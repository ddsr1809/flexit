#!/usr/bin/env bash
# Respaldo completo de la base, cifrado con restic en un bucket de otro proveedor.
#   /opt/constructora/respaldo.sh                      → etiqueta "diario" (timer de las 02:30)
#   /opt/constructora/respaldo.sh antes-de-desplegar   → lo llama deploy-prod.yml antes de migrar
#   /opt/constructora/respaldo.sh ensayo               → antes del ensayo general (H7)
#   /opt/constructora/respaldo.sh --iniciar            → crea el repositorio de restic (una vez)
#
# Cada respaldo guarda: el volcado de PostgreSQL (pg_dump -Fc), los roles sin contraseñas, el
# conteo exacto de renglones por tabla tomado en la misma instantánea que el volcado, la versión
# de la app y la configuración del servidor (.env y secrets/, que restic cifra). Al terminar avisa a
# Uptime Kuma; si falla, avisa la falla. Retención: 30 días completos, después 30 diarios,
# 12 semanales y 24 mensuales.
set -euo pipefail
# shellcheck source-path=SCRIPTDIR source=lib-operacion.sh
source "$(dirname "$(readlink -f "$0")")/lib-operacion.sh"

ETIQUETA=${1:-diario}
cargar_configuracion

if [[ $ETIQUETA == --iniciar ]]; then
  if restic cat config >/dev/null 2>&1; then
    registrar "El repositorio de restic ya existe: $RESTIC_REPOSITORY"
  else
    restic init
    registrar "Repositorio de restic creado: $RESTIC_REPOSITORY"
  fi
  exit 0
fi
[[ $ETIQUETA =~ ^[a-z0-9-]{3,30}$ ]] || fallar "Etiqueta inválida: $ETIQUETA"

INICIO=$(date +%s)
ACTUAL="$DIR_TRABAJO/actual"
export VERSION
VERSION=$(version_actual)

al_salir() {
  local codigo=$1
  rm -rf "$ACTUAL"
  ((codigo == 0)) && return
  registrar "Respaldo $ETIQUETA falló (código $codigo)"
  avisar "${KUMA_RESPALDO:-}" down "Respaldo $ETIQUETA falló en $(hostname) (código $codigo); ver operacion.log"
}
trap 'al_salir $?' EXIT

# Sin al menos 2 GB libres el volcado puede llenar el disco de datos y detener PostgreSQL.
libre_kb=$(df -Pk "$DIR_TRABAJO" | awk 'NR==2 {print $4}')
((libre_kb > 2 * 1024 * 1024)) || fallar "Menos de 2 GB libres en $DIR_TRABAJO"

rm -rf "$ACTUAL"
install -d -m 700 "$ACTUAL"
registrar "Respaldo $ETIQUETA de la versión ${VERSION:-desconocida}"

volcar_con_conteos "$ACTUAL/$BASE.dump" "$ACTUAL/conteos.tsv"
db pg_dumpall -U postgres --globals-only --no-role-passwords >"$ACTUAL/roles.sql"
db pg_restore --list <"$ACTUAL/$BASE.dump" >/dev/null ||
  fallar "El volcado no se puede leer con pg_restore"

{
  echo "version=${VERSION:-}"
  echo "servidor=$(hostname)"
  echo "fecha=$(date -u +%FT%TZ)"
  echo "etiqueta=$ETIQUETA"
  echo "tablas=$(wc -l <"$ACTUAL/conteos.tsv")"
  echo "renglones=$(awk -F'\t' '{s += $2} END {print s + 0}' "$ACTUAL/conteos.tsv")"
} >"$ACTUAL/respaldo.txt"

# Configuración del servidor: lo necesario para recrearlo (docs/runbooks/servidor-perdido.md).
# La llave de restic no va aquí: sin ella el respaldo no se abre, así que vive en el gestor de
# contraseñas de la empresa.
configuracion=()
for f in .env secrets respaldo.env .version; do
  [[ -e $DIR_APP/$f ]] && configuracion+=("$f")
done
if ((${#configuracion[@]} > 0)); then
  tar -C "$DIR_APP" -czf "$ACTUAL/configuracion.tar.gz" --exclude='secrets/restic_password' \
    "${configuracion[@]}"
fi

restic backup "$ACTUAL" --host "$RESTIC_HOST" --tag "$ETIQUETA" --quiet --json |
  tail -1 >"$DIR_TRABAJO/ultimo-respaldo.json"
INSTANTANEA=$(jq -r '.snapshot_id // empty' "$DIR_TRABAJO/ultimo-respaldo.json" | cut -c1-8)
[[ -n $INSTANTANEA ]] || fallar "restic no devolvió el identificador del respaldo"

# Retención por etiqueta (cada etiqueta es su propio grupo): todo lo de los últimos 30 días, y de
# ahí para atrás uno por día hasta 30 días, uno por semana hasta 12 y uno por mes hasta 24. Así un
# respaldo "antes-de-desplegar" o "ensayo" no desplaza al diario del mismo día. Lo marcado con la
# etiqueta "conservar" no se borra nunca.
restic forget --host "$RESTIC_HOST" --group-by host,tags --keep-within 30d \
  --keep-daily 30 --keep-weekly 12 --keep-monthly 24 --keep-tag conservar --quiet
# El domingo se libera espacio y se revisa una muestra de los datos del repositorio.
if [[ $(date +%u) == 7 && $ETIQUETA == diario ]]; then
  restic prune --quiet
  restic check --read-data-subset=5% --quiet
fi

TAMANO=$(du -sh "$ACTUAL/$BASE.dump" | cut -f1)
RENGLONES=$(grep '^renglones=' "$ACTUAL/respaldo.txt" | cut -d= -f2)

TARDO=$(duracion $(($(date +%s) - INICIO)))
registrar "Respaldo $ETIQUETA listo: $INSTANTANEA, $TAMANO, $RENGLONES renglones, $TARDO"
avisar "${KUMA_RESPALDO:-}" up "$ETIQUETA $INSTANTANEA · $TAMANO · $RENGLONES renglones · $TARDO"
echo "$INSTANTANEA"

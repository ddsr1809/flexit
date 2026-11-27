#!/usr/bin/env bash
# Funciones comunes de respaldo.sh, restaurar.sh y vigilar.sh. No se ejecuta: se carga con
#   source "$(dirname "$0")/lib-operacion.sh"
#
# Todo vive en /opt/constructora (DIR_APP). La configuración de respaldos está en
# /opt/constructora/respaldo.env (permisos 600, fuera del repositorio; ver respaldo.env.ejemplo).
# shellcheck shell=bash

DIR_APP=${DIR_APP:-/opt/constructora}
DIR_TRABAJO=${DIR_TRABAJO:-/mnt/datos/respaldos}
BASE=${BASE:-constructora}

registrar() {
  local linea
  linea="[$(date -u +%FT%TZ)] $*"
  echo "$linea" >&2
  echo "$linea" >>"$DIR_APP/operacion.log" 2>/dev/null || true
}

fallar() {
  registrar "ERROR: $*"
  exit 1
}

# Lee respaldo.env y deja exportadas las variables de restic (RESTIC_REPOSITORY,
# RESTIC_PASSWORD_FILE, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY) y las URL de Uptime Kuma.
cargar_configuracion() {
  local archivo=${CONFIG_RESPALDO:-$DIR_APP/respaldo.env}
  [[ -r $archivo ]] || fallar "Falta $archivo (ver docs/runbooks/respaldos.md)"
  set -a
  # shellcheck source=/dev/null
  source "$archivo"
  set +a
  : "${RESTIC_REPOSITORY:?Falta RESTIC_REPOSITORY en respaldo.env}"
  : "${RESTIC_PASSWORD_FILE:?Falta RESTIC_PASSWORD_FILE en respaldo.env}"
  [[ -s $RESTIC_PASSWORD_FILE ]] || fallar "La llave de restic ($RESTIC_PASSWORD_FILE) no existe o está vacía"
  RESTIC_HOST=${RESTIC_HOST:-$(hostname)}
  export RESTIC_HOST
}

# La versión que corre ahora: compose.yml la exige para cualquier comando.
version_actual() {
  [[ -n ${VERSION:-} ]] && { echo "$VERSION"; return; }
  cat "$DIR_APP/.version" 2>/dev/null || true
}

# Ejecuta un programa de PostgreSQL dentro del contenedor db. Con DB_LOCAL=1 (pruebas) lo ejecuta
# en esta máquina con las variables PG* del entorno.
db() {
  if [[ ${DB_LOCAL:-0} == 1 ]]; then
    "$@"
  else
    VERSION=${VERSION:-$(version_actual)} docker compose --project-directory "$DIR_APP" \
      -f "$DIR_APP/compose.yml" exec -T db "$@"
  fi
}

# psql como postgres, sin .psqlrc, sin adornos y deteniéndose en el primer error.
db_psql() {
  db psql -U postgres -X -At -q -v ON_ERROR_STOP=1 "$@"
}

# Avisa a un monitor de tipo push de Uptime Kuma: avisar <url> <up|down> <mensaje>.
# Sin URL no hace nada; si Uptime Kuma no contesta, se registra y se sigue.
avisar() {
  local url=$1 estado=$2 mensaje=$3
  [[ -n $url ]] || return 0
  curl -fsS -m 15 --retry 3 --retry-delay 5 -G "$url" \
    --data-urlencode "status=$estado" --data-urlencode "msg=${mensaje:0:240}" \
    --data-urlencode "ping=" >/dev/null 2>&1 ||
    registrar "No se pudo avisar a Uptime Kuma ($estado: $mensaje)"
}

# Volcado de la base y conteo exacto de renglones por tabla en la MISMA instantánea: el conteo
# describe exactamente lo que trae el volcado, aunque la app siga escribiendo mientras tanto.
#   volcar_con_conteos <archivo.dump> <conteos.tsv>
volcar_con_conteos() {
  local dump=$1 conteos=$2 instantanea linea
  local sql_conteos
  sql_conteos=$(cat "$(dirname "${BASH_SOURCE[0]}")/conteos.sql")

  coproc SESION { db_psql -d "$BASE" 2>&1; }
  local entrada=${SESION[1]} salida=${SESION[0]}
  printf '%s\n' "BEGIN ISOLATION LEVEL REPEATABLE READ, READ ONLY;" \
    "SELECT pg_export_snapshot();" >&"$entrada"
  read -r -t 60 instantanea <&"$salida" || fallar "PostgreSQL no respondió al abrir la instantánea"
  [[ $instantanea =~ ^[0-9A-F]+-[0-9A-F]+(-[0-9]+)?$ ]] ||
    fallar "No se pudo exportar la instantánea: $instantanea"

  db pg_dump -U postgres -d "$BASE" -Fc -Z 6 --snapshot="$instantanea" >"$dump" ||
    fallar "pg_dump falló"

  printf '%s\n' "$sql_conteos" "SELECT '-- fin --';" >&"$entrada"
  : >"$conteos"
  while read -r -t 600 linea <&"$salida"; do
    [[ $linea == '-- fin --' ]] && break
    [[ $linea == *$'\t'* ]] || fallar "Conteo inesperado: $linea"
    echo "$linea" >>"$conteos"
  done
  [[ $linea == '-- fin --' ]] || fallar "El conteo de renglones no terminó"
  echo "COMMIT;" >&"$entrada"
  exec {entrada}>&-
  wait "$SESION_PID" || fallar "La sesión de conteo terminó con error"
  [[ -s $conteos ]] || fallar "El conteo quedó vacío"
}

# Conteo de renglones por tabla de una base (la restaurada): conteos <base>
conteos() {
  db_psql -d "$1" -f - <"$(dirname "${BASH_SOURCE[0]}")/conteos.sql"
}

# Duración legible: duracion <segundos>
duracion() {
  printf '%d min %02d s' $(($1 / 60)) $(($1 % 60))
}

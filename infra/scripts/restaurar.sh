#!/usr/bin/env bash
# Restaura un respaldo de restic (docs/runbooks/restaurar.md).
#
#   restaurar.sh listar
#       Los respaldos de este servidor.
#   restaurar.sh prueba [instantanea]
#       Prueba de restauración (timer mensual, primer domingo 04:00): restaura en una base temporal
#       aislada, compara el conteo de renglones contra el del respaldo, aplica las migraciones y
#       arranca la versión actual de la app contra esa base. Al final borra todo. La base de
#       producción no se toca y los datos no salen del servidor.
#   restaurar.sh en-sitio <instantanea> --confirmo [--sin-respaldo-previo]
#       Reemplaza la base de este servidor por la del respaldo. Antes saca un respaldo
#       "antes-de-restaurar" (salvo --sin-respaldo-previo, para una base que ya no abre o un
#       servidor nuevo). Detiene la app, restaura, compara conteos, migra y arranca.
set -euo pipefail
# shellcheck source-path=SCRIPTDIR source=lib-operacion.sh
source "$(dirname "$(readlink -f "$0")")/lib-operacion.sh"

MODO=${1:-}
cargar_configuracion
export VERSION
VERSION=$(version_actual)

uso() {
  sed -n '2,15p' "$0" >&2
  exit 2
}

# ----------------------------------------------------------------------------- comunes

# Baja el respaldo a una carpeta y deja en ORIGEN la carpeta que trae el volcado.
bajar() {
  local instantanea=$1 destino=$2
  local -a filtro=()
  [[ $instantanea == latest ]] && filtro=(--host "$RESTIC_HOST")
  restic restore "$instantanea" "${filtro[@]}" --target "$destino" --quiet ||
    fallar "restic no pudo restaurar $instantanea"
  ORIGEN=$(dirname "$(find "$destino" -name "$BASE.dump" -print -quit)")
  [[ -f $ORIGEN/$BASE.dump && -f $ORIGEN/conteos.tsv ]] ||
    fallar "El respaldo $instantanea no trae $BASE.dump y conteos.tsv"
  registrar "Respaldo $instantanea bajado: $(tr '\n' ' ' <"$ORIGEN/respaldo.txt" 2>/dev/null)"
}

# Carga el volcado con el ejecutor dado (db o db_prueba) en una base recién creada con las
# propiedades del respaldo (dueño, permisos) y compara el conteo de renglones.
cargar_volcado() {
  local ejecutor=$1
  "$ejecutor" psql -U postgres -X -q -v ON_ERROR_STOP=1 -d postgres \
    -c "SET client_min_messages = warning" -c "DROP DATABASE IF EXISTS $BASE WITH (FORCE)" >/dev/null
  "$ejecutor" pg_restore -U postgres --create --exit-on-error -d postgres <"$ORIGEN/$BASE.dump" ||
    fallar "pg_restore falló"
  "$ejecutor" psql -U postgres -X -At -q -v ON_ERROR_STOP=1 -d "$BASE" -f - \
    <"$(dirname "$(readlink -f "$0")")/conteos.sql" >"$ORIGEN/conteos-restaurada.tsv"
  if ! diff -u "$ORIGEN/conteos.tsv" "$ORIGEN/conteos-restaurada.tsv" >"$ORIGEN/conteos.diff"; then
    head -40 "$ORIGEN/conteos.diff" >&2
    fallar "El conteo de renglones de la base restaurada no coincide con el del respaldo"
  fi
  TABLAS=$(wc -l <"$ORIGEN/conteos.tsv")
  RENGLONES=$(awk -F'\t' '{s += $2} END {print s + 0}' "$ORIGEN/conteos.tsv")
  registrar "Conteos idénticos: $TABLAS tablas, $RENGLONES renglones"
}

valor_env() {
  grep -E "^$1=" "$DIR_APP/.env" 2>/dev/null | tail -1 | cut -d= -f2- || true
}

# ----------------------------------------------------------------------------- prueba

ID_PRUEBA="restauracion-$$"
RED_PRUEBA="$ID_PRUEBA"
DB_PRUEBA="$ID_PRUEBA-db"
APP_PRUEBA="$ID_PRUEBA-app"

db_prueba() {
  if [[ ${DB_LOCAL:-0} == 1 ]]; then
    PGPORT=${PRUEBA_PGPORT:?Falta PRUEBA_PGPORT} "$@"
  else
    docker exec -i "$DB_PRUEBA" "$@"
  fi
}

limpiar_prueba() {
  if [[ ${DB_LOCAL:-0} != 1 ]]; then
    docker rm -f "$APP_PRUEBA" "$DB_PRUEBA" >/dev/null 2>&1 || true
    docker volume rm "$ID_PRUEBA-datos" >/dev/null 2>&1 || true
    docker network rm "$RED_PRUEBA" >/dev/null 2>&1 || true
  fi
  rm -rf "${TRABAJO_PRUEBA:-/nonexistent}"
}

arrancar_base_prueba() {
  [[ ${DB_LOCAL:-0} == 1 ]] && return
  docker network create --internal "$RED_PRUEBA" >/dev/null
  # Volumen de Docker propio (la imagen le pone los permisos de su usuario) que se borra al final.
  docker run -d --name "$DB_PRUEBA" --network "$RED_PRUEBA" --network-alias db \
    --memory 2g --shm-size 256m \
    -e POSTGRES_PASSWORD="$(openssl rand -hex 24)" \
    -v "$ID_PRUEBA-datos:/var/lib/postgresql" \
    postgres:18 -c max_connections=60 -c default_transaction_isolation='read committed' >/dev/null
  for _ in $(seq 1 60); do
    docker exec "$DB_PRUEBA" pg_isready -U postgres -q 2>/dev/null && return
    sleep 2
  done
  fallar "La base temporal no arrancó"
}

# Los roles del respaldo (sin la del superusuario) con contraseñas nuevas de un solo uso.
roles_prueba() {
  sed -E '/ROLE "?postgres"?[ ;]/d' "$ORIGEN/roles.sql" |
    db_prueba psql -U postgres -X -q -d postgres >/dev/null 2>"$ORIGEN/roles.err" || true
  CLAVE_APP=$(openssl rand -hex 24)
  CLAVE_MIGRADOR=$(openssl rand -hex 24)
  db_prueba psql -U postgres -X -q -v ON_ERROR_STOP=1 -d postgres \
    -v app="$CLAVE_APP" -v migrador="$CLAVE_MIGRADOR" >/dev/null <<'SQL'
ALTER ROLE app PASSWORD :'app';
ALTER ROLE migrador PASSWORD :'migrador';
SQL
}

# Migra con la versión que corre hoy (lo mismo que pasaría al restaurar y desplegar) y arranca la
# app contra la base temporal hasta que esté lista.
arrancar_app_prueba() {
  if [[ ${DB_LOCAL:-0} == 1 ]]; then
    registrar "Pruebas locales: se omite arrancar la app"
    return
  fi
  local imagen
  imagen="$(valor_env IMAGEN):$VERSION"
  docker image inspect "$imagen" >/dev/null 2>&1 || fallar "No está la imagen $imagen en el servidor"
  # Sin el .env del servidor: solo la base temporal y nada más (ni usuario inicial ni llaves).
  local -a comun=(
    --network "$RED_PRUEBA" --memory 1500m
    -e "DB_URL=jdbc:postgresql://db:5432/$BASE" -e DB_USUARIO=app -e "DB_CONTRASENA=$CLAVE_APP"
    -e "DB_MIGRADOR_URL=jdbc:postgresql://db:5432/$BASE" -e DB_MIGRADOR_USUARIO=migrador
    -e "DB_MIGRADOR_CONTRASENA=$CLAVE_MIGRADOR" -e APP_ENTORNO=restauracion
    -e APP_SEGUNDO_FACTOR_EXIGIR=false -e "APP_VERSION=$VERSION"
  )
  docker run --rm "${comun[@]}" -e SPRING_PROFILES_ACTIVE=prod,migrar "$imagen" >"$ORIGEN/migrar.log" 2>&1 ||
    { tail -30 "$ORIGEN/migrar.log" >&2; fallar "Las migraciones fallaron sobre la base restaurada"; }
  docker run -d --name "$APP_PRUEBA" "${comun[@]}" "$imagen" >/dev/null
  local estado=""
  for _ in $(seq 1 90); do
    estado=$(docker inspect -f '{{.State.Health.Status}}' "$APP_PRUEBA" 2>/dev/null || echo detenida)
    [[ $estado == healthy ]] && break
    [[ $estado == detenida || $estado == unhealthy ]] && break
    sleep 2
  done
  if [[ $estado != healthy ]]; then
    docker logs --tail 40 "$APP_PRUEBA" >&2 || true
    fallar "La app no quedó lista contra la base restaurada ($estado)"
  fi
  local version
  version=$(docker exec "$APP_PRUEBA" wget -qO- http://localhost:8080/api/v1/version || true)
  registrar "La app $VERSION arrancó contra la base restaurada: $version"
}

prueba() {
  local instantanea=${1:-latest}
  local inicio
  inicio=$(date +%s)
  TRABAJO_PRUEBA="$DIR_TRABAJO/$ID_PRUEBA"
  trap 'terminar_prueba $?' EXIT
  install -d -m 700 "$TRABAJO_PRUEBA"
  registrar "Prueba de restauración de $instantanea"
  bajar "$instantanea" "$TRABAJO_PRUEBA/respaldo"
  arrancar_base_prueba
  roles_prueba
  cargar_volcado db_prueba
  arrancar_app_prueba
  TARDO=$(($(date +%s) - inicio))
}

terminar_prueba() {
  local codigo=$1
  limpiar_prueba
  if ((codigo == 0)); then
    local mensaje
    mensaje="Restauración de prueba correcta en $(duracion "$TARDO"): $TABLAS tablas, $RENGLONES renglones, app $VERSION lista"
    registrar "$mensaje"
    echo "$(date -u +%FT%TZ)	correcta	$TARDO	$TABLAS	$RENGLONES	$VERSION" >>"$DIR_APP/restauraciones.log"
  else
    # vigilar.sh sigue avisando mientras la última prueba sea fallida.
    echo "$(date -u +%FT%TZ)	fallida	-	-	-	$VERSION" >>"$DIR_APP/restauraciones.log" || true
    avisar "${KUMA_VIGILAR:-}" down "La prueba de restauración falló en $(hostname); ver operacion.log"
  fi
}

# ----------------------------------------------------------------------------- en sitio

en_sitio() {
  local instantanea=${1:-} confirmo=no previo=si
  shift || true
  for opcion in "$@"; do
    case $opcion in
      --confirmo) confirmo=si ;;
      --sin-respaldo-previo) previo=no ;;
      *) uso ;;
    esac
  done
  [[ -n $instantanea && $instantanea != --* ]] || uso
  [[ $confirmo == si ]] || fallar "Esto reemplaza la base de $(hostname). Repite con --confirmo"
  [[ -n $VERSION ]] || fallar "No hay .version: despliega primero la app (docs/runbooks/servidor-perdido.md)"

  local inicio
  inicio=$(date +%s)
  TRABAJO_SITIO="$DIR_TRABAJO/en-sitio-$$"
  trap 'rm -rf "$TRABAJO_SITIO"' EXIT
  install -d -m 700 "$TRABAJO_SITIO"

  # Primero se baja el respaldo elegido; después se respalda lo que hay ahora.
  bajar "$instantanea" "$TRABAJO_SITIO"
  if [[ $previo == si ]]; then
    registrar "Respaldo previo antes de restaurar"
    "$(dirname "$(readlink -f "$0")")/respaldo.sh" antes-de-restaurar >/dev/null
  fi

  registrar "Deteniendo la app"
  [[ ${DB_LOCAL:-0} == 1 ]] || docker compose --project-directory "$DIR_APP" stop app
  cargar_volcado db

  if [[ ${DB_LOCAL:-0} != 1 ]]; then
    registrar "Migraciones y arranque de $VERSION"
    (cd "$DIR_APP" && docker compose run --rm --no-deps -e SPRING_PROFILES_ACTIVE=prod,migrar app)
    (cd "$DIR_APP" && docker compose up -d app)
    local lista=no
    for _ in $(seq 1 60); do
      if curl -fsS http://127.0.0.1:8080/actuator/health/readiness >/dev/null 2>&1; then
        lista=si
        break
      fi
      sleep 2
    done
    [[ $lista == si ]] || fallar "La app no quedó lista después de restaurar; ver docs/runbooks/incidente.md"
  fi
  local tardo=$(($(date +%s) - inicio))
  registrar "Restaurado $instantanea en sitio en $(duracion "$tardo"): $TABLAS tablas, $RENGLONES renglones"
  echo "$(date -u +%FT%TZ)	en-sitio	$tardo	$TABLAS	$RENGLONES	$VERSION	$instantanea" >>"$DIR_APP/restauraciones.log"
}

case $MODO in
  listar) restic snapshots --host "$RESTIC_HOST" --compact ;;
  prueba) prueba "${2:-latest}" ;;
  en-sitio)
    shift
    en_sitio "$@"
    ;;
  *) uso ;;
esac

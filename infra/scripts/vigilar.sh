#!/usr/bin/env bash
# Latido del servidor a Uptime Kuma cada 5 minutos (timer constructora-vigilar). Revisa lo que el
# monitor HTTP no ve desde fuera: disco, memoria, contenedores y la antigüedad del último respaldo.
# Si algo está mal manda "down" con el motivo; si el servidor se apaga, deja de llegar el latido y
# Uptime Kuma avisa igual.
set -euo pipefail
# shellcheck source-path=SCRIPTDIR source=lib-operacion.sh
source "$(dirname "$(readlink -f "$0")")/lib-operacion.sh"

if [[ -r ${CONFIG_RESPALDO:-$DIR_APP/respaldo.env} ]]; then
  set -a
  # shellcheck source=/dev/null
  source "${CONFIG_RESPALDO:-$DIR_APP/respaldo.env}"
  set +a
fi
export VERSION
VERSION=$(version_actual)

problemas=()
resumen=()

for punto in / /mnt/datos; do
  mountpoint -q "$punto" || [[ $punto == / ]] || continue
  uso=$(df -P "$punto" | awk 'NR==2 {gsub("%", "", $5); print $5}')
  resumen+=("disco $punto $uso%")
  ((uso < ${DISCO_LIMITE:-85})) || problemas+=("disco $punto al $uso%")
done

libre_mb=$(awk '/MemAvailable/ {print int($2 / 1024)}' /proc/meminfo)
resumen+=("memoria libre ${libre_mb} MB")
((libre_mb > ${MEMORIA_MINIMA_MB:-300})) || problemas+=("solo ${libre_mb} MB de memoria libre")

for servicio in db app caddy; do
  estado=$(docker compose --project-directory "$DIR_APP" ps -a --format '{{.State}} {{.Health}}' \
    "$servicio" 2>/dev/null || true)
  case $estado in
    "running healthy" | "running ") ;;
    "") problemas+=("$servicio no existe") ;;
    running*) problemas+=("$servicio ${estado#running }") ;;
    *) problemas+=("$servicio detenido (${estado% })") ;;
  esac
done

# El monitor del respaldo ya avisa si no llega el diario; esto cubre que llegue pero viejo.
if [[ -f $DIR_TRABAJO/ultimo-respaldo.json ]]; then
  horas=$((($(date +%s) - $(stat -c %Y "$DIR_TRABAJO/ultimo-respaldo.json")) / 3600))
  resumen+=("respaldo hace ${horas} h")
  ((horas <= 26)) || problemas+=("último respaldo hace $horas h")
fi

# La prueba mensual de restauración: la última tiene que ser correcta y de hace menos de 36 días.
if grep -qE '^RESTAURACION_MENSUAL=si' "${CONFIG_RESPALDO:-$DIR_APP/respaldo.env}" 2>/dev/null; then
  bitacora="$DIR_APP/restauraciones.log"
  ultima=$(grep -E $'\t(correcta|fallida)\t' "$bitacora" 2>/dev/null | tail -1 || true)
  if [[ $ultima == *$'\tfallida\t'* ]]; then
    problemas+=("la última prueba de restauración falló")
  else
    desde=$(stat -c %Y "$bitacora" 2>/dev/null || date +%s)
    [[ -n $ultima ]] && desde=$(date -d "${ultima%%$'\t'*}" +%s)
    dias=$((($(date +%s) - desde) / 86400))
    resumen+=("restauración hace ${dias} d")
    ((dias <= 36)) || problemas+=("sin prueba de restauración correcta en $dias días")
  fi
fi

if ((${#problemas[@]} > 0)); then
  printf -v mensaje '%s; ' "${problemas[@]}"
  mensaje="$(hostname): ${mensaje%; }"
  registrar "Vigilancia: $mensaje"
  avisar "${KUMA_VIGILAR:-}" down "$mensaje"
  exit 1
fi
printf -v texto '%s · ' "${resumen[@]}"
avisar "${KUMA_VIGILAR:-}" up "${texto% · }"

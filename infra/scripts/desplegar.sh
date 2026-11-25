#!/usr/bin/env bash
# Despliega una versión en este servidor. Lo llama GitHub Actions por SSH (vía Tailscale):
#   /opt/constructora/desplegar.sh <sha-de-la-imagen>
#
# 1. Descarga la imagen.  2. Aplica migraciones con el rol migrador (misma imagen, perfil migrar).
# 3. Arranca la versión nueva.  4. Si no está sana en 2 minutos, regresa a la anterior.
# Como toda migración es compatible hacia atrás, regresar la imagen no exige regresar la base.
set -euo pipefail
cd /opt/constructora

NUEVA=${1:?Uso: desplegar.sh <version>}
ANTERIOR=$(cat .version 2>/dev/null || true)
export VERSION=$NUEVA

registrar() { echo "[$(date -u +%FT%TZ)] $*" | tee -a despliegues.log; }

registrar "Desplegando $NUEVA (anterior: ${ANTERIOR:-ninguna})"
docker compose pull --quiet app

registrar "Migraciones"
docker compose run --rm --no-deps -e SPRING_PROFILES_ACTIVE=prod,migrar app

docker compose up -d --remove-orphans

for _ in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/actuator/health/readiness >/dev/null 2>&1; then
    echo "$NUEVA" > .version
    [[ -n "$ANTERIOR" && "$ANTERIOR" != "$NUEVA" ]] && echo "$ANTERIOR" > .version.anterior
    registrar "Listo: $NUEVA está sana"
    docker image prune -f --filter "until=168h" >/dev/null || true
    exit 0
  fi
  sleep 2
done

registrar "La versión $NUEVA no pasó la revisión de salud"
docker compose logs --tail 80 app || true
if [[ -n "$ANTERIOR" ]]; then
  registrar "Regresando a $ANTERIOR"
  VERSION=$ANTERIOR docker compose up -d --no-deps app
fi
exit 1

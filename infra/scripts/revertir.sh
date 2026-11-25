#!/usr/bin/env bash
# Regresa a la versión anterior en unos segundos (ver docs/runbooks/revertir.md).
#   /opt/constructora/revertir.sh            → a la de .version.anterior
#   /opt/constructora/revertir.sh <version>  → a una versión concreta
set -euo pipefail
cd /opt/constructora
DESTINO=${1:-$(cat .version.anterior)}
ACTUAL=$(cat .version)
echo "Revirtiendo $ACTUAL → $DESTINO"
VERSION=$DESTINO docker compose up -d --no-deps app
for _ in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/actuator/health/readiness >/dev/null 2>&1; then
    echo "$DESTINO" > .version
    echo "$ACTUAL" > .version.anterior
    echo "[$(date -u +%FT%TZ)] Revertido a $DESTINO" | tee -a despliegues.log
    exit 0
  fi
  sleep 2
done
echo "La versión $DESTINO tampoco responde: ver docs/runbooks/incidente.md" >&2
exit 1

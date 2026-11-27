#!/usr/bin/env bash
# Copia la definición del entorno a un servidor y deja instalada la operación. Lo corren
# release.yml (preproducción) y deploy-prod.yml (producción) desde el runner, ya dentro de
# Tailscale y con la llave SSH del entorno:
#   infra/scripts/copiar-entorno.sh <host> [--monitoreo]
# Con --monitoreo además levanta Uptime Kuma (solo preproducción, ADR-013).
set -euo pipefail
cd "$(dirname "$0")/../.."
HOST=${1:?Uso: copiar-entorno.sh <host> [--monitoreo]}
DESTINO="deploy@$HOST"

chmod +x infra/scripts/*.sh infra/db/init/*.sh
ssh "$DESTINO" "install -d -m 0750 /opt/constructora/systemd /opt/constructora/db-init"
scp -pq infra/compose/compose.yml infra/compose/Caddyfile infra/scripts/*.sh infra/scripts/conteos.sql \
  "$DESTINO:/opt/constructora/"
scp -pq infra/systemd/constructora-* "$DESTINO:/opt/constructora/systemd/"
scp -pq infra/db/init/*.sh "$DESTINO:/opt/constructora/db-init/"
ssh "$DESTINO" "sudo /opt/constructora/instalar-operacion.sh"

if [[ ${2:-} == --monitoreo ]]; then
  ssh "$DESTINO" "sudo install -d -o deploy -g deploy -m 0750 /opt/monitoreo"
  scp -pq infra/monitoreo/compose.yml "$DESTINO:/opt/monitoreo/compose.yml"
  ssh "$DESTINO" "cd /opt/monitoreo && docker compose pull -q && docker compose up -d --remove-orphans" ||
    echo "::warning::No se pudo actualizar Uptime Kuma en $HOST"
fi

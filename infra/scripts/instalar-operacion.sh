#!/usr/bin/env bash
# Deja en este servidor los timers de operación: respaldo diario, prueba mensual de restauración
# y latido a Uptime Kuma. Idempotente: lo corre cada despliegue (release.yml y deploy-prod.yml)
# después de copiar los scripts, así que un servidor ya creado también recibe los cambios.
#   sudo /opt/constructora/instalar-operacion.sh
set -euo pipefail
DIR_APP=${DIR_APP:-/opt/constructora}
[[ $EUID -eq 0 ]] || { echo "Correr con sudo" >&2; exit 1; }

command -v restic >/dev/null || DEBIAN_FRONTEND=noninteractive apt-get install -y -q restic >/dev/null
install -d -o deploy -g deploy -m 700 /mnt/datos/respaldos
# Solo si no existen: la fecha de restauraciones.log vacío cuenta como "desde cuándo" para vigilar.sh.
for bitacora in operacion.log restauraciones.log; do
  [[ -e $DIR_APP/$bitacora ]] || touch "$DIR_APP/$bitacora"
done
chown deploy:deploy "$DIR_APP/operacion.log" "$DIR_APP/restauraciones.log"

cambio=no
for unidad in "$DIR_APP"/systemd/constructora-*.service "$DIR_APP"/systemd/constructora-*.timer; do
  destino=/etc/systemd/system/$(basename "$unidad")
  if ! cmp -s "$unidad" "$destino"; then
    install -m 644 "$unidad" "$destino"
    cambio=si
  fi
done
[[ $cambio == si ]] && systemctl daemon-reload

systemctl enable --now constructora-vigilar.timer >/dev/null 2>&1

if [[ -f $DIR_APP/respaldo.env ]]; then
  chown deploy:deploy "$DIR_APP/respaldo.env"
  chmod 600 "$DIR_APP/respaldo.env"
  systemctl enable --now constructora-respaldo.timer >/dev/null 2>&1
  if grep -qE '^RESTAURACION_MENSUAL=si' "$DIR_APP/respaldo.env"; then
    systemctl enable --now constructora-restauracion.timer >/dev/null 2>&1
  else
    systemctl disable --now constructora-restauracion.timer >/dev/null 2>&1 || true
  fi
  echo "Operación instalada en $(hostname): latido, respaldo diario$(grep -qE '^RESTAURACION_MENSUAL=si' "$DIR_APP/respaldo.env" && echo ' y prueba mensual de restauración')."
else
  systemctl disable --now constructora-respaldo.timer constructora-restauracion.timer >/dev/null 2>&1 || true
  echo "::warning::$(hostname) no tiene respaldo.env: respaldos desactivados (docs/runbooks/respaldos.md)"
fi

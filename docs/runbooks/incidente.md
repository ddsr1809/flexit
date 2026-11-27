# Incidente

Algo impide trabajar en el sistema: no responde, no deja aplicar vales, o los números están mal.

## Quién

| Papel | Quién | Qué hace |
| --- | --- | --- |
| Decide | Dirección | Si se sigue esperando, se pasa a papel o se restaura un respaldo |
| Atiende | Responsable técnico (o el suplente de `acceso-emergencia.md`) | Diagnostica y corrige |
| Avisa | Coordinación de obra | Al almacén y a los residentes, por el grupo de obra |

## Primeros 15 minutos

1. Confirmar: ¿Uptime Kuma lo marca? ¿Pasa en más de una computadora?
2. Avisar en el grupo de obra: **"El sistema no está disponible. Se captura en vales de papel con
   folio preimpreso desde ahora."**
3. Revisar, por Tailscale:

   ```bash
   ssh deploy@constructora-prod
   cd /opt/constructora
   VERSION=$(cat .version) docker compose ps
   VERSION=$(cat .version) docker compose logs --tail 100 app
   tail -20 despliegues.log operacion.log
   df -h / /mnt/datos
   ```

## Según lo que se encuentre

| Situación | Acción | Runbook |
| --- | --- | --- |
| Empezó con un despliegue | Revertir la versión | `revertir.md` |
| La app no arranca y la base sí | Logs de la app; revertir si es la versión | `revertir.md` |
| La base no arranca | Logs de `db`; disco lleno o dañado | `restaurar.md` o `servidor-perdido.md` |
| El servidor no responde por Tailscale | Panel de Vultr: consola, reinicio | `servidor-perdido.md` |
| Datos dañados (una migración, un error) | Detener capturas; decidir con dirección si se restaura | `restaurar.md` |
| Sospecha de acceso indebido | Cambiar contraseñas de usuarios afectados y llaves | `rotar-secretos.md` |

Criterio que se acordó para el arranque (H8): si el almacén no pudo registrar salidas más de 2
horas seguidas por una falla del sistema, se regresa a papel ese día y se analiza.

## Al volver

1. Capturar los vales de papel en orden de folio, con su fecha real.
2. Revisar que el último vale de papel quedó en el sistema.
3. Issue con etiqueta `incidente`: qué pasó, cuánto duró, qué se perdió, qué se cambia para que
   no se repita.

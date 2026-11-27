# Revertir una versión

Tiempo esperado: menos de 2 minutos (criterio de H7).

## Lo más rápido: en el servidor

```bash
ssh deploy@constructora-prod          # o constructora-preprod, por Tailscale
/opt/constructora/revertir.sh         # a la versión anterior (.version.anterior)
/opt/constructora/revertir.sh <sha>   # a una versión concreta (ver despliegues.log)
```

La imagen anterior sigue en el servidor (no se borran imágenes con etiqueta), así que no hace
falta descargar nada.

## Desde GitHub (sin SSH)

*Actions › deploy-prod › Run workflow* con el SHA completo de la versión a la que se regresa (solo
para quien está en `APROBADORES`). Saca un respaldo antes, igual que un despliegue normal.

## Y la base

La base no se revierte: todas las migraciones son compatibles hacia atrás (ADR-003), así que la
imagen anterior funciona con el esquema nuevo. Si una migración rompió **datos**, no se improvisa
SQL en el servidor: se restaura el respaldo `antes-de-desplegar` de esa versión (`restaurar.md`,
"En sitio") y se abre un issue. Lo capturado entre el despliegue y la restauración se vuelve a
capturar desde los vales de papel (`incidente.md`).

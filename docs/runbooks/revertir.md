# Revertir una versión

Tiempo esperado: menos de 2 minutos.

```bash
ssh deploy@constructora-preprod        # por Tailscale
/opt/constructora/revertir.sh          # a la versión anterior (.version.anterior)
/opt/constructora/revertir.sh <sha>    # a una versión concreta (ver despliegues.log)
```

La base no se revierte: todas las migraciones son compatibles hacia atrás (ADR-003), así que la
imagen anterior funciona con el esquema nuevo. Si una migración rompió datos, no se improvisa SQL
en el servidor: se restaura el respaldo (H7, `restaurar.md`) y se abre un issue.

Después de revertir, abrir un issue con la etiqueta `tipo:bug` y el SHA que falló, y revertir el
commit en `main` con un PR para que el siguiente despliegue no lo vuelva a subir.

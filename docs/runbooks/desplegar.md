# Desplegar

## Preproducción

Automática: cada fusión a `main` con CI en verde dispara `release.yml`. Para volver a desplegar a
mano: *Actions › release › Run workflow*.

Qué hace, en orden (`infra/scripts/desplegar.sh`):

1. Copia `compose.yml`, `Caddyfile`, los scripts, los timers y `db-init/` al servidor
   (`copiar-entorno.sh`), instala los timers (`instalar-operacion.sh`) y actualiza Uptime Kuma.
2. Descarga la imagen `ghcr.io/…/constructora:<sha>` con el token de la ejecución.
3. Corre las migraciones: la misma imagen con el perfil `migrar` y el rol `migrador`.
4. Arranca la versión nueva y espera hasta 2 minutos a `/actuator/health/readiness`.
5. Si no responde, regresa sola a la anterior y el workflow queda en rojo.
6. Comprueba desde internet que `/api/v1/version` responde con el SHA nuevo.
7. Etiqueta la imagen como `preproduccion-<sha>`: solo esas imágenes pueden ir a producción.

## Producción

Solo con un release aprobado (`deploy-prod.yml`):

1. Elegir la versión: un commit de `main` que ya quedó sano en preproducción y se probó ahí.
2. *Releases › Draft a new release* → etiqueta `vX.Y.Z` sobre ese commit → notas (qué cambia para
   el almacén y la oficina) → *Publish release*. Publicar es la aprobación: si quien publica no está
   en `APROBADORES`, el workflow se detiene.
3. El workflow revisa que la imagen tenga la etiqueta `preproduccion-<sha>`, saca un respaldo
   `antes-de-desplegar`, migra, arranca y revisa salud. Si no queda sana, regresa sola a la
   versión anterior.
4. Al final etiqueta la imagen como `vX.Y.Z` y `produccion`.

Ventana: de lunes a sábado después de las 19:00, o domingo. El corte es de unos segundos.

Qué revisar después: *Actions › deploy-prod* en verde, `https://sistema…/api/v1/version`, en
Uptime Kuma el monitor de producción en verde, y en el servidor:

```bash
tail /opt/constructora/despliegues.log
tail /opt/constructora/operacion.log      # el respaldo antes de desplegar
```

Si la versión nueva tiene un problema que no se ve en la revisión de salud: `revertir.md`.

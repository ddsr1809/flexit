# Desplegar

**Preproducción** es automática: cada fusión a `main` con CI en verde dispara `release.yml`.
Para volver a desplegar a mano: *Actions › release › Run workflow*.

Qué hace, en orden (`infra/scripts/desplegar.sh`):

1. Copia `compose.yml`, `Caddyfile`, los scripts y `db-init/` al servidor (viajan con el código).
2. Descarga la imagen `ghcr.io/…/constructora:<sha>` con el token de la ejecución.
3. Corre las migraciones: la misma imagen con el perfil `migrar` y el rol `migrador`.
4. Arranca la versión nueva y espera hasta 2 minutos a `/actuator/health/readiness`.
5. Si no responde, regresa sola a la anterior y el workflow queda en rojo.
6. El workflow comprueba desde internet que `/api/v1/version` responde con el SHA nuevo.

Qué revisar después: *Actions* en verde, `https://preprod…/api/v1/version`, y en el servidor
`tail /opt/constructora/despliegues.log`.

**Producción** llega en H7 (`deploy-prod.yml`): solo desde un release publicado por alguien de la
lista de aprobadores, con la misma imagen que ya pasó por preproducción.

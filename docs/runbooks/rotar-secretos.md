# Rotar secretos

Cada 6 meses, cuando alguien con acceso deja la empresa, o después de un incidente. Una cosa a la
vez, en preproducción primero, y el gestor de contraseñas actualizado al terminar cada una.

| Secreto | Cómo | Corte |
| --- | --- | --- |
| Contraseña de `app` en PostgreSQL | `openssl rand -base64 32 \| tr -d '/+='` → `ALTER ROLE app PASSWORD '…'` (como postgres, en `docker compose exec db psql`) → mismo valor en `secrets/pg_app_password` y `DB_CONTRASENA` del `.env` → `docker compose up -d app` | Segundos |
| Contraseña de `migrador` | Igual, con `pg_migrador_password` y `DB_MIGRADOR_CONTRASENA` (se usa en el siguiente despliegue) | Ninguno |
| Llave SSH de `deploy` | Nueva llave; agregar la pública a `~/.ssh/authorized_keys`; actualizar `PROD_SSH_KEY`/`PREPROD_SSH_KEY` en GitHub; correr un despliegue; quitar la vieja | Ninguno |
| Llave de B2 de respaldos | Nueva llave para el bucket → `respaldo.env` → `./respaldo.sh` a mano → borrar la vieja en B2 | Ninguno |
| Llave de restic | `restic key add` (pide la nueva) → `secrets/restic_password` → `./respaldo.sh` → `restic key list` y `restic key remove <id-de-la-vieja>` | Ninguno |
| Tokens de Vultr y Cloudflare | Nuevos en cada panel, con los mismos permisos; borrar los viejos | Ninguno |
| Cliente OAuth de Tailscale | Nuevo; actualizar `TS_OAUTH_*` en GitHub; borrar el viejo | Ninguno |
| `APP_LLAVE_SEGUNDO_FACTOR` | **No se rota**: cambiarla obliga a todos a dar de alta otra vez su segundo factor. Solo si se filtró: nueva llave en el `.env`, `docker compose up -d app`, `DELETE FROM organizacion.segundo_factor;` como migrador y avisar a administración y dirección | Cada quien vuelve a darlo de alta |

Al salir alguien, además: quitarlo de la organización de GitHub, de `APROBADORES`, de Tailscale,
de la bóveda "Sistema · emergencia" y desactivar su usuario del sistema.

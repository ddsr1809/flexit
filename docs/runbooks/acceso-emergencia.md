# Acceso de emergencia

**Al menos una persona además del desarrollador puede operar y recuperar el sistema.** Lo que sigue
vive en el gestor de contraseñas de la empresa, en una bóveda compartida "Sistema · emergencia",
con acceso para el responsable técnico y su suplente (de dirección o administración, con nombre).

| Llave | Para qué | Sin ella |
| --- | --- | --- |
| Cuenta de GitHub de la organización (dueño) y sus códigos de recuperación | Código, despliegues, `APROBADORES` | No se despliega ni se revierte desde GitHub |
| Vultr (cuenta y token de API) | Consola del servidor, Terraform | No se recrea el servidor |
| Cloudflare (cuenta y token) | DNS | No se cambia a dónde apunta el dominio |
| Tailscale (administración) | Entrar por SSH | No se entra a los servidores |
| Backblaze B2 (cuenta, llave de respaldos y del estado de Terraform) | Respaldos y Terraform | No se bajan los respaldos |
| **Llave de restic de producción** | Abrir los respaldos | **Los respaldos no sirven** |
| Llaves SSH `constructora-prod` y `constructora-preprod` | Entrar como `deploy` | Hay que crear otras desde Vultr |
| `APP_LLAVE_SEGUNDO_FACTOR` de cada entorno | Segundo factor | Todos vuelven a dar de alta el suyo |
| Usuario administrador de Uptime Kuma y bot de Telegram | Avisos | Se reinstala |
| Usuario de administración del sistema, con segundo factor en el teléfono del suplente | Entrar a la app | Se restablece desde la base (`segundo-factor.md`) |

## Prueba (cada 6 meses, con `rotar-secretos.md`)

El suplente, sin ayuda y siguiendo los runbooks:

1. Entra a Tailscale y por SSH a `constructora-prod`.
2. Corre `./restaurar.sh listar` y `./restaurar.sh prueba`.
3. Revierte preproducción a la versión anterior (`revertir.md`) y la vuelve a desplegar.

Se anota la fecha y el resultado en el issue fijado "Acceso de emergencia".

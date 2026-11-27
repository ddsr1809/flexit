# Segundo factor

Desde H7, **administración y dirección** entran con usuario, contraseña y un código de 6 dígitos
que cambia cada 30 segundos en una app del teléfono (Google Authenticator, Microsoft
Authenticator, 1Password…). Los demás roles entran solo con contraseña.

## La primera vez

Después de la contraseña, el sistema muestra un código QR:

1. Instalar la app en el teléfono, *Agregar cuenta › Escanear código QR*.
2. Escribir el código de 6 dígitos que aparece. Con eso queda activo y entra.

Si el teléfono no puede escanear, *¿No puedes escanear?* muestra la clave para escribirla a mano
(tipo "basada en tiempo").

## Cada día

Usuario y contraseña, y después el código de la app. Un código sirve una sola vez. Cinco códigos
equivocados piden otra vez la contraseña y bloquean la cuenta 15 minutos, igual que cinco
contraseñas equivocadas.

## Perdió o cambió de teléfono

Otra persona de administración: *Usuarios › Segundo factor › Restablecer*. En su siguiente inicio
de sesión, la persona escanea un código nuevo. Nadie restablece el suyo propio.

**Si la única persona de administración perdió el teléfono**, el responsable técnico, por SSH:

```bash
ssh deploy@constructora-prod
cd /opt/constructora
VERSION=$(cat .version) docker compose exec db psql -U postgres -d constructora -c \
  "DELETE FROM organizacion.segundo_factor WHERE usuario_id = (SELECT id FROM organizacion.usuario WHERE usuario = 'admin')"
```

(La bitácora registra el borrado.) Para que esto no pase, el suplente de `acceso-emergencia.md`
tiene su propio usuario de administración con segundo factor.

## Configuración del servidor

| Variable del `.env` | Valor |
| --- | --- |
| `APP_LLAVE_SEGUNDO_FACTOR` | `openssl rand -base64 32`, una por entorno, en el gestor de contraseñas. Cifra los secretos en la base (un volcado sin esta llave no sirve para generar códigos) |
| `APP_SEGUNDO_FACTOR_EXIGIR` | `true` por omisión. Sin llave válida y con `true`, la app no arranca: el despliegue regresa solo a la versión anterior |
| `APP_SEGUNDO_FACTOR_EMISOR` | El nombre que se ve en la app del teléfono (`Constructora`) |

En local (`make dev`) y en las pruebas no se exige; `SegundoFactorTest` lo prende.

# Primer arranque de producción (H7 · issue 39)

Se hace una sola vez. Toma unas 2 horas. Requiere el runbook de preproducción ya hecho
(`primer-arranque.md`): las cuentas, el repositorio y Tailscale son los mismos.

## 1. Cuentas y llaves nuevas

| Qué | Dónde | Nota |
| --- | --- | --- |
| Llave SSH de producción | `ssh-keygen -t ed25519 -f ~/.ssh/constructora-prod -C deploy@constructora-prod` | Distinta de la de preproducción |
| Bucket de respaldos | Backblaze B2: `constructora-respaldos`, privado, cifrado del servidor activado | **Regla de ciclo de vida: "Keep prior versions for 30 days"**. Un servidor comprometido puede borrar, pero B2 conserva lo borrado 30 días |
| Llave de aplicación de B2 | Solo para `constructora-respaldos`, lectura y escritura | Va en `respaldo.env` |
| Llave de restic | `openssl rand -base64 48` | **Sin ella los respaldos no se abren.** Al gestor de contraseñas antes de seguir |
| Llave del segundo factor | `openssl rand -base64 32` | Una por entorno; al gestor de contraseñas |
| Clave de Tailscale | Un solo uso, etiqueta `tag:servidor` | |

## 2. Servidor con Terraform

```bash
cd infra/terraform/produccion
cp terraform.tfvars.ejemplo terraform.tfvars        # llave pública de producción, zona, dominio
export VULTR_API_KEY=… CLOUDFLARE_API_TOKEN=… AWS_ACCESS_KEY_ID=… AWS_SECRET_ACCESS_KEY=…
export TF_VAR_tailscale_clave=tskey-auth-…
terraform init -backend-config=../estado.s3.tfbackend
terraform plan -out plan && terraform apply plan
```

Sube `.terraform.lock.hcl` de `produccion/` en el mismo PR. cloud-init tarda unos 5 minutos; el
servidor aparece en Tailscale como `constructora-prod`.

## 3. Secretos y configuración en el servidor

```bash
ssh deploy@constructora-prod
cd /opt/constructora
for s in pg_postgres_password pg_migrador_password pg_app_password; do
  openssl rand -base64 32 | tr -d '/+=' > secrets/$s
done
printf '%s' 'LA-LLAVE-DE-RESTIC-DEL-GESTOR' > secrets/restic_password
chmod 600 secrets/*
```

- `/opt/constructora/.env` desde `infra/compose/.env.ejemplo` (permisos 600), con los valores de
  producción que trae el comentario del ejemplo: `DOMINIO=sistema.constructora.mx`,
  `APP_ENTORNO=produccion`, `APP_MEMORIA=3g`, `PG_SHARED_BUFFERS=2GB`, `PG_EFFECTIVE_CACHE=5GB`,
  `APP_LLAVE_SEGUNDO_FACTOR`, las contraseñas de la base y el usuario inicial.
- `/opt/constructora/respaldo.env` desde `infra/compose/respaldo.env.ejemplo` (permisos 600), con
  `RESTIC_REPOSITORY=s3:https://…/constructora-respaldos/produccion`, la llave de B2,
  `RESTIC_HOST=constructora-prod` y `RESTAURACION_MENSUAL=si`. Las URL de Uptime Kuma se llenan en
  `monitoreo.md`.

## 4. GitHub

*Settings › Environments › produccion*:

| Nombre | Tipo | Valor |
| --- | --- | --- |
| `PROD_HOST` | Variable | `constructora-prod` |
| `PROD_URL` | Variable | `https://sistema.constructora.mx` |
| `PROD_SSH_KEY` | Secreto | `~/.ssh/constructora-prod` (privada) |
| `TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET` | Secretos | Los mismos de preproducción |

En el mismo entorno, *Deployment branches and tags*: la rama `main` (para revertir o volver a
desplegar a mano) y las etiquetas `v*` (los releases).

*Settings › Secrets and variables › Actions › Variables* (del repositorio):
`APROBADORES` = `["usuario-responsable-tecnico", "usuario-direccion"]`. Solo quien está en esta
lista puede desplegar a producción, publicando un release o a mano.

## 5. Primer despliegue

1. Que la versión que se va a publicar ya esté sana en preproducción (*Actions › release* en verde
   después de este PR; deja la etiqueta `preproduccion-<sha>` en la imagen).
2. *Releases › Draft a new release*: etiqueta `v0.7.0` sobre `main`, *Publish*. Corre
   `deploy-prod.yml`: copia scripts y timers, migra, arranca y comprueba desde internet.
3. En el servidor: `./respaldo.sh --iniciar` (crea el repositorio de restic) y
   `./respaldo.sh` (primer respaldo). `restic snapshots` debe mostrarlo.
4. Entrar a `https://sistema.constructora.mx` como el usuario inicial: pide dar de alta el
   segundo factor (`segundo-factor.md`). Borrar las líneas `APP_INICIAL_*` del `.env`.
5. Lista de verificación: `lista-produccion.md`.

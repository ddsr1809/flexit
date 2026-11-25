# Primer arranque de preproducción

Se hace una sola vez, al cerrar H1. Toma de 1 a 2 horas, casi todo creando cuentas. Después, cada
fusión a `main` llega sola a preproducción.

## 1. Cuentas (a nombre de la empresa, no de una persona)

| Servicio | Para qué | Qué se obtiene |
| --- | --- | --- |
| GitHub (organización) | Código, CI/CD, imágenes | Organización y repositorio privado |
| Vultr | Servidor en Ciudad de México | Token de API (`VULTR_API_KEY`) |
| Cloudflare | DNS del dominio | Token con permiso *Zone · DNS · Edit* (`CLOUDFLARE_API_TOKEN`) y el *Zone ID* |
| Tailscale | SSH sin abrir el puerto 22 | Etiquetas `tag:servidor` y `tag:ci`, clave de autorización y cliente OAuth |
| Backblaze B2 o Cloudflare R2 | Estado de Terraform (y respaldos en H7) | Bucket `constructora-tfstate` y llaves S3 |

Las llaves quedan en el gestor de contraseñas de la empresa, con acceso para al menos una persona
además del desarrollador.

**Protección de `main` en un repositorio privado** requiere el plan GitHub Team (≈ USD 4 por
usuario al mes). Con el plan gratuito las reglas de `configurar-repo.sh` no se aplican.

## 2. Repositorio

```bash
gh repo create ORGANIZACION/constructora --private --source . --push
tools/github/configurar-repo.sh ORGANIZACION/constructora usuario-responsable
git commit -am "chore: CODEOWNERS con el responsable técnico" && git push
```

En Tailscale, política de acceso (ACL): `tag:ci` puede entrar por SSH a `tag:servidor`.

```json
{
  "tagOwners": { "tag:servidor": ["autogroup:admin"], "tag:ci": ["autogroup:admin"] },
  "acls": [{ "action": "accept", "src": ["tag:ci", "autogroup:admin"], "dst": ["tag:servidor:22"] }]
}
```

## 3. Servidor con Terraform

```bash
ssh-keygen -t ed25519 -f ~/.ssh/constructora-deploy -C deploy@constructora   # sin frase
cd infra/terraform
cp estado.s3.tfbackend.ejemplo estado.s3.tfbackend        # bucket y endpoint reales
cd preproduccion
cp terraform.tfvars.ejemplo terraform.tfvars              # llave pública, zona, dominio
export VULTR_API_KEY=… CLOUDFLARE_API_TOKEN=… AWS_ACCESS_KEY_ID=… AWS_SECRET_ACCESS_KEY=…
export TF_VAR_tailscale_clave=tskey-auth-…                 # de un solo uso, con tag:servidor
terraform init -backend-config=../estado.s3.tfbackend
terraform plan -out plan && terraform apply plan
```

Antes del primer `apply`, confirmar con `vultr-cli os list` que `2284` sigue siendo Ubuntu 24.04 y
con `vultr-cli regions list` que `mex` ofrece disco de bloque; si no, `disco_datos_gb = 0`.
Sube `.terraform.lock.hcl` al repositorio en el mismo PR.

cloud-init tarda unos 5 minutos. El servidor aparece en Tailscale como `constructora-preprod`.

## 4. Secretos en el servidor

```bash
ssh deploy@constructora-preprod
cd /opt/constructora
for s in pg_postgres_password pg_migrador_password pg_app_password; do
  openssl rand -base64 32 | tr -d '/+=' > secrets/$s
done
chmod 600 secrets/*
```

Crea `/opt/constructora/.env` desde `infra/compose/.env.ejemplo` (permisos 600):
`DB_CONTRASENA` = contenido de `secrets/pg_app_password`, `DB_MIGRADOR_CONTRASENA` = el de
`secrets/pg_migrador_password`, `IMAGEN=ghcr.io/organizacion/constructora` (en minúsculas) y
una contraseña inicial de al menos 10 caracteres en `APP_INICIAL_CONTRASENA`.

## 5. Variables y secretos del entorno en GitHub

*Settings › Environments › preproduccion*:

| Nombre | Tipo | Valor |
| --- | --- | --- |
| `PREPROD_HOST` | Variable | `constructora-preprod` (nombre en Tailscale) |
| `PREPROD_URL` | Variable | `https://preprod.constructora.mx` |
| `PREPROD_SSH_KEY` | Secreto | Contenido de `~/.ssh/constructora-deploy` (privada) |
| `TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET` | Secretos | Cliente OAuth de Tailscale con permiso de crear nodos `tag:ci` |

## 6. Primer despliegue y demostración de H1

1. *Actions › release › Run workflow* (o fusionar cualquier PR).
2. Abrir `https://preprod.constructora.mx`: debe mostrar el login con candado y la versión abajo.
3. Entrar como `admin`, dar de alta un usuario almacenista, salir y entrar como él: no ve *Usuarios*.
4. Borrar las líneas `APP_INICIAL_*` del `.env`.
5. Demo del criterio de H1: un PR que cambie un texto (por ejemplo, el subtítulo del login);
   medir desde la fusión hasta verlo en preproducción. Debe ser menos de 15 minutos.

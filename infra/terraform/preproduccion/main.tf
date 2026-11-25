# Preproducción: recibe sola cada versión fusionada a main (release.yml).
#   cd infra/terraform/preproduccion
#   terraform init -backend-config=../estado.s3.tfbackend
#   terraform plan -out plan && terraform apply plan

terraform {
  required_version = ">= 1.10"

  required_providers {
    vultr      = { source = "vultr/vultr", version = "~> 2.32" }
    cloudflare = { source = "cloudflare/cloudflare", version = "~> 5.0" }
  }

  # El estado nunca en la laptop: bucket S3 compatible en otro proveedor (Backblaze B2 o R2).
  # Credenciales en AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY; endpoint y bucket en el archivo
  # -backend-config (ver ../estado.s3.tfbackend.ejemplo).
  backend "s3" {
    key = "preproduccion/terraform.tfstate"
  }
}

# Token en VULTR_API_KEY y CLOUDFLARE_API_TOKEN; nunca en el repositorio.
provider "vultr" {
  rate_limit  = 700
  retry_limit = 3
}

provider "cloudflare" {}

module "servidor" {
  source = "../modulos/servidor"

  nombre             = "constructora-preprod"
  entorno            = "preproduccion"
  region             = var.region
  plan               = "vc2-2c-4gb"
  disco_datos_gb     = var.disco_datos_gb
  llave_ssh_publica  = var.llave_ssh_publica
  tailscale_clave    = var.tailscale_clave
  cloudflare_zona_id = var.cloudflare_zona_id
  dominio            = var.dominio
}

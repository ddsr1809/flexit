# Producción: recibe solo versiones publicadas como release por alguien de APROBADORES
# (deploy-prod.yml), con la misma imagen que ya pasó por preproducción.
#   cd infra/terraform/produccion
#   terraform init -backend-config=../estado.s3.tfbackend
#   terraform plan -out plan && terraform apply plan

terraform {
  required_version = ">= 1.10"

  required_providers {
    vultr      = { source = "vultr/vultr", version = "~> 2.32" }
    cloudflare = { source = "cloudflare/cloudflare", version = "~> 5.0" }
  }

  # Mismo bucket que preproducción, otra llave: un estado por entorno.
  backend "s3" {
    key = "produccion/terraform.tfstate"
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

  nombre             = "constructora-prod"
  entorno            = "produccion"
  region             = var.region
  plan               = var.plan
  disco_datos_gb     = var.disco_datos_gb
  llave_ssh_publica  = var.llave_ssh_publica
  tailscale_clave    = var.tailscale_clave
  cloudflare_zona_id = var.cloudflare_zona_id
  dominio            = var.dominio
}

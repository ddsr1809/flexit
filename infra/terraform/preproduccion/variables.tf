variable "region" {
  type    = string
  default = "mex"
}

variable "disco_datos_gb" {
  description = "Disco de bloque para PostgreSQL. Si la región no ofrece disco de bloque, usar 0"
  type        = number
  default     = 40
}

variable "llave_ssh_publica" {
  type = string
}

variable "tailscale_clave" {
  type      = string
  sensitive = true
}

variable "cloudflare_zona_id" {
  type = string
}

variable "dominio" {
  type    = string
  default = "preprod.constructora.mx"
}

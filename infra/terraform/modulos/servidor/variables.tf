variable "nombre" {
  description = "Nombre del servidor y prefijo de sus recursos, p. ej. constructora-preprod"
  type        = string
}

variable "entorno" {
  description = "preproduccion o produccion"
  type        = string
  validation {
    condition     = contains(["preproduccion", "produccion"], var.entorno)
    error_message = "entorno debe ser preproduccion o produccion."
  }
}

variable "region" {
  description = "Región de Vultr. mex = Ciudad de México"
  type        = string
  default     = "mex"
}

variable "plan" {
  description = "Plan de Vultr (vultr-cli plans list). vc2-2c-4gb para preproducción, vc2-4c-8gb para producción"
  type        = string
}

variable "os_id" {
  description = "Sistema operativo (vultr-cli os list). 2284 = Ubuntu 24.04 LTS x64; confirmar antes del primer apply"
  type        = number
  default     = 2284
}

variable "disco_datos_gb" {
  description = "Tamaño del disco de bloque para PostgreSQL; 0 para no crear disco aparte"
  type        = number
  default     = 40
}

variable "llave_ssh_publica" {
  description = "Llave pública del usuario deploy (la usa GitHub Actions por Tailscale)"
  type        = string
}

variable "tailscale_clave" {
  description = "Clave de autorización de Tailscale, de un solo uso y con la etiqueta tag:servidor"
  type        = string
  sensitive   = true
}

variable "cloudflare_zona_id" {
  description = "Zona DNS del dominio en Cloudflare"
  type        = string
}

variable "dominio" {
  description = "Nombre completo, p. ej. preprod.constructora.mx"
  type        = string
}

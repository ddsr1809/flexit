variable "region" {
  type    = string
  default = "mex"
}

variable "plan" {
  description = "4 vCPU y 8 GB (plan técnico, «Tamaño de los servidores»)"
  type        = string
  default     = "vc2-4c-8gb"
}

variable "disco_datos_gb" {
  description = "Disco de bloque para PostgreSQL. En producción no se acepta 0: los datos no viven en el disco del servidor"
  type        = number
  default     = 80
  validation {
    condition     = var.disco_datos_gb >= 40
    error_message = "Producción necesita disco de datos aparte (40 GB o más)."
  }
}

variable "llave_ssh_publica" {
  description = "Llave pública del usuario deploy de producción (distinta de la de preproducción)"
  type        = string
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
  default = "sistema.constructora.mx"
}

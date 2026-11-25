# Un servidor de la app: instancia, firewall, disco de datos y registro DNS.
# Lo usan preproduccion/ (H1) y produccion/ (H7) con distinto tamaño.

terraform {
  required_providers {
    vultr      = { source = "vultr/vultr", version = "~> 2.32" }
    cloudflare = { source = "cloudflare/cloudflare", version = "~> 5.0" }
  }
}

resource "vultr_ssh_key" "acceso" {
  name    = "${var.nombre}-acceso"
  ssh_key = var.llave_ssh_publica
}

# Solo 80 y 443 abiertos al mundo. SSH no se abre: se entra por Tailscale.
resource "vultr_firewall_group" "app" {
  description = "${var.nombre} · solo HTTP y HTTPS"
}

locals {
  reglas = {
    http-v4  = { protocolo = "tcp", ip = "v4", red = "0.0.0.0", puerto = "80" }
    https-v4 = { protocolo = "tcp", ip = "v4", red = "0.0.0.0", puerto = "443" }
    h3-v4    = { protocolo = "udp", ip = "v4", red = "0.0.0.0", puerto = "443" }
    http-v6  = { protocolo = "tcp", ip = "v6", red = "::", puerto = "80" }
    https-v6 = { protocolo = "tcp", ip = "v6", red = "::", puerto = "443" }
    h3-v6    = { protocolo = "udp", ip = "v6", red = "::", puerto = "443" }
  }
}

resource "vultr_firewall_rule" "app" {
  for_each          = local.reglas
  firewall_group_id = vultr_firewall_group.app.id
  protocol          = each.value.protocolo
  ip_type           = each.value.ip
  subnet            = each.value.red
  subnet_size       = 0
  port              = each.value.puerto
  notes             = each.key
}

resource "vultr_instance" "app" {
  label             = var.nombre
  hostname          = var.nombre
  region            = var.region
  plan              = var.plan
  os_id             = var.os_id
  enable_ipv6       = true
  backups           = "disabled" # los respaldos van cifrados a otro proveedor (restic, H7)
  activation_email  = false
  ddos_protection   = false
  ssh_key_ids       = [vultr_ssh_key.acceso.id]
  firewall_group_id = vultr_firewall_group.app.id
  tags              = ["constructora", var.entorno]

  user_data = templatefile("${path.module}/cloud-init.yml.tftpl", {
    nombre          = var.nombre
    llave_ssh       = var.llave_ssh_publica
    tailscale_clave = var.tailscale_clave
    con_disco_datos = var.disco_datos_gb > 0
    zona_horaria    = "America/Mexico_City"
  })

  lifecycle {
    # Cambiar cloud-init no debe recrear un servidor que ya corre; se aplica a mano o al recrearlo.
    ignore_changes = [user_data]
  }
}

# Los datos de PostgreSQL viven en un disco aparte: el servidor se puede destruir y recrear
# sin perderlos. Con disco_datos_gb = 0 se usa el disco del servidor (solo para pruebas).
resource "vultr_block_storage" "datos" {
  count                = var.disco_datos_gb > 0 ? 1 : 0
  label                = "${var.nombre}-datos"
  region               = var.region
  size_gb              = var.disco_datos_gb
  block_type           = "high_perf"
  attached_to_instance = vultr_instance.app.id
  live                 = true

  lifecycle {
    prevent_destroy = true
  }
}

resource "cloudflare_dns_record" "app_v4" {
  zone_id = var.cloudflare_zona_id
  name    = var.dominio
  type    = "A"
  content = vultr_instance.app.main_ip
  ttl     = 300
  proxied = false # solo DNS al principio: Caddy emite el certificado directamente
  comment = "constructora ${var.entorno} (Terraform)"
}

resource "cloudflare_dns_record" "app_v6" {
  zone_id = var.cloudflare_zona_id
  name    = var.dominio
  type    = "AAAA"
  content = vultr_instance.app.v6_main_ip
  ttl     = 300
  proxied = false
  comment = "constructora ${var.entorno} (Terraform)"
}

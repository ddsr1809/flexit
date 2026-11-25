output "ip" {
  description = "IP pública del servidor"
  value       = vultr_instance.app.main_ip
}

output "ipv6" {
  value = vultr_instance.app.v6_main_ip
}

output "url" {
  value = "https://${var.dominio}"
}

output "instancia_id" {
  value = vultr_instance.app.id
}

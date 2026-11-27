# Lista de verificación antes de producción

Anexo C de la arquitectura. Ninguno se negocia el día del arranque. Se firma al cerrar H7.

## Aplicación

- [ ] Sin secretos en el repositorio: el job *Secretos (gitleaks)* en verde.
- [ ] El contenedor no corre como root (`USER 10001` en el Dockerfile).
- [ ] *Readiness* y *liveness* responden: `curl https://sistema…/actuator/health/readiness`.
- [ ] Apagado limpio: `server.shutdown: graceful`.
- [ ] Logs JSON a la salida estándar (perfil `prod`, formato ECS).
- [ ] Segundo factor activo para administración y dirección (`segundo-factor.md`).

## Base de datos

- [ ] `READ COMMITTED`: `SHOW default_transaction_isolation;`.
- [ ] Existencia nunca negativa (`CHECK` y prueba de 50 salidas simultáneas de H4 en verde).
- [ ] Bitácora instalada en las tablas de negocio (`make sql`).
- [ ] El rol `app` sin `DROP` ni `GRANT`: `make sql` (pruebas de permisos).

## Infraestructura

- [ ] Servidor en Terraform con estado remoto (`infra/terraform/produccion`).
- [ ] Firewall: solo 80 y 443 al mundo; SSH solo por Tailscale.
- [ ] SSH solo por llave, sin contraseña; `root` sin acceso.
- [ ] Actualizaciones de seguridad desatendidas activas: `systemctl status unattended-upgrades`.
- [ ] TLS con renovación automática (Caddy) y aviso de vencimiento en Uptime Kuma.

## Respaldos

- [ ] Automáticos y cifrados: `systemctl list-timers constructora-respaldo.timer`.
- [ ] Destino externo al servidor (B2), con versiones previas por 30 días.
- [ ] **Restauración probada**: una línea `correcta` en `restauraciones.log` y una restauración en
      sitio de menos de 2 horas (ensayo general).
- [ ] Alerta si el respaldo no se ejecuta (monitor push de 26 h).

## Operación

- [ ] Uptime Kuma avisa a Telegram al apagar la app a propósito (`monitoreo.md`).
- [ ] Reversión escrita y ensayada en menos de 2 minutos (`revertir.md`).
- [ ] Una persona además del desarrollador con acceso de emergencia probado
      (`acceso-emergencia.md`).

Firma: responsable técnico ______________  dirección ______________  fecha ________

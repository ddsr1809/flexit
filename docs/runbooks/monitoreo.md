# Monitoreo (H7 · issue 40)

**Uptime Kuma corre en preproducción y vigila producción desde otro servidor**: avisa aunque se caiga
el servidor de producción completo (ADR-013). No está publicado en internet; se entra por
Tailscale. Los avisos llegan por Telegram a un grupo que alguien lee: el responsable técnico y
una persona de administración.

## Instalación (una vez)

`release.yml` ya deja Uptime Kuma corriendo en `/opt/monitoreo` del servidor de preproducción
(`infra/monitoreo/compose.yml`, escucha solo en `127.0.0.1:3001`).

1. En la consola de Tailscale: *DNS › MagicDNS* y *HTTPS Certificates* activados. En la política de
   acceso, permitir el puerto 443 de los servidores a los servidores y a administración:

   ```json
   { "action": "accept", "src": ["tag:servidor", "autogroup:admin"], "dst": ["tag:servidor:443"] }
   ```

2. En preproducción, publicarlo dentro de la red privada:

   ```bash
   ssh deploy@constructora-preprod
   sudo tailscale serve --bg 3001
   tailscale serve status          # https://constructora-preprod.<tailnet>.ts.net
   ```

3. Abrir esa dirección desde una computadora en Tailscale, crear el usuario administrador de Uptime
   Kuma (contraseña al gestor de contraseñas) y configurar la notificación de Telegram: un bot de
   @BotFather, su token y el id del grupo. Marcarla como *Default enabled* y *Apply on all existing
   monitors*.

## Monitores

| Nombre | Tipo | Configuración | Avisa cuando |
| --- | --- | --- | --- |
| Producción · app | HTTP(s) | `https://sistema.constructora.mx/actuator/health/readiness`, cada 60 s, 2 reintentos, *Certificate Expiry Notification* activado | La app no responde o no está lista; el certificado vence en menos de 14 días |
| Producción · servidor | Push | Intervalo 600 s. Copiar su URL a `KUMA_VIGILAR` | `vigilar.sh` reporta disco > 85%, memoria < 300 MB, un contenedor detenido o no sano, el último respaldo de hace más de 26 h, la última prueba de restauración fallida o de hace más de 36 días; o el servidor deja de mandar el latido |
| Producción · respaldo diario | Push | Intervalo 93,600 s (26 h). URL a `KUMA_RESPALDO` | Un respaldo falla, o no llega el diario |
| Preproducción · app | HTTP(s) | `https://preprod.constructora.mx/actuator/health/readiness`, cada 300 s | Preproducción no responde |

Las URL de los monitores push van en `/opt/constructora/respaldo.env` de producción (ver
`infra/compose/respaldo.env.ejemplo`). Los timers leen ese archivo en cada corrida: no hay que
reiniciar nada.

Uptime Kuma no se respalda: si se pierde, se reinstala con esta tabla en 15 minutos.

## Demostración (criterio de H7)

```bash
ssh deploy@constructora-prod
cd /opt/constructora
VERSION=$(cat .version) docker compose stop app      # en 1 a 3 minutos llega el aviso a Telegram
VERSION=$(cat .version) docker compose start app     # y después el de recuperación
```

Avisar al almacén antes: durante la prueba la app no responde.

## Qué hacer con cada aviso

| Aviso | Primero |
| --- | --- |
| Producción · app caída | `incidente.md` |
| Disco al 85% | `df -h`, `docker system df`; si es `/mnt/datos`, ampliar `disco_datos_gb` en Terraform |
| Contenedor detenido o no sano | `docker compose ps`, `docker compose logs --tail 100 <servicio>` |
| Respaldo falló o es viejo | `respaldos.md`, "Si falla" |
| Prueba de restauración falló | `tail -50 operacion.log`; correr `./restaurar.sh prueba` a mano y abrir un issue |
| Certificado por vencer | `docker compose logs caddy | grep -i acme`; Caddy lo renueva solo, el aviso indica que algo lo impide (DNS, puerto 80) |

Las métricas (Prometheus y Grafana) y Sentry entran en la etapa 2 de observabilidad, cuando haya
preguntas de rendimiento o errores que nadie reporte (plan técnico, "Observabilidad").

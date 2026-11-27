# Servidor perdido

El servidor de producción no responde y no se recupera desde el panel de Vultr (o se borró, o se
comprometió). Objetivo: menos de 2 horas. Mientras tanto, el almacén captura en vales de papel
(`incidente.md`).

## 1. Decidir si el disco de datos sirve

Los datos de PostgreSQL viven en un disco de bloque aparte (`constructora-prod-datos`, con
`prevent_destroy`). Si el problema es el servidor y no el disco, se recrea el servidor y el disco se
vuelve a montar con los datos intactos: es el camino más rápido y no pierde nada. Si el disco se
perdió o se comprometió, hay que restaurar desde restic (paso 4).

## 2. Recrear el servidor

```bash
cd infra/terraform/produccion
export VULTR_API_KEY=… CLOUDFLARE_API_TOKEN=… AWS_ACCESS_KEY_ID=… AWS_SECRET_ACCESS_KEY=…
export TF_VAR_tailscale_clave=tskey-auth-…          # una nueva, de un solo uso
terraform apply -replace=module.servidor.vultr_instance.app
```

El registro DNS apunta solo a la IP nueva. Si el servidor se comprometió, primero quitarle el
disco en el panel de Vultr y crear uno nuevo (`terraform apply` con otro `label`).

## 3. Configuración

Sale del último respaldo (va cifrada en restic), desde la laptop del responsable técnico:

```bash
export RESTIC_REPOSITORY=s3:https://…/constructora-respaldos/produccion
export AWS_ACCESS_KEY_ID=… AWS_SECRET_ACCESS_KEY=…     # del gestor de contraseñas
export RESTIC_PASSWORD=…                               # la llave de restic, del gestor
restic restore latest --host constructora-prod --include '/mnt/datos/respaldos/actual/configuracion.tar.gz' --target /tmp/rec
tar -tzf /tmp/rec/mnt/datos/respaldos/actual/configuracion.tar.gz
```

Copiar `.env`, `secrets/` y `respaldo.env` a `/opt/constructora/` del servidor nuevo, más
`secrets/restic_password`, y `chmod 600`. Borrar `/tmp/rec`.

## 4. App y datos

1. *Actions › deploy-prod › Run workflow* con el SHA de la última versión de producción
   (`despliegues.log` del respaldo, o la etiqueta `produccion` en GHCR). Con el disco de datos
   intacto, aquí termina.
2. Sin disco de datos: el primer despliegue arranca con una base vacía. Restaurar:

   ```bash
   ssh deploy@constructora-prod
   cd /opt/constructora && ./restaurar.sh listar
   ./restaurar.sh en-sitio <id> --confirmo --sin-respaldo-previo
   ```

3. Uptime Kuma: el monitor de producción vuelve a verde; el latido, cuando corra el primer
   `vigilar.sh` (5 minutos).

## 5. Después

- Rotar las llaves que vivían en el servidor perdido (`rotar-secretos.md`), sobre todo si se
  comprometió.
- Correr `./restaurar.sh prueba` y `./respaldo.sh` para confirmar que la operación quedó completa.
- Escribir qué pasó en un issue con la etiqueta `incidente`.

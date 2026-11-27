# Respaldos

Los respaldos no maduran por etapas: desde el primer día de producción van completos, cifrados,
fuera del servidor y probados cada mes.

## Qué se respalda y cuándo

| Cuándo | Etiqueta | Quién lo dispara |
| --- | --- | --- |
| Todos los días, 02:30 (Ciudad de México) | `diario` | timer `constructora-respaldo` |
| Antes de cada despliegue a producción | `antes-de-desplegar` | `deploy-prod.yml` |
| Antes de restaurar en sitio | `antes-de-restaurar` | `restaurar.sh en-sitio` |
| Antes del ensayo general y del corte | `ensayo`, `corte` | a mano: `./respaldo.sh ensayo` |

Cada respaldo (`infra/scripts/respaldo.sh`) lleva:

- el volcado de PostgreSQL (`pg_dump -Fc`), roles sin contraseñas y la versión de la app;
- el **conteo exacto de renglones de cada tabla, tomado en la misma instantánea que el volcado**:
  la restauración se da por buena solo si el conteo de la base restaurada es idéntico;
- la configuración del servidor (`.env`, `secrets/`, `respaldo.env`), cifrada por restic, para
  recrearlo (`servidor-perdido.md`). La llave de restic no va: vive en el gestor de contraseñas.

restic cifra del lado del servidor antes de subir; el bucket de B2 solo ve bloques cifrados.

## Retención

Todo lo de los últimos 30 días; de ahí para atrás, uno por día hasta 30, uno por semana hasta 12 y
uno por mes hasta 24. Cada etiqueta se cuenta aparte, así que un respaldo `antes-de-desplegar` no
desplaza al diario. Para conservar uno para siempre (el del corte, por ejemplo):

```bash
restic tag --add conservar <id>        # con las variables de respaldo.env cargadas
```

Los domingos, además, se libera espacio (`prune`) y se revisa al azar el 5% de los datos del
repositorio (`check --read-data-subset`).

## Revisar

```bash
cd /opt/constructora
tail operacion.log                                   # cada respaldo con id, tamaño, renglones y tiempo
set -a; . ./respaldo.env; set +a
restic snapshots --host constructora-prod --compact  # o ./restaurar.sh listar
systemctl list-timers 'constructora-*'
```

Uptime Kuma avisa si el respaldo diario falla o no llega en 26 horas (`monitoreo.md`).

## Respaldo a mano

```bash
/opt/constructora/respaldo.sh ensayo        # imprime el id del respaldo al final
```

## Si falla

| Mensaje en operacion.log | Qué hacer |
| --- | --- |
| `Menos de 2 GB libres` | Revisar `df -h /mnt/datos`; ampliar el disco de bloque en Terraform (`disco_datos_gb`) |
| `restic … unable to open repository` / `wrong password` | Revisar `respaldo.env` y `secrets/restic_password` contra el gestor de contraseñas |
| `pg_dump falló` | `docker compose ps db`; si la base está caída, `incidente.md` |
| `The specified bucket does not exist` / 403 | La llave de B2 venció o perdió permisos: crear otra (`rotar-secretos.md`) |

# ADR-013 · Producción, respaldos, monitoreo y segundo factor

**Contexto.** H7 tiene que dejar producción operando antes del corte del piloto (H8): servidor,
despliegue aprobado por una persona, respaldos que se restauran de verdad, avisos cuando algo
falla, acceso de emergencia y un ensayo general de la migración. En H1 quedó pendiente el segundo
factor para administración y dirección, y el plan pedía restaurar el respaldo de producción en
preproducción cada mes.

**Decisión.**

- **Producción con el mismo módulo de Terraform** que preproducción (`infra/terraform/produccion`):
  4 vCPU, 8 GB, disco de datos de 80 GB aparte (`prevent_destroy`), llave SSH propia y estado
  propio en el mismo bucket. cloud-init instala `restic`; los timers de operación los instala
  `instalar-operacion.sh`, que viaja con cada despliegue, así un servidor ya creado también los
  recibe sin recrearlo.
- **Despliegue por release.** `deploy-prod.yml` corre al publicar un release `vX.Y.Z` sobre `main` y
  solo si quien lo publica está en la variable `APROBADORES`. Solo despliega imágenes que
  `release.yml` etiquetó `preproduccion-<sha>` después de quedar sanas en preproducción. Antes de
  migrar saca un respaldo `antes-de-desplegar`. La reversión rápida sigue siendo `revertir.sh`.
- **Respaldos con restic a Backblaze B2.** Volcado `pg_dump -Fc`, roles, versión y la configuración
  del servidor, cifrados por restic. El conteo exacto de renglones de cada tabla se toma **dentro
  de la misma instantánea** que el volcado (`pg_export_snapshot` + `pg_dump --snapshot`), así que
  la restauración se puede verificar al renglón aunque la app siga escribiendo. Retención por
  etiqueta (30 días completos; 30 diarios, 12 semanales, 24 mensuales) para que un respaldo
  `antes-de-desplegar` no desplace al diario. El bucket conserva versiones borradas 30 días.
- **Prueba mensual de restauración en una base temporal del propio servidor de producción**, no en
  preproducción como decía el plan: preproducción quedaría con los datos personales del SUA
  (nombres, NSS) hasta la siguiente prueba. La base temporal vive en una red de Docker sin salida,
  se compara el conteo, se aplican las migraciones de la versión actual, se arranca la app contra
  ella y se borra. Corre con un timer de systemd y no con un workflow programado, para no dejar la
  llave SSH de producción al alcance de un workflow sin aprobación.
- **Uptime Kuma en preproducción**, vigilando producción desde otro servidor: avisa aunque se caiga
  el servidor de producción completo. No se publica en internet (escucha en `127.0.0.1`; se entra
  con `tailscale serve`). Producción le manda un latido cada 5 minutos (`vigilar.sh`: disco,
  memoria, contenedores, edad del respaldo y de la última prueba de restauración) y el resultado
  de cada respaldo. Sentry, Prometheus y Grafana quedan para la etapa 2 de observabilidad.
- **Fotos y evidencias siguen en la base** (`compartido.archivo`), no en S3: pesan poco, entran en
  el mismo respaldo y no agregan otra pieza que operar. Se revisa al llegar la app de campo o si la
  base pasa de 5 GB.
- **Segundo factor TOTP (RFC 6238)** para los roles `admin` y `direccion` cuando
  `APP_SEGUNDO_FACTOR_EXIGIR=true` (producción y preproducción). Login en dos pasos: con la
  contraseña correcta la sesión queda *pendiente* 5 minutos sin autenticar (202), y el código la
  completa. El secreto se guarda cifrado con AES-256-GCM y una llave del entorno
  (`APP_LLAVE_SEGUNDO_FACTOR`), con el id del usuario como dato autenticado; un código no se reusa
  (`ultimo_paso`). Sin llave válida la app no arranca. Restablece otra persona de administración;
  nadie el suyo. V010 cierra todas las sesiones abiertas.
- **Ensayo general con una herramienta de línea de comandos** (`tools/ensayo/ensayo.py`, solo
  biblioteca estándar) que usa la API: exporta de preproducción las decisiones de la cuarentena
  **por nombre** (endpoint nuevo `GET /importaciones/destinos/{entidad}/{id}`), las repite en
  producción solo en renglones idénticos (huella del contenido) y se detiene en la primera carga
  que no concilia. El borrado es restaurar el respaldo `ensayo`, que deja producción exactamente
  como estaba y demuestra la restauración.

**Consecuencias.**

- Nada llega a producción sin pasar por preproducción y sin una persona con nombre que lo apruebe.
- Un respaldo se considera bueno solo si se restauró y contó igual; la prueba corre sola cada mes.
- Las llaves de restic y del segundo factor son críticas: viven en el gestor de contraseñas, en la
  bóveda de emergencia (`acceso-emergencia.md`).
- Preproducción ahora corre algo que importa a producción (Uptime Kuma): si se recrea, se
  reinstala con `monitoreo.md`.
- El ensayo depende de que los Excel no cambien entre preproducción y producción; si cambian, la
  herramienta lo detecta y deja esos renglones a administración.

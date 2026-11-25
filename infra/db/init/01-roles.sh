#!/usr/bin/env bash
# Crea los roles de la base. Lo ejecuta la imagen oficial de PostgreSQL una sola vez, al
# inicializar un volumen vacío (/docker-entrypoint-initdb.d/).
#
#   migrador  dueño de los esquemas; corre Flyway; BYPASSRLS para migraciones de datos.
#   app       con el que se conecta la aplicación; sin DDL, sujeto a RLS.
#
# Las contraseñas llegan como secretos de Docker, nunca como variables en el repositorio.
set -euo pipefail

leer_secreto() {
  local archivo=$1
  [[ -s "$archivo" ]] || { echo "Falta el secreto $archivo" >&2; exit 1; }
  tr -d '\r\n' < "$archivo"
}

MIGRADOR_PASS=$(leer_secreto "${PG_MIGRADOR_PASSWORD_FILE:-/run/secrets/pg_migrador_password}")
APP_PASS=$(leer_secreto "${PG_APP_PASSWORD_FILE:-/run/secrets/pg_app_password}")

psql -v ON_ERROR_STOP=1 \
     --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
     -v migrador_pass="${MIGRADOR_PASS}" -v app_pass="${APP_PASS}" -v base="${POSTGRES_DB}" <<'SQL'
CREATE ROLE migrador LOGIN BYPASSRLS PASSWORD :'migrador_pass';
CREATE ROLE app LOGIN PASSWORD :'app_pass' CONNECTION LIMIT 40;
ALTER DATABASE :"base" OWNER TO migrador;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
-- La app lee la tabla de historial de Flyway para validar que la base está al día al arrancar.
ALTER DEFAULT PRIVILEGES FOR ROLE migrador IN SCHEMA public GRANT SELECT ON TABLES TO app;
SQL

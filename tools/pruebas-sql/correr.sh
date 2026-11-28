#!/usr/bin/env bash
# Crea una base desechable, crea los roles, aplica las migraciones como migrador y corre
# seguridad.sql, estructura-catalogo.sql, migracion.sql, inventario.sql, historico.sql,
# abastecimiento.sql, apertura.sql y estabilizacion.sql. Sirve sin Docker, contra cualquier
# PostgreSQL 16 o superior.
#
#   PGHOST=localhost PGPORT=5432 PGUSER=postgres tools/pruebas-sql/correr.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

BASE=constructora_pruebas_$$
TMP=$(mktemp -d)
trap 'dropdb --if-exists "$BASE" >/dev/null 2>&1 || true; rm -rf "$TMP"' EXIT

# Los roles son de todo el clúster: se crean solo si no existen.
if ! psql -tAc "SELECT 1 FROM pg_roles WHERE rolname = 'migrador'" | grep -q 1; then
  echo "migrador-local" > "$TMP/m"; echo "app-local" > "$TMP/a"
  createdb "$BASE"
  POSTGRES_USER="${PGUSER:-postgres}" POSTGRES_DB="$BASE" \
    PG_MIGRADOR_PASSWORD_FILE="$TMP/m" PG_APP_PASSWORD_FILE="$TMP/a" \
    bash infra/db/init/01-roles.sh >/dev/null
else
  createdb -O migrador "$BASE"
  psql -q -d "$BASE" -c "REVOKE CREATE ON SCHEMA public FROM PUBLIC" \
       -c "ALTER DEFAULT PRIVILEGES FOR ROLE migrador IN SCHEMA public GRANT SELECT ON TABLES TO app"
fi

for f in backend/src/main/resources/db/migration/V*.sql; do
  psql -q -v ON_ERROR_STOP=1 -d "$BASE" -1 -c "SET ROLE migrador" -f "$f"
done
# El orden importa: cada archivo usa las empresas que crea seguridad.sql.
for prueba in seguridad estructura-catalogo migracion inventario historico abastecimiento apertura estabilizacion; do
  psql -q -v ON_ERROR_STOP=1 -d "$BASE" -f "tools/pruebas-sql/$prueba.sql" 2>&1 \
    | sed 's/^psql:[^ ]* NOTICE:  /  /'
done
echo "Pruebas SQL en verde."

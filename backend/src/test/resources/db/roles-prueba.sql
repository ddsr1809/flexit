-- Roles para las pruebas con Testcontainers. Equivale a infra/db/init/01-roles.sh con
-- contraseñas fijas de prueba (el contenedor es efímero y solo escucha en la máquina de CI).
CREATE ROLE migrador LOGIN BYPASSRLS PASSWORD 'migrador-prueba';
CREATE ROLE app LOGIN PASSWORD 'app-prueba';
ALTER DATABASE constructora OWNER TO migrador;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE migrador IN SCHEMA public GRANT SELECT ON TABLES TO app;

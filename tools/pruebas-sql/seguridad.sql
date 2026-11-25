-- Pruebas de las defensas que viven en la base: RLS por empresa, permisos del rol app,
-- bitácora y folios. Se corren con tools/pruebas-sql/correr.sh contra una base recién migrada.
-- Cada bloque lanza una excepción si la regla no se cumple; la salida limpia es el éxito.
\set ON_ERROR_STOP on
\set QUIET on

-- ============ Datos: dos empresas con un usuario cada una (como migrador) ============
SET ROLE migrador;
INSERT INTO organizacion.empresa (nombre, rfc) VALUES ('Empresa A', 'AAA010101AA1'), ('Empresa B', 'BBB010101BB1');
INSERT INTO organizacion.usuario (empresa_id, usuario, nombre, hash_contrasena)
SELECT id, lower(right(nombre, 1)) || '.admin', 'Admin ' || right(nombre, 1), '{noop}x'
  FROM organizacion.empresa;
INSERT INTO organizacion.usuario_rol (empresa_id, usuario_id, rol)
SELECT empresa_id, id, 'admin' FROM organizacion.usuario;
RESET ROLE;

-- ============ Como app, sin contexto: no ve nada ============
SET ROLE app;
DO $$ BEGIN
  IF (SELECT count(*) FROM organizacion.usuario) <> 0 THEN RAISE 'sin contexto se ven usuarios'; END IF;
  IF (SELECT count(*) FROM organizacion.empresa) <> 0 THEN RAISE 'sin contexto se ven empresas'; END IF;
  RAISE NOTICE 'ok · sin empresa en el contexto no se ve ninguna fila';
END $$;

-- ============ Como app en la empresa A ============
RESET ROLE;
SELECT set_config('app.empresa_id', (SELECT id::text FROM organizacion.empresa WHERE rfc = 'AAA010101AA1'), false) \gset
SELECT set_config('app.usuario_id', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'a.admin'), false) \gset
SELECT id AS empresa_b FROM organizacion.empresa WHERE rfc = 'BBB010101BB1' \gset
SET ROLE app;

DO $$ BEGIN
  IF (SELECT count(*) FROM organizacion.usuario) <> 1 THEN RAISE 'A debería ver 1 usuario'; END IF;
  IF EXISTS (SELECT 1 FROM organizacion.usuario WHERE usuario = 'b.admin') THEN RAISE 'A ve usuarios de B'; END IF;
  IF (SELECT count(*) FROM organizacion.empresa) <> 1 THEN RAISE 'A debería ver solo su empresa'; END IF;
  RAISE NOTICE 'ok · la empresa A solo ve sus filas';
END $$;

-- Insertar un usuario con empresa_id de B estando en A: lo rechaza la política
DO $$ BEGIN
  INSERT INTO organizacion.usuario (empresa_id, usuario, nombre, hash_contrasena)
  VALUES (current_setting('app.empresa_id')::bigint + 1, 'intruso', 'Intruso', 'x');
  RAISE 'se insertó un usuario en otra empresa';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · RLS rechaza escribir en otra empresa (%)', SQLERRM;
END $$;

-- Actualizar filas de B: la política no las deja ver, así que no se toca ninguna
UPDATE organizacion.usuario SET nombre = 'hackeado' WHERE usuario = 'b.admin';
RESET ROLE;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM organizacion.usuario WHERE nombre = 'hackeado') THEN RAISE 'A modificó un usuario de B'; END IF;
  RAISE NOTICE 'ok · A no puede modificar filas de B';
END $$;
SET ROLE app;

-- Permisos: la app no borra usuarios, no toca la bitácora, no crea tablas
DO $$ BEGIN
  DELETE FROM organizacion.usuario;
  RAISE 'app pudo borrar usuarios';
EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'ok · app no puede borrar usuarios';
END $$;
DO $$ BEGIN
  UPDATE compartido.bitacora SET operacion = 'DELETE';
  RAISE 'app pudo modificar la bitácora';
EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'ok · la bitácora es de solo inserción';
END $$;
DO $$ BEGIN
  DELETE FROM compartido.bitacora;
  RAISE 'app pudo borrar la bitácora';
EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'ok · la bitácora no se puede borrar';
END $$;
DO $$ BEGIN
  CREATE TABLE public.atajo (x int);
  RAISE 'app pudo crear una tabla';
EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'ok · app no puede crear tablas';
END $$;
DO $$ BEGIN
  CREATE TABLE organizacion.atajo (x int);
  RAISE 'app pudo crear una tabla en organizacion';
EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'ok · app no puede crear tablas en los esquemas';
END $$;
DO $$ BEGIN
  INSERT INTO organizacion.empresa (nombre) VALUES ('Nueva');
  RAISE 'app pudo crear una empresa';
EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'ok · app no puede crear empresas';
END $$;

-- ============ Bitácora ============
INSERT INTO organizacion.usuario (empresa_id, usuario, nombre, hash_contrasena)
VALUES (current_setting('app.empresa_id')::bigint, 'almacen.uno', 'Almacenista Uno', '{noop}secreto');
UPDATE organizacion.usuario SET nombre = 'Almacenista 1' WHERE usuario = 'almacen.uno';
UPDATE organizacion.usuario SET intentos_fallidos = 3 WHERE usuario = 'almacen.uno';
INSERT INTO organizacion.usuario_rol (empresa_id, usuario_id, rol)
SELECT empresa_id, id, 'almacenista' FROM organizacion.usuario WHERE usuario = 'almacen.uno';

DO $$
DECLARE
  v_id bigint := (SELECT id FROM organizacion.usuario WHERE usuario = 'almacen.uno');
  v_n int;
BEGIN
  SELECT count(*) INTO v_n FROM compartido.bitacora WHERE tabla = 'usuario' AND registro_id = v_id;
  IF v_n <> 2 THEN RAISE 'se esperaban 2 renglones de bitácora (alta y cambio de nombre), hay %', v_n; END IF;
  IF EXISTS (SELECT 1 FROM compartido.bitacora WHERE despues ? 'hash_contrasena' OR antes ? 'hash_contrasena') THEN
    RAISE 'la bitácora guardó el hash de la contraseña';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM compartido.bitacora
                  WHERE tabla = 'usuario' AND registro_id = v_id AND operacion = 'UPDATE'
                    AND antes ->> 'nombre' = 'Almacenista Uno' AND despues ->> 'nombre' = 'Almacenista 1'
                    AND usuario_id = current_setting('app.usuario_id')::bigint) THEN
    RAISE 'el cambio de nombre no quedó con antes, después y usuario';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM compartido.bitacora WHERE tabla = 'usuario_rol' AND registro_id = v_id) THEN
    RAISE 'la asignación de rol no quedó en bitácora';
  END IF;
  RAISE NOTICE 'ok · bitácora con antes, después y usuario; sin contraseñas ni contadores';
END $$;

-- ============ Folios ============
BEGIN;
DO $$ BEGIN
  IF compartido.siguiente_folio('ENT', 2026::smallint) <> 1 THEN RAISE 'primer folio distinto de 1'; END IF;
  IF compartido.siguiente_folio('ENT', 2026::smallint) <> 2 THEN RAISE 'segundo folio distinto de 2'; END IF;
  IF compartido.siguiente_folio('SAL', 2026::smallint) <> 1 THEN RAISE 'otro tipo debe empezar en 1'; END IF;
  IF compartido.siguiente_folio('ENT', 2027::smallint) <> 1 THEN RAISE 'otro año debe empezar en 1'; END IF;
END $$;
COMMIT;
BEGIN;
SELECT compartido.siguiente_folio('ENT', 2026::smallint) AS descartado \gset
ROLLBACK;
DO $$ BEGIN
  IF compartido.siguiente_folio('ENT', 2026::smallint) <> 3 THEN RAISE 'el folio revertido dejó hueco'; END IF;
  RAISE NOTICE 'ok · folios por tipo y año, sin huecos al revertir';
END $$;

-- Folios de B no se mezclan con los de A
RESET ROLE;
SELECT set_config('app.empresa_id', :'empresa_b', false) \gset
SET ROLE app;
DO $$ BEGIN
  IF compartido.siguiente_folio('ENT', 2026::smallint) <> 1 THEN RAISE 'B arrancó con el folio de A'; END IF;
  IF (SELECT count(*) FROM compartido.folio) <> 1 THEN RAISE 'B ve folios de A'; END IF;
  RAISE NOTICE 'ok · cada empresa lleva sus propios folios';
END $$;

-- Sin empresa en el contexto no se puede pedir folio
RESET ROLE;
SELECT set_config('app.empresa_id', '', false) \gset
SET ROLE app;
DO $$ BEGIN
  PERFORM compartido.siguiente_folio('ENT', 2026::smallint);
  RAISE 'se generó un folio sin empresa';
EXCEPTION WHEN insufficient_privilege OR not_null_violation THEN RAISE NOTICE 'ok · sin empresa no hay folio';
END $$;

-- ============ Inicio de sesión ============
DO $$
DECLARE r record;
BEGIN
  SELECT * INTO r FROM organizacion.usuario_para_login('  A.Admin ');
  IF r.id IS NULL OR r.empresa <> 'Empresa A' OR r.roles <> ARRAY['admin'] THEN
    RAISE 'usuario_para_login no encontró al usuario sin contexto: %', r;
  END IF;
  FOR i IN 1..5 LOOP PERFORM organizacion.registrar_intento_login('a.admin', false); END LOOP;
  SELECT * INTO r FROM organizacion.usuario_para_login('a.admin');
  IF r.bloqueado_hasta IS NULL OR r.bloqueado_hasta < now() + interval '14 minutes' THEN
    RAISE 'cinco intentos fallidos no bloquearon la cuenta';
  END IF;
  PERFORM organizacion.registrar_intento_login('a.admin', true);
  SELECT * INTO r FROM organizacion.usuario_para_login('a.admin');
  IF r.bloqueado_hasta IS NOT NULL THEN RAISE 'el acceso correcto no limpió el bloqueo'; END IF;
  IF organizacion.inicializar('Otra', '', 'otro', 'Otro', 'x') THEN RAISE 'inicializar corrió con usuarios existentes'; END IF;
  RAISE NOTICE 'ok · login sin contexto, bloqueo a los 5 intentos e inicialización de un solo uso';
END $$;
RESET ROLE;

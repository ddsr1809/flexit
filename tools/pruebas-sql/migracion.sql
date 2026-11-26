-- Pruebas de las defensas de la migración y de personas (H3). Corre después de
-- estructura-catalogo.sql, sobre sus empresas A y B. Cada bloque lanza una excepción si la regla
-- no se cumple.
\set ON_ERROR_STOP on
\set QUIET on

RESET ROLE;
SELECT set_config('pruebas.empresa_a', (SELECT id::text FROM organizacion.empresa WHERE rfc = 'AAA010101AA1'), false) \gset
SELECT set_config('pruebas.empresa_b', (SELECT id::text FROM organizacion.empresa WHERE rfc = 'BBB010101BB1'), false) \gset
SELECT set_config('pruebas.usuario_a', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'a.admin'), false) \gset
SELECT set_config('pruebas.usuario_b', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'b.admin'), false) \gset

-- ============ Datos de B (como migrador): una carga, una persona y un contrato importado ============
SET ROLE migrador;
INSERT INTO migracion.archivo (empresa_id, sha256, nombre, tamano, contenido, usuario_id)
VALUES (current_setting('pruebas.empresa_b')::bigint, repeat('b', 64), 'sua.xlsx', 3, '\x010203',
        current_setting('pruebas.usuario_b')::bigint);
INSERT INTO migracion.carga (empresa_id, tipo, archivo, sha256, hojas, version_reglas, usuario_id)
VALUES (current_setting('pruebas.empresa_b')::bigint, 'PERSONAS_SUA', 'sua.xlsx', repeat('b', 64),
        'IMSS OCTUBRE', 'h3.1', current_setting('pruebas.usuario_b')::bigint);
SELECT set_config('pruebas.carga_b', (SELECT max(id)::text FROM migracion.carga), false) \gset
INSERT INTO organizacion.persona (empresa_id, nombre, origen_carga)
VALUES (current_setting('pruebas.empresa_b')::bigint, 'Persona de B', current_setting('pruebas.carga_b')::bigint);
INSERT INTO estructura.contrato (empresa_id, cliente_id, codigo, origen_carga)
SELECT empresa_id, id, '60-B-IMPORTADO', current_setting('pruebas.carga_b')::bigint
  FROM estructura.cliente WHERE nombre = 'Cliente de B';
RESET ROLE;

-- ============ Como app en la empresa A ============
SELECT set_config('app.empresa_id', current_setting('pruebas.empresa_a'), false) \gset
SELECT set_config('app.usuario_id', current_setting('pruebas.usuario_a'), false) \gset
SET ROLE app;

-- Personas: cada empresa ve las suyas; la app no las borra
INSERT INTO organizacion.persona (empresa_id, nombre)
VALUES (current_setting('app.empresa_id')::bigint, 'García Cruz Bladimir León');
DO $$ BEGIN
  IF (SELECT count(*) FROM organizacion.persona) <> 1 THEN RAISE 'A debería ver solo su persona'; END IF;
  IF (SELECT nombre_normal FROM organizacion.persona) <> 'GARCIA CRUZ BLADIMIR LEON' THEN
    RAISE 'el nombre de la persona no se normalizó';
  END IF;
  RAISE NOTICE 'ok · personas: cada empresa ve solo las suyas';
END $$;
DO $$ BEGIN
  DELETE FROM organizacion.persona;
  RAISE 'app pudo borrar personas';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · la app no borra personas (se desactivan)';
END $$;
DO $$ BEGIN
  INSERT INTO organizacion.persona (empresa_id, nombre, nss)
  VALUES (current_setting('app.empresa_id')::bigint, 'Sin NSS válido', '123');
  RAISE 'se aceptó un NSS de 3 dígitos';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · el NSS lleva 11 dígitos';
END $$;

-- Una carga de A
INSERT INTO migracion.archivo (empresa_id, sha256, nombre, tamano, contenido, usuario_id)
VALUES (current_setting('app.empresa_id')::bigint, repeat('a', 64), 'almacen.xlsx', 3, '\x010203',
        current_setting('app.usuario_id')::bigint);
INSERT INTO migracion.carga (empresa_id, tipo, archivo, sha256, hojas, version_reglas, usuario_id)
VALUES (current_setting('app.empresa_id')::bigint, 'CONTRATOS', 'almacen.xlsx', repeat('a', 64),
        'CONTRATOS ACTIVOS', 'h3.1', current_setting('app.usuario_id')::bigint);
SELECT set_config('pruebas.carga_a', (SELECT max(id)::text FROM migracion.carga), false) \gset

DO $$ BEGIN
  IF (SELECT count(*) FROM migracion.carga) <> 1 THEN RAISE 'A debería ver solo su carga'; END IF;
  IF EXISTS (SELECT 1 FROM migracion.archivo WHERE sha256 = repeat('b', 64)) THEN RAISE 'A ve archivos de B'; END IF;
  RAISE NOTICE 'ok · migración: cada empresa ve solo sus cargas y archivos';
END $$;

-- Idempotencia: el mismo archivo y tipo con las mismas reglas no se carga dos veces
DO $$ BEGIN
  INSERT INTO migracion.carga (empresa_id, tipo, archivo, sha256, hojas, version_reglas, usuario_id)
  VALUES (current_setting('app.empresa_id')::bigint, 'CONTRATOS', 'copia.xlsx', repeat('a', 64),
          'CONTRATOS ACTIVOS', 'h3.1', current_setting('app.usuario_id')::bigint);
  RAISE 'se aceptó la misma carga dos veces';
EXCEPTION WHEN unique_violation THEN
  RAISE NOTICE 'ok · el mismo archivo no se carga dos veces con las mismas reglas';
END $$;

-- Un renglón con su incidencia; una incidencia no se cuelga de la carga de otra empresa
INSERT INTO migracion.fila (empresa_id, carga_id, numero, hoja, fila_excel, crudo, estado, accion)
VALUES (current_setting('app.empresa_id')::bigint, current_setting('pruebas.carga_a')::bigint, 1,
        'CONTRATOS ACTIVOS', 4, '{"B":"60-KZ7-MF6/C002/2026"}', 'corregida', 'crear');
INSERT INTO migracion.incidencia (empresa_id, carga_id, numero, regla, severidad, detalle)
VALUES (current_setting('app.empresa_id')::bigint, current_setting('pruebas.carga_a')::bigint, 1,
        'CODIGO_NORMALIZADO', 'info', 'La / se guarda como -');
DO $$ BEGIN
  INSERT INTO migracion.fila (empresa_id, carga_id, numero, hoja, crudo, estado, accion)
  VALUES (current_setting('app.empresa_id')::bigint, current_setting('pruebas.carga_b')::bigint, 1,
          'IMSS OCTUBRE', '{}', 'valida', 'crear');
  RAISE 'se colgó un renglón de una carga de otra empresa';
EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'ok · un renglón no se cuelga de la carga de otra empresa';
END $$;

-- Alias: el texto se guarda normalizado
DO $$ BEGIN
  INSERT INTO migracion.alias (empresa_id, entidad, texto, destino_id, origen)
  VALUES (current_setting('app.empresa_id')::bigint, 'persona', 'Bladimir gcia', 1, 'manual');
  RAISE 'se aceptó un alias sin normalizar';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · el texto del alias va normalizado';
END $$;
INSERT INTO migracion.alias (empresa_id, entidad, texto, destino_id, origen, carga_id)
SELECT current_setting('app.empresa_id')::bigint, 'persona', 'BLADIMIR GCIA', id, 'manual',
       current_setting('pruebas.carga_a')::bigint
  FROM organizacion.persona;

-- Reversa: borra lo que creó la carga en A, nada de B, y nada si algo lo usa
INSERT INTO estructura.contrato (empresa_id, cliente_id, codigo, origen_carga)
SELECT empresa_id, id, '60-A-IMPORTADO', current_setting('pruebas.carga_a')::bigint
  FROM estructura.cliente WHERE nombre = 'Gobierno del Estado';
INSERT INTO estructura.prototipo (empresa_id, clave, nombre, origen_carga)
VALUES (current_setting('app.empresa_id')::bigint, 'P-IMP', 'Importado', current_setting('pruebas.carga_a')::bigint);
-- Un lote capturado a mano (sin carga) sobre el contrato importado
INSERT INTO estructura.unidad_obra (empresa_id, contrato_id, tipo_id, clave)
SELECT empresa_id, id, 2, 'EDIFICIO-A' FROM estructura.contrato WHERE codigo = '60-A-IMPORTADO';

DO $$ BEGIN
  PERFORM estructura.revertir_carga(current_setting('pruebas.carga_a')::bigint);
  RAISE 'se revirtió un contrato que usa un lote capturado a mano';
EXCEPTION WHEN foreign_key_violation THEN
  IF NOT EXISTS (SELECT 1 FROM estructura.prototipo WHERE clave = 'P-IMP') THEN
    RAISE 'la reversa fallida borró parte de la carga';
  END IF;
  RAISE NOTICE 'ok · una carga no se revierte si algo usa lo que creó, y no borra nada';
END $$;

RESET ROLE;
SET ROLE migrador;
DELETE FROM estructura.unidad_obra WHERE clave = 'EDIFICIO-A';
RESET ROLE;
SET ROLE app;

DO $$
DECLARE
  v_borrados integer;
BEGIN
  v_borrados := estructura.revertir_carga(current_setting('pruebas.carga_a')::bigint);
  IF v_borrados <> 2 THEN RAISE 'la reversa debía borrar 2 registros y borró %', v_borrados; END IF;
  IF EXISTS (SELECT 1 FROM estructura.contrato WHERE codigo = '60-A-IMPORTADO') THEN RAISE 'el contrato importado sigue'; END IF;
  RAISE NOTICE 'ok · revertir una carga borra lo que creó (% registros)', v_borrados;
END $$;

-- La misma función, con el número de la carga de B, no toca B
DO $$ BEGIN
  IF estructura.revertir_carga(current_setting('pruebas.carga_b')::bigint) <> 0 THEN
    RAISE 'A borró registros de una carga de B';
  END IF;
  IF organizacion.revertir_carga_personas(current_setting('pruebas.carga_b')::bigint) <> 0 THEN
    RAISE 'A borró personas de B';
  END IF;
END $$;
RESET ROLE;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM estructura.contrato WHERE codigo = '60-B-IMPORTADO') THEN RAISE 'se borró el contrato de B'; END IF;
  IF NOT EXISTS (SELECT 1 FROM organizacion.persona WHERE nombre = 'Persona de B') THEN RAISE 'se borró la persona de B'; END IF;
  RAISE NOTICE 'ok · la reversa solo toca la empresa en curso';
END $$;

-- Después de revertida, el mismo archivo se puede volver a cargar
SET ROLE app;
UPDATE migracion.carga SET estado = 'revertida', revertida = now(), revertida_por = current_setting('app.usuario_id')::bigint
 WHERE id = current_setting('pruebas.carga_a')::bigint;
INSERT INTO migracion.carga (empresa_id, tipo, archivo, sha256, hojas, version_reglas, usuario_id)
VALUES (current_setting('app.empresa_id')::bigint, 'CONTRATOS', 'almacen.xlsx', repeat('a', 64),
        'CONTRATOS ACTIVOS', 'h3.1', current_setting('app.usuario_id')::bigint);
DO $$ BEGIN
  IF (SELECT count(*) FROM migracion.carga) <> 2 THEN RAISE 'no se pudo volver a cargar después de revertir'; END IF;
  RAISE NOTICE 'ok · una carga revertida se puede volver a subir';
END $$;

-- La app no borra cargas, archivos ni el registro de lo creado
DO $$ BEGIN
  DELETE FROM migracion.carga;
  RAISE 'app pudo borrar cargas';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · las cargas no se borran, se revierten';
END $$;

-- Sin empresa en el contexto la reversa no corre
RESET ROLE;
SELECT set_config('app.empresa_id', '', false) \gset
SET ROLE app;
DO $$ BEGIN
  PERFORM catalogo.revertir_carga(1);
  RAISE 'la reversa corrió sin empresa';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · sin empresa en el contexto la reversa no corre';
END $$;
RESET ROLE;

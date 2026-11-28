-- Pruebas de las defensas de la estabilización (H9): incidentes del piloto y el cierre de mes que
-- no deja conteos, aperturas ni documentos sin terminar. Corre después de apertura.sql, sobre las
-- empresas A y B de seguridad.sql. Cada bloque lanza una excepción si la regla no se cumple; el
-- flujo por HTTP se prueba en Java (CriterioH9Test).
\set ON_ERROR_STOP on
\set QUIET on

RESET ROLE;
SELECT set_config('pruebas.empresa_a', (SELECT id::text FROM organizacion.empresa WHERE rfc = 'AAA010101AA1'), false) \gset
SELECT set_config('pruebas.empresa_b', (SELECT id::text FROM organizacion.empresa WHERE rfc = 'BBB010101BB1'), false) \gset
SELECT set_config('pruebas.admin_a', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'a.admin'), false) \gset
SELECT set_config('pruebas.almacen_a', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'almacen.uno'), false) \gset

-- ============ Incidentes: el almacenista reporta ============
SELECT set_config('app.empresa_id', current_setting('pruebas.empresa_a'), false) \gset
SELECT set_config('app.usuario_id', current_setting('pruebas.almacen_a'), false) \gset
SET ROLE app;

INSERT INTO soporte.incidente (empresa_id, folio, severidad, titulo, descripcion, almacen_id,
                               pantalla, peticion, documento, reportado_por)
SELECT empresa_id, 'INC-2027-000001', 'critica', 'No deja aplicar el vale',
       'Al aplicar el vale sale un error y no se puede entregar el material', id,
       '/inventario/movimientos/12', 'b7e0c2d4-1f3a-4c55-9e1d-0a2b3c4d5e6f', 'SAL-2027-000012',
       current_setting('app.usuario_id')::bigint
  FROM inventario.almacen WHERE clave = 'PIL';
INSERT INTO soporte.incidente_nota (empresa_id, incidente_id, tipo, texto, usuario_id)
SELECT empresa_id, id, 'nota', 'Se reportó desde el almacén', reportado_por
  FROM soporte.incidente WHERE folio = 'INC-2027-000001';

DO $$ BEGIN
  INSERT INTO soporte.incidente (empresa_id, folio, severidad, estado, titulo, descripcion, reportado_por)
  VALUES (current_setting('app.empresa_id')::bigint, 'INC-2027-000002', 'menor', 'resuelto',
          'Ya resuelto', 'Nace resuelto', current_setting('app.usuario_id')::bigint);
  RAISE 'un incidente nació resuelto';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · un incidente nace abierto';
END $$;

DO $$ BEGIN
  INSERT INTO soporte.incidente (empresa_id, folio, severidad, titulo, descripcion, reportado_por)
  VALUES (current_setting('app.empresa_id')::bigint, 'INC-2027-000003', 'urgente',
          'Severidad inventada', 'x', current_setting('app.usuario_id')::bigint);
  RAISE 'se aceptó una severidad que no existe';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · la severidad es crítica, mayor o menor';
END $$;

DO $$ BEGIN
  INSERT INTO soporte.incidente (empresa_id, folio, severidad, titulo, descripcion, peticion, reportado_por)
  VALUES (current_setting('app.empresa_id')::bigint, 'INC-2027-000004', 'menor', 'Petición rara',
          'x', 'abc; DROP TABLE', current_setting('app.usuario_id')::bigint);
  RAISE 'se aceptó un identificador de petición con caracteres raros';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · el X-Request-Id solo lleva letras, números y guiones';
END $$;

DO $$ BEGIN
  UPDATE soporte.incidente SET titulo = 'Otro título', version = version + 1
   WHERE folio = 'INC-2027-000001';
  RAISE 'se cambió lo reportado';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · lo reportado no cambia';
END $$;

DO $$ BEGIN
  UPDATE soporte.incidente SET estado = 'resuelto', version = version + 1
   WHERE folio = 'INC-2027-000001';
  RAISE 'se resolvió sin quién, cuándo ni cómo';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · resolver lleva quién, cuándo y la resolución';
END $$;

DO $$ BEGIN
  DELETE FROM soporte.incidente WHERE folio = 'INC-2027-000001';
  RAISE 'la app borró un incidente';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · un incidente no se borra';
END $$;

DO $$ BEGIN
  UPDATE soporte.incidente_nota SET texto = 'Otra cosa';
  RAISE 'la app editó una nota';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · una nota no se edita';
END $$;
RESET ROLE;

-- ============ Administración atiende: severidad, resolver, reabrir ============
SELECT set_config('app.usuario_id', current_setting('pruebas.admin_a'), false) \gset
SET ROLE app;
UPDATE soporte.incidente SET severidad = 'mayor', issue = 41, version = version + 1
 WHERE folio = 'INC-2027-000001';
UPDATE soporte.incidente
   SET estado = 'resuelto', cerrado_por = current_setting('app.usuario_id')::bigint,
       cerrado_en = now(), resolucion = 'Corregido en la versión 1.9.1', version = version + 1
 WHERE folio = 'INC-2027-000001';

DO $$ BEGIN
  UPDATE soporte.incidente SET severidad = 'critica', version = version + 1
   WHERE folio = 'INC-2027-000001';
  RAISE 'se cambió la severidad de un incidente resuelto';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · un incidente cerrado no cambia: se reabre';
END $$;

DO $$ BEGIN
  UPDATE soporte.incidente
     SET estado = 'descartado', version = version + 1 WHERE folio = 'INC-2027-000001';
  RAISE 'se pasó de resuelto a descartado';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · de resuelto solo se reabre';
END $$;

DO $$ BEGIN
  UPDATE soporte.incidente
     SET estado = 'abierto', cerrado_por = NULL, cerrado_en = NULL, resolucion = NULL,
         version = version + 1
   WHERE folio = 'INC-2027-000001';
  RAISE 'se reabrió sin contar la reapertura';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · reabrir suma una reapertura';
END $$;

UPDATE soporte.incidente
   SET estado = 'abierto', cerrado_por = NULL, cerrado_en = NULL, resolucion = NULL,
       reaperturas = reaperturas + 1, version = version + 1
 WHERE folio = 'INC-2027-000001';
DO $$ BEGIN
  IF (SELECT reaperturas FROM soporte.incidente WHERE folio = 'INC-2027-000001') <> 1 THEN
    RAISE 'no contó la reapertura';
  END IF;
  RAISE NOTICE 'ok · un incidente resuelto se reabre';
END $$;
RESET ROLE;

DO $$ BEGIN
  IF (SELECT count(*) FROM compartido.bitacora WHERE esquema = 'soporte' AND tabla = 'incidente') < 4 THEN
    RAISE 'la bitácora no registró los cambios del incidente';
  END IF;
  RAISE NOTICE 'ok · la bitácora registra cada cambio del incidente';
END $$;

-- ============ Otra empresa no ve ni escribe incidentes de A ============
SELECT set_config('app.empresa_id', current_setting('pruebas.empresa_b'), false) \gset
SELECT set_config('app.usuario_id', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'b.admin'), false) \gset
SET ROLE app;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM soporte.incidente) OR EXISTS (SELECT 1 FROM soporte.incidente_nota) THEN
    RAISE 'B ve los incidentes de A';
  END IF;
  RAISE NOTICE 'ok · cada empresa ve solo sus incidentes';
END $$;
DO $$ BEGIN
  INSERT INTO soporte.incidente (empresa_id, folio, severidad, titulo, descripcion, reportado_por)
  VALUES (current_setting('pruebas.empresa_a')::bigint, 'INC-2027-000099', 'menor', 'Intruso',
          'x', current_setting('app.usuario_id')::bigint);
  RAISE 'B escribió un incidente en A';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · RLS rechaza escribir incidentes en otra empresa';
END $$;
RESET ROLE;

-- ============ Cierre de mes: documentos, conteos y aperturas sin terminar ============
SELECT set_config('app.empresa_id', current_setting('pruebas.empresa_a'), false) \gset
SELECT set_config('app.usuario_id', current_setting('pruebas.admin_a'), false) \gset
SET ROLE app;
INSERT INTO estructura.frente (empresa_id, cliente_id, clave, nombre)
SELECT empresa_id, id, 'CIERRE', 'Frente para el cierre' FROM estructura.cliente WHERE nombre = 'Ruba Desarrollos';
INSERT INTO estructura.frente (empresa_id, cliente_id, clave, nombre)
SELECT empresa_id, id, 'NUEVO', 'Frente que abre' FROM estructura.cliente WHERE nombre = 'Ruba Desarrollos';
INSERT INTO inventario.almacen (empresa_id, frente_id, clave, nombre)
SELECT empresa_id, id, 'CIE', 'Almacén del cierre' FROM estructura.frente WHERE clave = 'CIERRE';
INSERT INTO inventario.almacen (empresa_id, frente_id, clave, nombre)
SELECT empresa_id, id, 'NUE', 'Almacén que abre' FROM estructura.frente WHERE clave = 'NUEVO';

-- Un borrador del 10 de febrero de 2026
INSERT INTO inventario.movimiento (empresa_id, folio, tipo, fecha, almacen_id, usuario_captura)
SELECT empresa_id, 'SAL-2026-900001', 'salida', '2026-02-10', id, current_setting('pruebas.almacen_a')::bigint
  FROM inventario.almacen WHERE clave = 'CIE';
DO $$ BEGIN
  INSERT INTO inventario.periodo (empresa_id, anio, mes, cerrado_por)
  VALUES (current_setting('app.empresa_id')::bigint, 2026, 2, current_setting('app.usuario_id')::bigint);
  RAISE 'se cerró un mes con un documento sin aplicar';
EXCEPTION WHEN check_violation THEN
  IF SQLERRM NOT LIKE 'MES_CON_PENDIENTES%SAL-2026-900001%' THEN RAISE; END IF;
  RAISE NOTICE 'ok · un mes con documentos sin aplicar no se cierra';
END $$;
UPDATE inventario.movimiento
   SET estado = 'cancelado', cancelado_en = now(), motivo_cancelacion = 'Prueba del cierre',
       version = version + 1
 WHERE folio = 'SAL-2026-900001';

-- Un conteo abierto del 15 de febrero
INSERT INTO inventario.conteo (empresa_id, folio, almacen_id, fecha, usuario_crea)
SELECT empresa_id, 'CON-2026-900010', id, '2026-02-15', current_setting('pruebas.almacen_a')::bigint
  FROM inventario.almacen WHERE clave = 'CIE';
DO $$ BEGIN
  INSERT INTO inventario.periodo (empresa_id, anio, mes, cerrado_por)
  VALUES (current_setting('app.empresa_id')::bigint, 2026, 2, current_setting('app.usuario_id')::bigint);
  RAISE 'se cerró un mes con un conteo abierto';
EXCEPTION WHEN check_violation THEN
  IF SQLERRM NOT LIKE 'MES_CON_CONTEO_ABIERTO%CON-2026-900010%' THEN RAISE; END IF;
  RAISE NOTICE 'ok · un mes con un conteo abierto no se cierra (después ya no se podría cerrar)';
END $$;
UPDATE inventario.conteo SET estado = 'cancelado', version = version + 1 WHERE folio = 'CON-2026-900010';

-- Una apertura del 20 de febrero sin aplicar (su conteo es de marzo)
INSERT INTO inventario.conteo (empresa_id, folio, almacen_id, fecha, usuario_crea)
SELECT empresa_id, 'CON-2026-900011', id, '2026-03-02', current_setting('pruebas.almacen_a')::bigint
  FROM inventario.almacen WHERE clave = 'NUE';
INSERT INTO inventario.apertura (empresa_id, folio, almacen_id, conteo_id, fecha, usuario_crea)
SELECT c.empresa_id, 'APE-2026-900011', c.almacen_id, c.id, '2026-02-20', c.usuario_crea
  FROM inventario.conteo c WHERE c.folio = 'CON-2026-900011';
DO $$ BEGIN
  INSERT INTO inventario.periodo (empresa_id, anio, mes, cerrado_por)
  VALUES (current_setting('app.empresa_id')::bigint, 2026, 2, current_setting('app.usuario_id')::bigint);
  RAISE 'se cerró un mes con una apertura sin aplicar';
EXCEPTION WHEN check_violation THEN
  IF SQLERRM NOT LIKE 'MES_CON_APERTURA_ABIERTA%APE-2026-900011%' THEN RAISE; END IF;
  RAISE NOTICE 'ok · un mes con una apertura sin aplicar no se cierra';
END $$;
UPDATE inventario.apertura
   SET estado = 'cancelada', cancelado_en = now(), motivo_cancelacion = 'Prueba del cierre',
       version = version + 1
 WHERE folio = 'APE-2026-900011';
UPDATE inventario.conteo SET estado = 'cancelado', version = version + 1 WHERE folio = 'CON-2026-900011';

-- Un conteo abierto con fecha posterior al mes no lo detiene
INSERT INTO inventario.conteo (empresa_id, folio, almacen_id, fecha, usuario_crea)
SELECT empresa_id, 'CON-2026-900012', id, '2026-03-01', current_setting('pruebas.almacen_a')::bigint
  FROM inventario.almacen WHERE clave = 'CIE';
INSERT INTO inventario.periodo (empresa_id, anio, mes, cerrado_por)
VALUES (current_setting('app.empresa_id')::bigint, 2026, 2, current_setting('app.usuario_id')::bigint);
DO $$ BEGIN
  IF NOT inventario.periodo_cerrado(current_setting('app.empresa_id')::bigint, '2026-02-28') THEN
    RAISE 'no se cerró febrero';
  END IF;
  RAISE NOTICE 'ok · sin pendientes del mes, se cierra aunque haya un conteo del mes siguiente';
END $$;
UPDATE inventario.conteo SET estado = 'cancelado', version = version + 1 WHERE folio = 'CON-2026-900012';
RESET ROLE;

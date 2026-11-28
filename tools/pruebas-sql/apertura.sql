-- Pruebas de las defensas del corte (H8): apertura de un almacén desde el conteo físico, con dos
-- firmas, y la foto de Existencias del Excel. Corre después de abastecimiento.sql, sobre la empresa
-- A (el almacén PAL ya tiene kardex). Cada bloque lanza una excepción si la regla no se cumple; el
-- flujo completo con el costeo se prueba en Java (CriterioH8Test).
\set ON_ERROR_STOP on
\set QUIET on

RESET ROLE;
SELECT set_config('pruebas.empresa_a', (SELECT id::text FROM organizacion.empresa WHERE rfc = 'AAA010101AA1'), false) \gset
SELECT set_config('pruebas.empresa_b', (SELECT id::text FROM organizacion.empresa WHERE rfc = 'BBB010101BB1'), false) \gset
SELECT set_config('pruebas.admin_a', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'a.admin'), false) \gset
SELECT set_config('pruebas.almacen_a', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'almacen.uno'), false) \gset

-- ============ Datos de A: frente y almacén nuevos, una coordinadora, una persona ============
SELECT set_config('app.empresa_id', current_setting('pruebas.empresa_a'), false) \gset
SELECT set_config('app.usuario_id', current_setting('pruebas.admin_a'), false) \gset
SET ROLE app;
INSERT INTO estructura.frente (empresa_id, cliente_id, clave, nombre)
SELECT empresa_id, id, 'PILOTO', 'Frente piloto' FROM estructura.cliente WHERE nombre = 'Ruba Desarrollos';
INSERT INTO inventario.almacen (empresa_id, frente_id, clave, nombre)
SELECT empresa_id, id, 'PIL', 'Almacén piloto' FROM estructura.frente WHERE clave = 'PILOTO';
INSERT INTO organizacion.usuario (empresa_id, usuario, nombre, hash_contrasena)
VALUES (current_setting('app.empresa_id')::bigint, 'coord.uno', 'Coordinadora Uno', '{noop}x');
INSERT INTO organizacion.persona (empresa_id, nombre)
VALUES (current_setting('app.empresa_id')::bigint, 'Responsable de herramienta');
RESET ROLE;
SELECT set_config('pruebas.coord_a', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'coord.uno'), false) \gset

-- Abre el almacenista: conteo del almacén piloto
SELECT set_config('app.usuario_id', current_setting('pruebas.almacen_a'), false) \gset
SET ROLE app;
INSERT INTO inventario.conteo (empresa_id, folio, almacen_id, fecha, usuario_crea)
SELECT empresa_id, 'CON-2027-900001', id, current_date, current_setting('app.usuario_id')::bigint
  FROM inventario.almacen WHERE clave = 'PIL';
INSERT INTO inventario.conteo (empresa_id, folio, almacen_id, fecha, usuario_crea)
SELECT empresa_id, 'CON-2027-900002', id, current_date, current_setting('app.usuario_id')::bigint
  FROM inventario.almacen WHERE clave = 'PAL';

DO $$ BEGIN
  INSERT INTO inventario.apertura (empresa_id, folio, almacen_id, conteo_id, fecha, usuario_crea)
  SELECT c.empresa_id, 'APE-2027-900009', c.almacen_id, c.id, current_date, c.usuario_crea
    FROM inventario.conteo c WHERE c.folio = 'CON-2027-900002';
  RAISE 'se abrió un almacén que ya tenía kardex';
EXCEPTION WHEN check_violation THEN
  IF SQLERRM NOT LIKE 'YA_TIENE_MOVIMIENTOS%' THEN RAISE; END IF;
  RAISE NOTICE 'ok · un almacén con movimientos no se vuelve a abrir';
END $$;
UPDATE inventario.conteo SET estado = 'cancelado', version = version + 1 WHERE folio = 'CON-2027-900002';

INSERT INTO inventario.apertura (empresa_id, folio, almacen_id, conteo_id, fecha, usuario_crea)
SELECT c.empresa_id, 'APE-2027-900001', c.almacen_id, c.id, current_date, c.usuario_crea
  FROM inventario.conteo c WHERE c.folio = 'CON-2027-900001';

DO $$ BEGIN
  INSERT INTO inventario.conteo (empresa_id, folio, almacen_id, fecha, usuario_crea)
  SELECT empresa_id, 'CON-2027-900003', id, current_date, current_setting('app.usuario_id')::bigint
    FROM inventario.almacen WHERE clave = 'PIL';
  RAISE 'el almacén tiene dos conteos abiertos';
EXCEPTION WHEN unique_violation THEN
  RAISE NOTICE 'ok · una apertura en curso deja un solo conteo abierto en el almacén';
END $$;

-- Lo que se cuenta: dos materiales
INSERT INTO inventario.conteo_linea (empresa_id, conteo_id, material_id, contado, contado_por, contado_en)
SELECT c.empresa_id, c.id, m.id, v.contado, current_setting('app.usuario_id')::bigint, now()
  FROM inventario.conteo c, catalogo.material m,
       (VALUES ('INV-1', 12), ('INV-2', 0)) AS v(clave, contado)
 WHERE c.folio = 'CON-2027-900001' AND m.clave = v.clave;

DO $$ BEGIN
  INSERT INTO inventario.movimiento (empresa_id, folio, tipo, fecha, almacen_id, conteo_id, usuario_captura)
  SELECT c.empresa_id, 'AJE-2027-900001', 'ajuste_entrada', current_date, c.almacen_id, c.id,
         current_setting('app.usuario_id')::bigint
    FROM inventario.conteo c WHERE c.folio = 'CON-2027-900001';
  RAISE 'el conteo de la apertura generó un ajuste';
EXCEPTION WHEN check_violation THEN
  IF SQLERRM NOT LIKE 'CONTEO_DE_APERTURA%' THEN RAISE; END IF;
  RAISE NOTICE 'ok · el conteo de una apertura no genera sobrantes ni faltantes';
END $$;

DO $$ BEGIN
  UPDATE inventario.conteo SET estado = 'cerrado', usuario_cierra = current_setting('pruebas.admin_a')::bigint,
         cerrado_en = now() WHERE folio = 'CON-2027-900001';
  RAISE 'se cerró el conteo de la apertura sin firmas';
EXCEPTION WHEN check_violation THEN
  IF SQLERRM NOT LIKE 'CONTEO_DE_APERTURA%' THEN RAISE; END IF;
  RAISE NOTICE 'ok · el conteo de la apertura no se cierra por fuera';
END $$;
DO $$ BEGIN
  UPDATE inventario.conteo SET estado = 'cancelado' WHERE folio = 'CON-2027-900001';
  RAISE 'se canceló el conteo de la apertura por fuera';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · el conteo de la apertura se cancela solo con la apertura';
END $$;

-- Mientras el almacén no abre, no aplica nada más (aunque no tuviera conteo).
INSERT INTO catalogo.proveedor (empresa_id, nombre) VALUES (current_setting('app.empresa_id')::bigint, 'Proveedor del piloto');
INSERT INTO inventario.movimiento (empresa_id, folio, tipo, fecha, almacen_id, proveedor_id, usuario_captura)
SELECT a.empresa_id, 'ENT-2027-900001', 'entrada', current_date, a.id,
       (SELECT id FROM catalogo.proveedor WHERE nombre = 'Proveedor del piloto'),
       current_setting('app.usuario_id')::bigint
  FROM inventario.almacen a WHERE a.clave = 'PIL';
DO $$ BEGIN
  UPDATE inventario.movimiento SET estado = 'aplicado', aplicado_en = now(),
         usuario_aplica = current_setting('app.usuario_id')::bigint
   WHERE folio = 'ENT-2027-900001';
  RAISE 'se aplicó una recepción antes del saldo de apertura';
EXCEPTION WHEN check_violation THEN
  IF SQLERRM NOT LIKE 'ALMACEN_EN_APERTURA%' THEN RAISE; END IF;
  RAISE NOTICE 'ok · el almacén no aplica movimientos antes de su apertura';
END $$;

-- Préstamos: listo exige material y responsable; descartado exige motivo.
RESET ROLE;
SELECT set_config('app.usuario_id', current_setting('pruebas.admin_a'), false) \gset
SET ROLE app;
INSERT INTO inventario.apertura_prestamo (empresa_id, apertura_id, origen_id, fila_excel, articulo, cantidad)
SELECT empresa_id, id, 501, 4, 'Compresor grande naranja', 1 FROM inventario.apertura WHERE folio = 'APE-2027-900001';
DO $$ BEGIN
  UPDATE inventario.apertura_prestamo SET estado = 'listo' WHERE origen_id = 501;
  RAISE 'un préstamo quedó listo sin material ni responsable';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · un préstamo listo lleva material y responsable';
END $$;
DO $$ BEGIN
  INSERT INTO inventario.apertura_prestamo (empresa_id, apertura_id, origen_id, articulo, cantidad)
  SELECT empresa_id, id, 501, 'Otra vez el compresor', 1 FROM inventario.apertura WHERE folio = 'APE-2027-900001';
  RAISE 'se agregó dos veces el mismo préstamo del Excel';
EXCEPTION WHEN unique_violation THEN
  RAISE NOTICE 'ok · cada préstamo del Excel entra una vez';
END $$;
UPDATE inventario.apertura_prestamo
   SET estado = 'listo', material_id = (SELECT id FROM catalogo.material WHERE clave = 'INV-2'),
       responsable_id = (SELECT id FROM organizacion.persona WHERE nombre = 'Responsable de herramienta')
 WHERE origen_id = 501;
INSERT INTO inventario.apertura_costo (empresa_id, apertura_id, material_id, costo_unitario, usuario_id)
SELECT a.empresa_id, a.id, m.id, 850, current_setting('app.usuario_id')::bigint
  FROM inventario.apertura a, catalogo.material m WHERE a.folio = 'APE-2027-900001' AND m.clave = 'INV-2';

-- Quien contó no firma.
RESET ROLE;
SELECT set_config('app.usuario_id', current_setting('pruebas.almacen_a'), false) \gset
SET ROLE app;
INSERT INTO inventario.apertura_linea (empresa_id, apertura_id, material_id, contado, prestado,
                                       costo_unitario, origen_costo, importe)
SELECT a.empresa_id, a.id, m.id, v.contado, v.prestado, v.costo, v.origen, v.importe
  FROM inventario.apertura a, catalogo.material m,
       (VALUES ('INV-1', 12, 0, 40.5::numeric, 'historico', 486::numeric),
               ('INV-2', 0, 1, 850, 'capturado', 850)) AS v(clave, contado, prestado, costo, origen, importe)
 WHERE a.folio = 'APE-2027-900001' AND m.clave = v.clave;
DO $$ BEGIN
  UPDATE inventario.apertura SET estado = 'en_firma', importe = 1336,
         firma_coordinacion = current_setting('pruebas.almacen_a')::bigint, firma_coordinacion_en = now()
   WHERE folio = 'APE-2027-900001';
  RAISE 'firmó quien contó';
EXCEPTION WHEN check_violation THEN
  IF SQLERRM NOT LIKE 'FIRMA_DE_QUIEN_CONTO%' THEN RAISE; END IF;
  RAISE NOTICE 'ok · quien abrió el conteo o contó no firma la apertura';
END $$;
DO $$ BEGIN
  INSERT INTO inventario.apertura_linea (empresa_id, apertura_id, material_id, contado)
  SELECT a.empresa_id, a.id, m.id, 3
    FROM inventario.apertura a, catalogo.material m WHERE a.folio = 'APE-2027-900001' AND m.clave = 'ABS-1';
  RAISE 'una línea con existencia entró sin costo';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · lo que entra en la apertura lleva costo';
END $$;

-- Primera firma (coordinación): se congela lo firmado.
RESET ROLE;
SELECT set_config('app.usuario_id', current_setting('pruebas.coord_a'), false) \gset
SET ROLE app;
UPDATE inventario.apertura SET estado = 'en_firma', importe = 1336,
       firma_coordinacion = current_setting('app.usuario_id')::bigint, firma_coordinacion_en = now(),
       version = version + 1
 WHERE folio = 'APE-2027-900001';
DO $$ BEGIN
  UPDATE inventario.conteo_linea SET contado = 13
   WHERE conteo_id = (SELECT conteo_id FROM inventario.apertura WHERE folio = 'APE-2027-900001');
  RAISE 'cambió lo contado después de firmar';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · firmado, lo contado no cambia';
END $$;
DO $$ BEGIN
  UPDATE inventario.apertura_costo SET costo_unitario = 1;
  RAISE 'cambió un costo después de firmar';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · firmado, los costos no cambian';
END $$;
DO $$ BEGIN
  UPDATE inventario.apertura_prestamo SET cantidad = 2;
  RAISE 'cambió un préstamo después de firmar';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · firmado, los préstamos no cambian';
END $$;
DO $$ BEGIN
  DELETE FROM inventario.apertura_linea;
  RAISE 'se borró lo firmado';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · lo congelado no se borra con la apertura en firma';
END $$;
DO $$ BEGIN
  UPDATE inventario.apertura SET firma_coordinacion = current_setting('pruebas.admin_a')::bigint
   WHERE folio = 'APE-2027-900001';
  RAISE 'una firma cambió de dueño';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · una firma puesta no se cambia';
END $$;
DO $$ BEGIN
  UPDATE inventario.apertura SET firma_administracion = current_setting('pruebas.coord_a')::bigint,
         firma_administracion_en = now()
   WHERE folio = 'APE-2027-900001';
  RAISE 'la misma persona firmó dos veces';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · las dos firmas son de personas distintas';
END $$;
DO $$ BEGIN
  INSERT INTO inventario.movimiento (empresa_id, folio, tipo, fecha, almacen_id, conteo_id, usuario_captura)
  SELECT a.empresa_id, 'SAP-2027-900001', 'apertura', current_date, a.almacen_id, a.conteo_id,
         current_setting('app.usuario_id')::bigint
    FROM inventario.apertura a WHERE a.folio = 'APE-2027-900001';
  RAISE 'el saldo de apertura salió con una sola firma';
EXCEPTION WHEN check_violation THEN
  IF SQLERRM NOT LIKE 'APERTURA_SIN_FIRMAS%' THEN RAISE; END IF;
  RAISE NOTICE 'ok · el saldo de apertura necesita las dos firmas';
END $$;

-- Segunda firma (administración) y aplicación, en el orden del servicio.
RESET ROLE;
SELECT set_config('app.usuario_id', current_setting('pruebas.admin_a'), false) \gset
SET ROLE app;
UPDATE inventario.apertura SET firma_administracion = current_setting('app.usuario_id')::bigint,
       firma_administracion_en = now(), version = version + 1
 WHERE folio = 'APE-2027-900001';
INSERT INTO inventario.movimiento (empresa_id, folio, tipo, fecha, almacen_id, conteo_id, usuario_captura)
SELECT a.empresa_id, 'SAP-2027-900001', 'apertura', current_date, a.almacen_id, a.conteo_id,
       current_setting('app.usuario_id')::bigint
  FROM inventario.apertura a WHERE a.folio = 'APE-2027-900001';
DO $$ BEGIN
  INSERT INTO inventario.movimiento (empresa_id, folio, tipo, fecha, almacen_id, usuario_captura)
  SELECT a.empresa_id, 'SAP-2027-900002', 'apertura', current_date, a.almacen_id,
         current_setting('app.usuario_id')::bigint
    FROM inventario.apertura a WHERE a.folio = 'APE-2027-900001';
  RAISE 'un saldo de apertura sin conteo';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · el saldo de apertura liga el conteo de su apertura';
END $$;
UPDATE inventario.conteo SET estado = 'cerrado', usuario_cierra = current_setting('app.usuario_id')::bigint,
       cerrado_en = now(), version = version + 1
 WHERE id = (SELECT conteo_id FROM inventario.apertura WHERE folio = 'APE-2027-900001');
INSERT INTO inventario.movimiento_linea (empresa_id, movimiento_id, tipo, renglon, material_id, cantidad,
                                         cantidad_captura, unidad_captura_id)
SELECT mv.empresa_id, mv.id, mv.tipo, row_number() OVER (ORDER BY m.id), m.id, l.contado + l.prestado,
       l.contado + l.prestado, m.unidad_control_id
  FROM inventario.movimiento mv
  JOIN inventario.apertura a ON a.conteo_id = mv.conteo_id
  JOIN inventario.apertura_linea l ON l.apertura_id = a.id AND l.contado + l.prestado > 0
  JOIN catalogo.material m ON m.id = l.material_id
 WHERE mv.folio = 'SAP-2027-900001';
UPDATE inventario.movimiento SET estado = 'aplicado', aplicado_en = now(),
       usuario_aplica = current_setting('app.usuario_id')::bigint, version = version + 1
 WHERE folio = 'SAP-2027-900001';
INSERT INTO inventario.prestamo (empresa_id, folio, almacen_id, material_id, cantidad, responsable_id,
                                 fecha, usuario_presta)
SELECT p.empresa_id, 'PRE-2027-900001', a.almacen_id, p.material_id, p.cantidad, p.responsable_id,
       current_date, current_setting('app.usuario_id')::bigint
  FROM inventario.apertura_prestamo p JOIN inventario.apertura a ON a.id = p.apertura_id
 WHERE p.origen_id = 501;
UPDATE inventario.apertura_prestamo
   SET prestamo_id = (SELECT id FROM inventario.prestamo WHERE folio = 'PRE-2027-900001'), version = version + 1
 WHERE origen_id = 501;
UPDATE inventario.apertura
   SET estado = 'aplicada', movimiento_id = (SELECT id FROM inventario.movimiento WHERE folio = 'SAP-2027-900001'),
       version = version + 1
 WHERE folio = 'APE-2027-900001';
DO $$ BEGIN
  IF (SELECT estado FROM inventario.apertura WHERE folio = 'APE-2027-900001') <> 'aplicada' THEN
    RAISE 'la apertura no quedó aplicada';
  END IF;
  RAISE NOTICE 'ok · con dos firmas se aplica el saldo de apertura y nace el préstamo vivo';
END $$;
DO $$ BEGIN
  UPDATE inventario.apertura SET observaciones = 'cambio' WHERE folio = 'APE-2027-900001';
  RAISE 'cambió una apertura aplicada';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · una apertura aplicada ya no cambia';
END $$;
UPDATE inventario.movimiento SET estado = 'aplicado', aplicado_en = now(),
       usuario_aplica = current_setting('app.usuario_id')::bigint
 WHERE folio = 'ENT-2027-900001';
DO $$ BEGIN
  IF (SELECT estado FROM inventario.movimiento WHERE folio = 'ENT-2027-900001') <> 'aplicado' THEN
    RAISE 'después de abrir, el almacén no aplica';
  END IF;
  RAISE NOTICE 'ok · ya abierto, el almacén opera';
END $$;
DO $$ BEGIN
  INSERT INTO inventario.conteo (empresa_id, folio, almacen_id, fecha, usuario_crea)
  SELECT empresa_id, 'CON-2027-900004', id, current_date, current_setting('app.usuario_id')::bigint
    FROM inventario.almacen WHERE clave = 'PIL';
  INSERT INTO inventario.apertura (empresa_id, folio, almacen_id, conteo_id, fecha, usuario_crea)
  SELECT c.empresa_id, 'APE-2027-900002', c.almacen_id, c.id, current_date, c.usuario_crea
    FROM inventario.conteo c WHERE c.folio = 'CON-2027-900004';
  RAISE 'el almacén abrió dos veces';
EXCEPTION WHEN unique_violation OR check_violation THEN
  RAISE NOTICE 'ok · un almacén abre una sola vez';
END $$;
RESET ROLE;

-- ============ Foto de Existencias del Excel ============
SET ROLE migrador;
INSERT INTO migracion.carga (empresa_id, tipo, archivo, sha256, hojas, version_reglas, usuario_id)
VALUES (current_setting('pruebas.empresa_a')::bigint, 'HIST_EXISTENCIAS', 'almacen-h8.xlsx', repeat('e', 64),
        'Existencias', 'h8.1', current_setting('pruebas.admin_a')::bigint);
SELECT set_config('pruebas.carga_ex', (SELECT max(id)::text FROM migracion.carga), false) \gset
RESET ROLE;
SET ROLE app;
INSERT INTO historico.saldo_excel (empresa_id, material_id, inventario_inicial, calidad, carga_id, numero, hoja, fila_excel)
SELECT m.empresa_id, m.id, 10, 'valida', current_setting('pruebas.carga_ex')::bigint, 1, 'Existencias', 5
  FROM catalogo.material m WHERE m.clave = 'INV-1';
DO $$
DECLARE
  v historico.v_existencia_excel;
  v_entradas numeric;
  v_salidas numeric;
BEGIN
  SELECT coalesce(sum(cantidad), 0) INTO v_entradas FROM historico.entrada e
    JOIN catalogo.material m ON m.id = e.material_id WHERE m.clave = 'INV-1';
  SELECT coalesce(sum(cantidad), 0) INTO v_salidas FROM historico.salida s
    JOIN catalogo.material m ON m.id = s.material_id WHERE m.clave = 'INV-1' AND s.entregado;
  SELECT x.* INTO v FROM historico.v_existencia_excel x
    JOIN catalogo.material m ON m.id = x.material_id WHERE m.clave = 'INV-1';
  IF v.inicial <> 10 OR v.existencia <> 10 + v_entradas - v_salidas THEN
    RAISE 'la existencia del Excel no es inicial + entradas − salidas entregadas (%)', v;
  END IF;
  RAISE NOTICE 'ok · existencia del Excel = inventario inicial + entradas − salidas entregadas';
END $$;
RESET ROLE;
SET ROLE migrador;
DO $$ BEGIN
  UPDATE historico.saldo_excel SET inventario_inicial = 11;
  RAISE 'se editó la foto de Existencias';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · la foto de Existencias no se edita, ni el dueño de la tabla';
END $$;
RESET ROLE;
SET ROLE app;
DO $$
DECLARE
  v integer;
BEGIN
  v := historico.revertir_carga(current_setting('pruebas.carga_ex')::bigint);
  IF v <> 1
     OR EXISTS (SELECT 1 FROM historico.saldo_excel) THEN
    RAISE 'la reversa no borró la foto de Existencias (%)', v;
  END IF;
  RAISE NOTICE 'ok · revertir la carga borra la foto de Existencias';
END $$;
RESET ROLE;

-- ============ Otra empresa ============
SELECT set_config('app.empresa_id', current_setting('pruebas.empresa_b'), false) \gset
SELECT set_config('app.usuario_id', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'b.admin'), false) \gset
SET ROLE app;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM inventario.apertura) OR EXISTS (SELECT 1 FROM inventario.apertura_linea)
     OR EXISTS (SELECT 1 FROM inventario.apertura_prestamo) OR EXISTS (SELECT 1 FROM inventario.apertura_costo) THEN
    RAISE 'B ve la apertura de A';
  END IF;
  RAISE NOTICE 'ok · cada empresa ve solo sus aperturas';
END $$;
DO $$ BEGIN
  INSERT INTO inventario.apertura_costo (empresa_id, apertura_id, material_id, costo_unitario, usuario_id)
  SELECT current_setting('pruebas.empresa_a')::bigint, 1, 1, 1, 1;
  RAISE 'B escribió en una apertura de A';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · RLS rechaza escribir en la apertura de otra empresa';
END $$;
RESET ROLE;

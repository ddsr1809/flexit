-- Pruebas de las defensas del inventario (H4). Corre después de migracion.sql, sobre las empresas
-- A y B de seguridad.sql. Cada bloque lanza una excepción si la regla no se cumple. Aquí se prueba
-- lo que la base hace cumplir aunque el código falle; el costeo y la concurrencia se prueban en
-- Java (CriterioH4Test, CosteoTest).
\set ON_ERROR_STOP on
\set QUIET on

RESET ROLE;
SELECT set_config('pruebas.empresa_a', (SELECT id::text FROM organizacion.empresa WHERE rfc = 'AAA010101AA1'), false) \gset
SELECT set_config('pruebas.empresa_b', (SELECT id::text FROM organizacion.empresa WHERE rfc = 'BBB010101BB1'), false) \gset
SELECT set_config('pruebas.admin_a', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'a.admin'), false) \gset
SELECT set_config('pruebas.almacen_a', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'almacen.uno'), false) \gset

-- ============ Datos de A (como app): frente, contrato con dos lotes, otro contrato, paquete, material ============
SELECT set_config('app.empresa_id', current_setting('pruebas.empresa_a'), false) \gset
SELECT set_config('app.usuario_id', current_setting('pruebas.almacen_a'), false) \gset
SET ROLE app;

INSERT INTO estructura.frente (empresa_id, cliente_id, clave, nombre)
SELECT empresa_id, id, 'PALERMO', 'MDZ Palermo' FROM estructura.cliente WHERE nombre = 'Ruba Desarrollos';
INSERT INTO estructura.contrato (empresa_id, cliente_id, frente_id, codigo)
SELECT f.empresa_id, f.cliente_id, f.id, '60-PAL-UNO' FROM estructura.frente f WHERE f.clave = 'PALERMO';
INSERT INTO estructura.contrato (empresa_id, cliente_id, frente_id, codigo)
SELECT f.empresa_id, f.cliente_id, f.id, '60-PAL-DOS' FROM estructura.frente f WHERE f.clave = 'PALERMO';
INSERT INTO estructura.unidad_obra (empresa_id, contrato_id, frente_id, tipo_id, clave, manzana, lote)
SELECT c.empresa_id, c.id, c.frente_id, 1, 'M' || m || '-L' || l, m, l
  FROM estructura.contrato c, (VALUES ('3', '1'), ('3', '2')) AS v(m, l) WHERE c.codigo = '60-PAL-UNO';
INSERT INTO estructura.unidad_obra (empresa_id, contrato_id, frente_id, tipo_id, clave, manzana, lote)
SELECT c.empresa_id, c.id, c.frente_id, 1, 'M9-L9', '9', '9' FROM estructura.contrato c WHERE c.codigo = '60-PAL-DOS';
INSERT INTO estructura.paquete (empresa_id, clave, nombre) VALUES (current_setting('app.empresa_id')::bigint, '9', 'Acabados');
INSERT INTO catalogo.material (empresa_id, clave, nombre, unidad_control_id)
SELECT current_setting('app.empresa_id')::bigint, 'INV-1', 'Pintura vinílica blanca', id
  FROM catalogo.unidad_medida WHERE clave = 'L' AND empresa_id IS NULL;
INSERT INTO catalogo.material (empresa_id, clave, nombre, unidad_control_id)
SELECT current_setting('app.empresa_id')::bigint, 'INV-2', 'Rodillo de felpa', id
  FROM catalogo.unidad_medida WHERE clave = 'PZA' AND empresa_id IS NULL;

INSERT INTO inventario.almacen (empresa_id, frente_id, clave, nombre)
SELECT empresa_id, id, 'PAL', 'Almacén Palermo' FROM estructura.frente WHERE clave = 'PALERMO';
INSERT INTO inventario.almacen (empresa_id, frente_id, clave, nombre)
SELECT empresa_id, id, 'COR', 'Almacén Corceles' FROM estructura.frente WHERE clave = 'CORCELES';

DO $$ BEGIN
  INSERT INTO inventario.almacen (empresa_id, frente_id, clave, nombre)
  SELECT empresa_id, id, 'PAL2', 'Otro almacén de Palermo' FROM estructura.frente WHERE clave = 'PALERMO';
  RAISE 'se dieron de alta dos almacenes para el mismo frente';
EXCEPTION WHEN unique_violation THEN
  RAISE NOTICE 'ok · un almacén por frente';
END $$;

-- Una recepción de 20 L aplicada a mano (lo que hace el servicio): movimiento, línea, kardex, saldo.
INSERT INTO catalogo.proveedor (empresa_id, nombre) VALUES (current_setting('app.empresa_id')::bigint, 'Pinturas de prueba H4');
INSERT INTO inventario.movimiento (empresa_id, folio, tipo, fecha, almacen_id, proveedor_id, usuario_captura)
SELECT a.empresa_id, 'ENT-2026-000001', 'entrada', current_date, a.id,
       (SELECT id FROM catalogo.proveedor WHERE nombre = 'Pinturas de prueba H4'), current_setting('app.usuario_id')::bigint
  FROM inventario.almacen a WHERE a.clave = 'PAL';

INSERT INTO inventario.movimiento_linea (empresa_id, movimiento_id, tipo, renglon, material_id, cantidad,
                                         cantidad_captura, unidad_captura_id, precio_captura)
SELECT m.empresa_id, m.id, m.tipo, 1, mat.id, 20, 20, mat.unidad_control_id, 50
  FROM inventario.movimiento m, catalogo.material mat
 WHERE m.folio = 'ENT-2026-000001' AND mat.clave = 'INV-1';
UPDATE inventario.movimiento_linea SET costo_unitario = 50, importe = 1000
 WHERE movimiento_id = (SELECT id FROM inventario.movimiento WHERE folio = 'ENT-2026-000001');
INSERT INTO inventario.saldo (empresa_id, almacen_id, material_id, existencia, valor, costo_promedio)
SELECT l.empresa_id, m.almacen_id, l.material_id, 20, 1000, 50
  FROM inventario.movimiento_linea l JOIN inventario.movimiento m ON m.id = l.movimiento_id
 WHERE m.folio = 'ENT-2026-000001';
INSERT INTO inventario.kardex (empresa_id, almacen_id, material_id, movimiento_id, linea_id, fecha, cantidad,
                               costo_unitario, importe, existencia_despues, valor_despues, costo_promedio_despues)
SELECT l.empresa_id, m.almacen_id, l.material_id, m.id, l.id, m.fecha, 20, 50, 1000, 20, 1000, 50
  FROM inventario.movimiento_linea l JOIN inventario.movimiento m ON m.id = l.movimiento_id
 WHERE m.folio = 'ENT-2026-000001';
UPDATE inventario.movimiento SET estado = 'aplicado', aplicado_en = now(),
       usuario_aplica = current_setting('app.usuario_id')::bigint, version = version + 1
 WHERE folio = 'ENT-2026-000001';

-- ============ Existencia ============
DO $$ BEGIN
  UPDATE inventario.saldo SET existencia = existencia - 21;
  RAISE 'la existencia quedó negativa';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · la existencia nunca queda negativa';
END $$;
DO $$ BEGIN
  UPDATE inventario.saldo SET existencia = 0;
  RAISE 'quedó valor sin existencia';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · sin existencia no queda valor';
END $$;
DO $$ BEGIN
  UPDATE inventario.saldo SET prestado = 15, reservado = 6;
  RAISE 'se apartó más de lo que hay';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · lo prestado y reservado no pasa de la existencia';
END $$;

-- ============ Kardex y cargos inmutables ============
DO $$ BEGIN
  UPDATE inventario.kardex SET cantidad = 1;
  RAISE 'la app modificó el kardex';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · la app no modifica el kardex';
END $$;
DO $$ BEGIN
  DELETE FROM inventario.kardex;
  RAISE 'la app borró el kardex';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · la app no borra el kardex';
END $$;
RESET ROLE;
SET ROLE migrador;
DO $$ BEGIN
  DELETE FROM inventario.kardex;
  RAISE 'el dueño de las tablas borró el kardex';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · ni el dueño de las tablas modifica el kardex';
END $$;
RESET ROLE;
SET ROLE app;

-- ============ Documento aplicado ============
DO $$ BEGIN
  UPDATE inventario.movimiento SET observaciones = 'cambio posterior' WHERE folio = 'ENT-2026-000001';
  RAISE 'se modificó un documento aplicado';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · un documento aplicado no cambia';
END $$;
DO $$ BEGIN
  UPDATE inventario.movimiento_linea SET cantidad = 30
   WHERE movimiento_id = (SELECT id FROM inventario.movimiento WHERE folio = 'ENT-2026-000001');
  RAISE 'se modificó la línea de un documento aplicado';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · la línea de un documento aplicado no cambia';
END $$;
DO $$ BEGIN
  INSERT INTO inventario.movimiento_linea (empresa_id, movimiento_id, tipo, renglon, material_id, cantidad,
                                           cantidad_captura, unidad_captura_id, precio_captura)
  SELECT m.empresa_id, m.id, m.tipo, 2, mat.id, 1, 1, mat.unidad_control_id, 1
    FROM inventario.movimiento m, catalogo.material mat
   WHERE m.folio = 'ENT-2026-000001' AND mat.clave = 'INV-1';
  RAISE 'se agregó una línea a un documento aplicado';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · a un documento aplicado no se le agregan líneas';
END $$;
DO $$ BEGIN
  DELETE FROM inventario.movimiento;
  RAISE 'la app borró movimientos';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · los movimientos no se borran, se cancelan';
END $$;

-- ============ Vale ============
INSERT INTO inventario.movimiento (empresa_id, folio, tipo, fecha, almacen_id, usuario_captura)
SELECT empresa_id, 'SAL-2026-000001', 'salida', current_date, id, current_setting('app.usuario_id')::bigint
  FROM inventario.almacen WHERE clave = 'PAL';

DO $$ BEGIN
  INSERT INTO inventario.movimiento_linea (empresa_id, movimiento_id, tipo, renglon, material_id, cantidad,
                                           cantidad_captura, unidad_captura_id)
  SELECT m.empresa_id, m.id, m.tipo, 1, mat.id, 2, 2, mat.unidad_control_id
    FROM inventario.movimiento m, catalogo.material mat
   WHERE m.folio = 'SAL-2026-000001' AND mat.clave = 'INV-1';
  RAISE 'se aceptó una salida sin contrato';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · una salida sin contrato, lote, paquete y clasificación se rechaza';
END $$;

DO $$ BEGIN
  INSERT INTO inventario.movimiento_linea (empresa_id, movimiento_id, tipo, renglon, material_id, cantidad,
                                           cantidad_captura, unidad_captura_id, contrato_id, unidad_obra_id,
                                           paquete_id, clasificacion_id)
  SELECT m.empresa_id, m.id, m.tipo, 1, mat.id, 2, 2, mat.unidad_control_id,
         (SELECT id FROM estructura.contrato WHERE codigo = '60-PAL-UNO'),
         (SELECT id FROM estructura.unidad_obra WHERE clave = 'M9-L9'),
         (SELECT id FROM estructura.paquete WHERE clave = '9' AND nombre = 'Acabados'), 1
    FROM inventario.movimiento m, catalogo.material mat
   WHERE m.folio = 'SAL-2026-000001' AND mat.clave = 'INV-1';
  RAISE 'se cargó a un lote de otro contrato';
EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'ok · el lote de la línea es del contrato de la línea';
END $$;

INSERT INTO inventario.movimiento_linea (empresa_id, movimiento_id, tipo, renglon, material_id, cantidad,
                                         cantidad_captura, unidad_captura_id, contrato_id, unidad_obra_id,
                                         paquete_id, clasificacion_id)
SELECT m.empresa_id, m.id, m.tipo, 1, mat.id, 2, 2, mat.unidad_control_id,
       c.id, u.id, (SELECT id FROM estructura.paquete WHERE clave = '9' AND nombre = 'Acabados'), 1
  FROM inventario.movimiento m, catalogo.material mat, estructura.contrato c
  JOIN estructura.unidad_obra u ON u.contrato_id = c.id AND u.clave = 'M3-L1'
 WHERE m.folio = 'SAL-2026-000001' AND mat.clave = 'INV-1' AND c.codigo = '60-PAL-UNO';

DO $$ BEGIN
  UPDATE inventario.movimiento SET estado = 'aplicado', aplicado_en = now(),
         usuario_aplica = current_setting('app.usuario_id')::bigint
   WHERE folio = 'SAL-2026-000001';
  RAISE 'se aplicó un vale sin autorizar';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · un vale no se aplica sin autorización';
END $$;

UPDATE inventario.movimiento SET estado = 'pendiente' WHERE folio = 'SAL-2026-000001';
DO $$ BEGIN
  UPDATE inventario.movimiento SET estado = 'autorizado', usuario_autoriza = usuario_captura,
         autorizado_en = now()
   WHERE folio = 'SAL-2026-000001';
  RAISE 'quien capturó el vale lo autorizó';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · quien captura un vale no lo autoriza';
END $$;
DO $$ BEGIN
  INSERT INTO inventario.movimiento_linea (empresa_id, movimiento_id, tipo, renglon, material_id, cantidad,
                                           cantidad_captura, unidad_captura_id, contrato_id, unidad_obra_id,
                                           paquete_id, clasificacion_id)
  SELECT empresa_id, movimiento_id, tipo, 2, material_id, 5, 5, unidad_captura_id, contrato_id,
         unidad_obra_id, paquete_id, clasificacion_id
    FROM inventario.movimiento_linea
   WHERE movimiento_id = (SELECT id FROM inventario.movimiento WHERE folio = 'SAL-2026-000001');
  RAISE 'se agregó una línea a un vale enviado a autorizar';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · lo que se autoriza es lo que se envió: sin líneas nuevas';
END $$;
UPDATE inventario.movimiento SET estado = 'autorizado', usuario_autoriza = current_setting('pruebas.admin_a')::bigint,
       autorizado_en = now()
 WHERE folio = 'SAL-2026-000001';
DO $$ BEGIN
  UPDATE inventario.movimiento SET estado = 'pendiente' WHERE folio = 'SAL-2026-000001';
  RAISE 'un vale autorizado regresó a pendiente';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · las transiciones de estado son las del ciclo del documento';
END $$;

-- Devolución con línea de origen de otro material
INSERT INTO inventario.movimiento (empresa_id, folio, tipo, fecha, almacen_id, usuario_captura)
SELECT empresa_id, 'DEV-2026-000001', 'devolucion', current_date, id, current_setting('app.usuario_id')::bigint
  FROM inventario.almacen WHERE clave = 'PAL';
DO $$ BEGIN
  INSERT INTO inventario.movimiento_linea (empresa_id, movimiento_id, tipo, renglon, material_id, cantidad,
                                           cantidad_captura, unidad_captura_id, contrato_id, unidad_obra_id,
                                           paquete_id, clasificacion_id, linea_origen_id)
  SELECT d.empresa_id, d.id, d.tipo, 1, otro.id, 1, 1, otro.unidad_control_id, l.contrato_id,
         l.unidad_obra_id, l.paquete_id, l.clasificacion_id, l.id
    FROM inventario.movimiento d, catalogo.material otro, inventario.movimiento_linea l
   WHERE d.folio = 'DEV-2026-000001' AND otro.clave = 'INV-2'
     AND l.movimiento_id = (SELECT id FROM inventario.movimiento WHERE folio = 'SAL-2026-000001');
  RAISE 'se devolvió un material distinto al que salió';
EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'ok · la devolución es del mismo material que la línea de origen';
END $$;

-- ============ Merma sin evidencia ============
DO $$ BEGIN
  INSERT INTO inventario.movimiento (empresa_id, folio, tipo, fecha, almacen_id, usuario_captura, motivo)
  SELECT empresa_id, 'MER-2026-000001', 'merma', current_date, id, current_setting('app.usuario_id')::bigint, 'Se rompió'
    FROM inventario.almacen WHERE clave = 'PAL';
  RAISE 'se aceptó una merma sin foto';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · una merma lleva foto (o el préstamo perdido)';
END $$;

-- ============ Mes cerrado ============
INSERT INTO inventario.periodo (empresa_id, anio, mes, cerrado_por)
VALUES (current_setting('app.empresa_id')::bigint, 2026, 1, current_setting('pruebas.admin_a')::bigint);
DO $$ BEGIN
  INSERT INTO inventario.movimiento (empresa_id, folio, tipo, fecha, almacen_id, usuario_captura)
  SELECT empresa_id, 'SAL-2026-000099', 'salida', '2026-01-15', id, current_setting('app.usuario_id')::bigint
    FROM inventario.almacen WHERE clave = 'PAL';
  RAISE 'se capturó un movimiento en un mes cerrado';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · un mes cerrado no recibe movimientos';
END $$;
DO $$ BEGIN
  UPDATE inventario.movimiento SET fecha = '2026-01-31' WHERE folio = 'SAL-2026-000001';
  RAISE 'se movió un documento a un mes cerrado';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · un documento no se pasa a un mes cerrado';
END $$;
DO $$ BEGIN
  DELETE FROM inventario.periodo;
  RAISE 'la app reabrió un mes';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · la app no reabre un mes cerrado';
END $$;

-- ============ Conteo ============
INSERT INTO inventario.conteo (empresa_id, folio, almacen_id, fecha, usuario_crea)
SELECT empresa_id, 'CON-2026-000001', id, current_date, current_setting('app.usuario_id')::bigint
  FROM inventario.almacen WHERE clave = 'PAL';
DO $$ BEGIN
  INSERT INTO inventario.conteo (empresa_id, folio, almacen_id, fecha, usuario_crea)
  SELECT empresa_id, 'CON-2026-000002', id, current_date, current_setting('app.usuario_id')::bigint
    FROM inventario.almacen WHERE clave = 'PAL';
  RAISE 'dos conteos abiertos en el mismo almacén';
EXCEPTION WHEN unique_violation THEN
  RAISE NOTICE 'ok · un solo conteo abierto por almacén';
END $$;
INSERT INTO inventario.conteo_linea (empresa_id, conteo_id, material_id, contado)
SELECT c.empresa_id, c.id, m.id, 19 FROM inventario.conteo c, catalogo.material m
 WHERE c.folio = 'CON-2026-000001' AND m.clave = 'INV-1';
DO $$ BEGIN
  UPDATE inventario.conteo SET estado = 'cerrado', usuario_cierra = usuario_crea, cerrado_en = now()
   WHERE folio = 'CON-2026-000001';
  RAISE 'quien capturó el conteo lo cerró';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · quien abre el conteo no lo cierra';
END $$;
UPDATE inventario.conteo SET estado = 'cerrado', usuario_cierra = current_setting('pruebas.admin_a')::bigint,
       cerrado_en = now()
 WHERE folio = 'CON-2026-000001';
DO $$ BEGIN
  UPDATE inventario.conteo_linea SET contado = 25;
  RAISE 'se cambió un conteo cerrado';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · un conteo cerrado no cambia';
END $$;

-- ============ Archivos ============
INSERT INTO compartido.archivo (empresa_id, nombre, tipo, tamano, sha256, contenido, usuario_id)
VALUES (current_setting('app.empresa_id')::bigint, 'merma.jpg', 'image/jpeg', 3, repeat('c', 64), '\xffd8ff',
        current_setting('app.usuario_id')::bigint);
DO $$ BEGIN
  DELETE FROM compartido.archivo;
  RAISE 'la app borró un archivo';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · la evidencia subida no se borra';
END $$;
DO $$ BEGIN
  INSERT INTO compartido.archivo (empresa_id, nombre, tipo, tamano, sha256, contenido, usuario_id)
  VALUES (current_setting('app.empresa_id')::bigint, 'virus.exe', 'application/x-msdownload', 2,
          repeat('d', 64), '\x4d5a', current_setting('app.usuario_id')::bigint);
  RAISE 'se aceptó un ejecutable';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · solo imágenes y PDF';
END $$;

-- ============ Otra empresa ============
RESET ROLE;
SELECT set_config('app.empresa_id', current_setting('pruebas.empresa_b'), false) \gset
SET ROLE app;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM inventario.movimiento) OR EXISTS (SELECT 1 FROM inventario.saldo)
     OR EXISTS (SELECT 1 FROM inventario.kardex) OR EXISTS (SELECT 1 FROM inventario.almacen)
     OR EXISTS (SELECT 1 FROM compartido.archivo) THEN
    RAISE 'B ve el inventario de A';
  END IF;
  RAISE NOTICE 'ok · cada empresa ve solo su inventario';
END $$;
DO $$ BEGIN
  INSERT INTO inventario.almacen (empresa_id, frente_id, clave, nombre)
  VALUES (current_setting('app.empresa_id')::bigint,
          (SELECT f.id FROM estructura.frente f LIMIT 1), 'B1', 'Almacén con frente ajeno');
  RAISE 'B creó un almacén sin frente propio';
EXCEPTION WHEN not_null_violation OR foreign_key_violation THEN
  RAISE NOTICE 'ok · un almacén solo se liga a un frente de su empresa';
END $$;
RESET ROLE;

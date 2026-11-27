-- Pruebas de las defensas del abastecimiento (H6). Corre después de historico.sql, sobre los datos
-- de inventario.sql en la empresa A (frente PALERMO con su almacén PAL, contratos 60-PAL-UNO con
-- los lotes M3-L1 y M3-L2 y 60-PAL-DOS con M9-L9, paquete 9). Cada bloque lanza una excepción si
-- la regla no se cumple. El ciclo completo (reservar al autorizar, surtir, recibir contra pedido)
-- se prueba en Java (CriterioH6Test).
\set ON_ERROR_STOP on
\set QUIET on

RESET ROLE;
SELECT set_config('pruebas.empresa_a', (SELECT id::text FROM organizacion.empresa WHERE rfc = 'AAA010101AA1'), false) \gset
SELECT set_config('pruebas.empresa_b', (SELECT id::text FROM organizacion.empresa WHERE rfc = 'BBB010101BB1'), false) \gset
SELECT set_config('pruebas.admin_a', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'a.admin'), false) \gset
SELECT set_config('pruebas.almacen_a', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'almacen.uno'), false) \gset

SELECT set_config('app.empresa_id', current_setting('pruebas.empresa_a'), false) \gset
SELECT set_config('app.usuario_id', current_setting('pruebas.admin_a'), false) \gset
SET ROLE app;

-- ============ Datos: un material con 10 piezas en el almacén PAL y otro sin saldo ============
INSERT INTO catalogo.material (empresa_id, clave, nombre, unidad_control_id)
SELECT current_setting('app.empresa_id')::bigint, 'ABS-1', 'Codo de PVC 1/2"', id
  FROM catalogo.unidad_medida WHERE clave = 'PZA' AND empresa_id IS NULL;
INSERT INTO catalogo.material (empresa_id, clave, nombre, unidad_control_id)
SELECT current_setting('app.empresa_id')::bigint, 'ABS-2', 'Cople de PVC 1/2"', id
  FROM catalogo.unidad_medida WHERE clave = 'PZA' AND empresa_id IS NULL;
INSERT INTO inventario.saldo (empresa_id, almacen_id, material_id, existencia, valor, costo_promedio)
SELECT a.empresa_id, a.id, m.id, 10, 100, 10
  FROM inventario.almacen a, catalogo.material m WHERE a.clave = 'PAL' AND m.clave = 'ABS-1';

-- Requisición en borrador para el contrato 60-PAL-UNO, con dos renglones (un lote cada uno).
INSERT INTO abastecimiento.requisicion (empresa_id, folio, contrato_id, frente_id, almacen_id, fecha,
                                        fecha_requerida, usuario_captura)
SELECT c.empresa_id, 'REQ-2026-000001', c.id, c.frente_id, a.id, current_date, current_date + 2,
       current_setting('app.usuario_id')::bigint
  FROM estructura.contrato c JOIN inventario.almacen a ON a.frente_id = c.frente_id
 WHERE c.codigo = '60-PAL-UNO';
INSERT INTO abastecimiento.requisicion_linea (empresa_id, requisicion_id, renglon, material_id, cantidad,
                                              cantidad_captura, unidad_captura_id, contrato_id,
                                              unidad_obra_id, paquete_id, clasificacion_id)
SELECT r.empresa_id, r.id, v.renglon, m.id, v.cantidad, v.cantidad, m.unidad_control_id, r.contrato_id,
       u.id, (SELECT id FROM estructura.paquete WHERE clave = '9'), 1
  FROM abastecimiento.requisicion r, catalogo.material m, estructura.unidad_obra u,
       (VALUES (1, 'M3-L1', 6), (2, 'M3-L2', 8)) AS v(renglon, clave, cantidad)
 WHERE r.folio = 'REQ-2026-000001' AND m.clave = 'ABS-1' AND u.clave = v.clave
   AND u.contrato_id = r.contrato_id;

DO $$ BEGIN
  IF (SELECT count(*) FROM abastecimiento.requisicion_linea) <> 2 THEN
    RAISE 'la requisición debería tener 2 renglones';
  END IF;
  RAISE NOTICE 'ok · requisición en borrador con renglones por lote';
END $$;

-- ============ Requisición: contrato, almacén y lotes coherentes ============
DO $$ BEGIN
  INSERT INTO abastecimiento.requisicion (empresa_id, folio, contrato_id, frente_id, almacen_id, fecha,
                                          fecha_requerida, usuario_captura)
  SELECT c.empresa_id, 'REQ-2026-000099', c.id, c.frente_id,
         (SELECT id FROM inventario.almacen WHERE clave = 'COR'), current_date, current_date,
         current_setting('app.usuario_id')::bigint
    FROM estructura.contrato c WHERE c.codigo = '60-PAL-UNO';
  RAISE 'se pidió a un almacén de otro frente';
EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'ok · la requisición se surte del almacén del frente de su contrato';
END $$;

DO $$ BEGIN
  INSERT INTO abastecimiento.requisicion_linea (empresa_id, requisicion_id, renglon, material_id, cantidad,
                                                cantidad_captura, unidad_captura_id, contrato_id,
                                                unidad_obra_id, paquete_id, clasificacion_id)
  SELECT r.empresa_id, r.id, 3, m.id, 1, 1, m.unidad_control_id, r.contrato_id,
         (SELECT id FROM estructura.unidad_obra WHERE clave = 'M9-L9'),
         (SELECT id FROM estructura.paquete WHERE clave = '9'), 1
    FROM abastecimiento.requisicion r, catalogo.material m
   WHERE r.folio = 'REQ-2026-000001' AND m.clave = 'ABS-1';
  RAISE 'se pidió para un lote de otro contrato';
EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'ok · los lotes de la requisición son de su contrato';
END $$;

DO $$ BEGIN
  INSERT INTO abastecimiento.requisicion (empresa_id, folio, contrato_id, frente_id, almacen_id, fecha,
                                          fecha_requerida, usuario_captura, estado)
  SELECT c.empresa_id, 'REQ-2026-000098', c.id, c.frente_id, a.id, current_date, current_date,
         current_setting('app.usuario_id')::bigint, 'autorizada'
    FROM estructura.contrato c JOIN inventario.almacen a ON a.frente_id = c.frente_id
   WHERE c.codigo = '60-PAL-UNO';
  RAISE 'una requisición nació autorizada';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · una requisición nace en borrador';
END $$;

-- ============ Ciclo y autorización ============
UPDATE abastecimiento.requisicion SET estado = 'pendiente', version = version + 1
 WHERE folio = 'REQ-2026-000001';

DO $$ BEGIN
  UPDATE abastecimiento.requisicion_linea SET cantidad = 99;
  RAISE 'cambió un renglón de una requisición enviada';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · los renglones solo cambian en borrador';
END $$;
DO $$ BEGIN
  UPDATE abastecimiento.requisicion SET fecha_requerida = fecha_requerida + 5
   WHERE folio = 'REQ-2026-000001';
  RAISE 'cambió la cabecera de una requisición enviada';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · una requisición enviada solo avanza de estado';
END $$;
DO $$ BEGIN
  UPDATE abastecimiento.requisicion SET estado = 'autorizada', autorizada_en = now(),
         usuario_autoriza = usuario_captura
   WHERE folio = 'REQ-2026-000001';
  RAISE 'quien capturó autorizó su requisición';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · quien captura una requisición no la autoriza';
END $$;
DO $$ BEGIN
  UPDATE abastecimiento.requisicion SET estado = 'cerrada', cerrada_en = now(), motivo_cierre = 'x'
   WHERE folio = 'REQ-2026-000001';
  RAISE 'se cerró una requisición sin autorizar';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · solo se cierra una requisición autorizada';
END $$;

UPDATE abastecimiento.requisicion SET estado = 'autorizada', autorizada_en = now(),
       usuario_autoriza = current_setting('pruebas.almacen_a')::bigint, version = version + 1
 WHERE folio = 'REQ-2026-000001';

-- ============ Reserva: lo reservado en el saldo es la suma de las reservas ============
INSERT INTO inventario.reserva (empresa_id, almacen_id, material_id, requisicion_linea_id, cantidad)
SELECT l.empresa_id, r.almacen_id, l.material_id, l.id, 6
  FROM abastecimiento.requisicion_linea l
  JOIN abastecimiento.requisicion r ON r.id = l.requisicion_id
 WHERE r.folio = 'REQ-2026-000001' AND l.renglon = 1;

DO $$ BEGIN
  IF (SELECT reservado FROM inventario.saldo s JOIN catalogo.material m ON m.id = s.material_id
       WHERE m.clave = 'ABS-1') <> 6 THEN
    RAISE 'el saldo no refleja la reserva';
  END IF;
  RAISE NOTICE 'ok · reservar aparta en el saldo';
END $$;

DO $$ BEGIN
  INSERT INTO inventario.reserva (empresa_id, almacen_id, material_id, requisicion_linea_id, cantidad)
  SELECT l.empresa_id, r.almacen_id, l.material_id, l.id, 5
    FROM abastecimiento.requisicion_linea l
    JOIN abastecimiento.requisicion r ON r.id = l.requisicion_id
   WHERE r.folio = 'REQ-2026-000001' AND l.renglon = 2;
  RAISE 'se reservó más de lo que hay';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · no se reserva más de la existencia';
END $$;

INSERT INTO inventario.reserva (empresa_id, almacen_id, material_id, requisicion_linea_id, cantidad)
SELECT l.empresa_id, r.almacen_id, l.material_id, l.id, 4
  FROM abastecimiento.requisicion_linea l
  JOIN abastecimiento.requisicion r ON r.id = l.requisicion_id
 WHERE r.folio = 'REQ-2026-000001' AND l.renglon = 2;

DO $$ BEGIN
  UPDATE inventario.saldo SET existencia = 9, valor = 90
   WHERE material_id = (SELECT id FROM catalogo.material WHERE clave = 'ABS-1');
  RAISE 'la existencia bajó de lo reservado';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · la existencia no baja de lo reservado';
END $$;

UPDATE inventario.reserva SET cantidad = 2, actualizado = now()
 WHERE requisicion_linea_id = (SELECT l.id FROM abastecimiento.requisicion_linea l
                                 JOIN abastecimiento.requisicion r ON r.id = l.requisicion_id
                                WHERE r.folio = 'REQ-2026-000001' AND l.renglon = 1);
DO $$ BEGIN
  IF (SELECT reservado FROM inventario.saldo s JOIN catalogo.material m ON m.id = s.material_id
       WHERE m.clave = 'ABS-1') <> 6 THEN
    RAISE 'el saldo no refleja la reserva liberada';
  END IF;
  RAISE NOTICE 'ok · liberar o surtir una reserva devuelve lo apartado';
END $$;

DO $$ BEGIN
  DELETE FROM inventario.reserva;
  RAISE 'la app borró una reserva';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · una reserva no se borra: se libera';
END $$;

DO $$ BEGIN
  UPDATE inventario.reserva SET material_id = (SELECT id FROM catalogo.material WHERE clave = 'ABS-2');
  RAISE 'una reserva cambió de material';
EXCEPTION WHEN check_violation OR foreign_key_violation THEN
  RAISE NOTICE 'ok · una reserva no cambia de material';
END $$;

-- ============ Vale ligado a la requisición: mismo material y solo vales ============
INSERT INTO inventario.movimiento (empresa_id, folio, tipo, fecha, almacen_id, usuario_captura)
SELECT a.empresa_id, 'SAL-2026-000901', 'salida', current_date, a.id,
       current_setting('pruebas.almacen_a')::bigint
  FROM inventario.almacen a WHERE a.clave = 'PAL';

DO $$ BEGIN
  INSERT INTO inventario.movimiento_linea (empresa_id, movimiento_id, tipo, renglon, material_id, cantidad,
                                           cantidad_captura, unidad_captura_id, contrato_id,
                                           unidad_obra_id, paquete_id, clasificacion_id,
                                           requisicion_linea_id)
  SELECT m.empresa_id, m.id, 'salida', 1, otro.id, 1, 1, otro.unidad_control_id, l.contrato_id,
         l.unidad_obra_id, l.paquete_id, l.clasificacion_id, l.id
    FROM inventario.movimiento m, catalogo.material otro, abastecimiento.requisicion_linea l
   WHERE m.folio = 'SAL-2026-000901' AND otro.clave = 'ABS-2' AND l.renglon = 1;
  RAISE 'un vale surtió otro material';
EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'ok · el vale surte el mismo material que pide la requisición';
END $$;

INSERT INTO inventario.movimiento_linea (empresa_id, movimiento_id, tipo, renglon, material_id, cantidad,
                                         cantidad_captura, unidad_captura_id, contrato_id,
                                         unidad_obra_id, paquete_id, clasificacion_id,
                                         requisicion_linea_id)
SELECT m.empresa_id, m.id, 'salida', 1, l.material_id, 2, 2, l.unidad_captura_id, l.contrato_id,
       l.unidad_obra_id, l.paquete_id, l.clasificacion_id, l.id
  FROM inventario.movimiento m, abastecimiento.requisicion_linea l
 WHERE m.folio = 'SAL-2026-000901' AND l.renglon = 1;

DO $$ BEGIN
  INSERT INTO inventario.movimiento (empresa_id, folio, tipo, fecha, almacen_id, proveedor_id, usuario_captura)
  SELECT a.empresa_id, 'ENT-2026-000902', 'entrada', current_date, a.id,
         (SELECT id FROM catalogo.proveedor LIMIT 1), current_setting('pruebas.almacen_a')::bigint
    FROM inventario.almacen a WHERE a.clave = 'PAL';
  INSERT INTO inventario.movimiento_linea (empresa_id, movimiento_id, tipo, renglon, material_id, cantidad,
                                           cantidad_captura, unidad_captura_id, precio_captura,
                                           requisicion_linea_id)
  SELECT m.empresa_id, m.id, 'entrada', 1, l.material_id, 1, 1, l.unidad_captura_id, 10, l.id
    FROM inventario.movimiento m, abastecimiento.requisicion_linea l
   WHERE m.folio = 'ENT-2026-000902' AND l.renglon = 1;
  RAISE 'una recepción se ligó a una requisición';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · solo un vale surte una requisición';
END $$;

-- ============ Pedido ============
INSERT INTO abastecimiento.pedido (empresa_id, folio, proveedor_id, almacen_id, fecha, usuario_captura)
SELECT a.empresa_id, 'PED-2026-000001', (SELECT id FROM catalogo.proveedor ORDER BY id LIMIT 1), a.id,
       current_date, current_setting('app.usuario_id')::bigint
  FROM inventario.almacen a WHERE a.clave = 'PAL';
INSERT INTO abastecimiento.pedido_linea (empresa_id, pedido_id, renglon, material_id, cantidad,
                                         cantidad_captura, unidad_captura_id, precio, tasa_iva)
SELECT p.empresa_id, p.id, 1, m.id, 12, 12, m.unidad_control_id, 9.5, 0.16
  FROM abastecimiento.pedido p, catalogo.material m
 WHERE p.folio = 'PED-2026-000001' AND m.clave = 'ABS-1';
INSERT INTO abastecimiento.pedido_requisicion (empresa_id, pedido_linea_id, requisicion_linea_id,
                                               material_id, cantidad)
SELECT pl.empresa_id, pl.id, rl.id, pl.material_id, 4
  FROM abastecimiento.pedido_linea pl, abastecimiento.requisicion_linea rl
 WHERE rl.renglon = 2 AND pl.renglon = 1;

DO $$ BEGIN
  INSERT INTO abastecimiento.pedido_linea (empresa_id, pedido_id, renglon, material_id, cantidad,
                                           cantidad_captura, unidad_captura_id, precio, tasa_iva)
  SELECT p.empresa_id, p.id, 2, m.id, 3, 3, m.unidad_control_id, 7, 0.16
    FROM abastecimiento.pedido p, catalogo.material m
   WHERE p.folio = 'PED-2026-000001' AND m.clave = 'ABS-2';
  INSERT INTO abastecimiento.pedido_requisicion (empresa_id, pedido_linea_id, requisicion_linea_id,
                                                 material_id, cantidad)
  SELECT pl.empresa_id, pl.id, rl.id, pl.material_id, 1
    FROM abastecimiento.pedido_linea pl, abastecimiento.requisicion_linea rl
   WHERE rl.renglon = 2 AND pl.renglon = 2;
  RAISE 'un pedido cubrió una requisición de otro material';
EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'ok · el pedido cubre requisiciones del mismo material';
END $$;

DO $$ BEGIN
  UPDATE abastecimiento.pedido_requisicion SET recibido = 1;
  RAISE 'se recibió un pedido en borrador';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · solo se recibe un pedido enviado';
END $$;

UPDATE abastecimiento.pedido SET estado = 'enviado', enviado_en = now(),
       usuario_envia = current_setting('app.usuario_id')::bigint, version = version + 1
 WHERE folio = 'PED-2026-000001';

DO $$ BEGIN
  UPDATE abastecimiento.pedido_linea SET precio = 1;
  RAISE 'cambió el precio de un pedido enviado';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · un pedido enviado no cambia sus renglones';
END $$;
DO $$ BEGIN
  DELETE FROM abastecimiento.pedido_requisicion;
  RAISE 'se quitó la liga de un pedido enviado';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · la liga con la requisición no cambia después de enviar';
END $$;

UPDATE abastecimiento.pedido_requisicion SET recibido = 3;
DO $$ BEGIN
  UPDATE abastecimiento.pedido_requisicion SET recibido = 2;
  RAISE 'lo recibido bajó';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · lo recibido solo crece';
END $$;
DO $$ BEGIN
  UPDATE abastecimiento.pedido_requisicion SET recibido = 5;
  RAISE 'se recibió más de lo que cubre la liga';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · lo recibido no pasa de lo pedido para la requisición';
END $$;

UPDATE abastecimiento.pedido SET estado = 'cerrado', cerrado_en = now(), motivo_cierre = 'Llegó incompleto',
       version = version + 1
 WHERE folio = 'PED-2026-000001';
DO $$ BEGIN
  UPDATE abastecimiento.pedido SET estado = 'enviado' WHERE folio = 'PED-2026-000001';
  RAISE 'se reabrió un pedido cerrado';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · un pedido cerrado ya no cambia';
END $$;

-- ============ Vistas de reportes ============
UPDATE estructura.unidad_obra SET presupuesto_material = 1000, version = version + 1 WHERE clave = 'M3-L1';
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM reportes.v_presupuesto_unidad WHERE clave = 'M3-L1' AND presupuesto = 1000) THEN
    RAISE 'la vista de presupuesto no ve el presupuesto del lote';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM reportes.v_existencia_valorizada v
                   JOIN catalogo.material m ON m.id = v.material_id
                  WHERE m.clave = 'ABS-1' AND v.reservado = 6 AND v.disponible = 4) THEN
    RAISE 'la existencia valorizada no descuenta lo reservado';
  END IF;
  RAISE NOTICE 'ok · vistas de presupuesto por lote y de existencia valorizada';
END $$;

-- ============ Otra empresa ============
RESET ROLE;
SELECT set_config('app.empresa_id', current_setting('pruebas.empresa_b'), false) \gset
SELECT set_config('app.usuario_id', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'b.admin'), false) \gset
SET ROLE app;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM abastecimiento.requisicion) OR EXISTS (SELECT 1 FROM abastecimiento.pedido)
     OR EXISTS (SELECT 1 FROM abastecimiento.requisicion_linea)
     OR EXISTS (SELECT 1 FROM abastecimiento.pedido_requisicion)
     OR EXISTS (SELECT 1 FROM inventario.reserva)
     OR EXISTS (SELECT 1 FROM reportes.v_presupuesto_unidad)
     OR EXISTS (SELECT 1 FROM reportes.v_existencia_valorizada) THEN
    RAISE 'B ve el abastecimiento de A';
  END IF;
  RAISE NOTICE 'ok · cada empresa ve solo su abastecimiento, también por las vistas';
END $$;
DO $$ BEGIN
  INSERT INTO inventario.reserva (empresa_id, almacen_id, material_id, requisicion_linea_id, cantidad)
  SELECT current_setting('pruebas.empresa_a')::bigint, 1, 1, 1, 1;
  RAISE 'B escribió una reserva en A';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · RLS rechaza reservar en otra empresa';
END $$;
RESET ROLE;

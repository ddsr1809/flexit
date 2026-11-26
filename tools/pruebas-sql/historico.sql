-- Pruebas de las defensas del histórico (H5). Corre después de inventario.sql, sobre sus datos de
-- la empresa A (frente PALERMO, contratos 60-PAL-UNO y 60-PAL-DOS, material INV-1, paquete 9).
-- Cada bloque lanza una excepción si la regla no se cumple.
\set ON_ERROR_STOP on
\set QUIET on

RESET ROLE;
SELECT set_config('pruebas.empresa_a', (SELECT id::text FROM organizacion.empresa WHERE rfc = 'AAA010101AA1'), false) \gset
SELECT set_config('pruebas.empresa_b', (SELECT id::text FROM organizacion.empresa WHERE rfc = 'BBB010101BB1'), false) \gset
SELECT set_config('pruebas.usuario_a', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'a.admin'), false) \gset
SELECT set_config('pruebas.usuario_b', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'b.admin'), false) \gset

-- ============ Cargas de A y de B (como migrador) ============
SET ROLE migrador;
INSERT INTO migracion.archivo (empresa_id, sha256, nombre, tamano, contenido, usuario_id)
VALUES (current_setting('pruebas.empresa_a')::bigint, repeat('e', 64), 'almacen-h5.xlsx', 3, '\x010203',
        current_setting('pruebas.usuario_a')::bigint),
       (current_setting('pruebas.empresa_b')::bigint, repeat('f', 64), 'almacen-b.xlsx', 3, '\x010203',
        current_setting('pruebas.usuario_b')::bigint);
INSERT INTO migracion.carga (empresa_id, tipo, archivo, sha256, hojas, version_reglas, usuario_id)
VALUES (current_setting('pruebas.empresa_a')::bigint, 'HIST_SALIDAS', 'almacen-h5.xlsx', repeat('e', 64),
        'Salidas', 'h5.1', current_setting('pruebas.usuario_a')::bigint);
SELECT set_config('pruebas.carga_a', (SELECT max(id)::text FROM migracion.carga), false) \gset
INSERT INTO migracion.carga (empresa_id, tipo, archivo, sha256, hojas, version_reglas, usuario_id)
VALUES (current_setting('pruebas.empresa_b')::bigint, 'HIST_SALIDAS', 'almacen-b.xlsx', repeat('f', 64),
        'Salidas', 'h5.1', current_setting('pruebas.usuario_b')::bigint);
SELECT set_config('pruebas.carga_b', (SELECT max(id)::text FROM migracion.carga), false) \gset
INSERT INTO organizacion.persona (empresa_id, nombre)
VALUES (current_setting('pruebas.empresa_b')::bigint, 'Trabajador de B');

-- Un cargo del sistema (lo que deja un vale aplicado) para la vista que une histórico y vivo.
INSERT INTO inventario.cargo (empresa_id, movimiento_id, linea_id, linea_base_id, fecha, material_id,
                              contrato_id, unidad_obra_id, paquete_id, clasificacion_id, cantidad, importe)
SELECT l.empresa_id, l.movimiento_id, l.id, l.id, current_date, l.material_id, u.contrato_id, u.id,
       (SELECT id FROM estructura.paquete WHERE clave = '9' AND empresa_id = l.empresa_id), 1, 2, 100
  FROM inventario.movimiento_linea l
  JOIN inventario.movimiento m ON m.id = l.movimiento_id AND m.folio = 'ENT-2026-000001'
  JOIN estructura.unidad_obra u ON u.empresa_id = l.empresa_id AND u.clave = 'M3-L1';
RESET ROLE;

-- ============ Como app en la empresa A ============
SELECT set_config('app.empresa_id', current_setting('pruebas.empresa_a'), false) \gset
SELECT set_config('app.usuario_id', current_setting('pruebas.usuario_a'), false) \gset
SET ROLE app;

-- Una salida a un lote, una "Sin asignar" del frente y un vale no entregado.
INSERT INTO historico.salida (empresa_id, fecha, tipo, material_id, cantidad, frente_id, contrato_id,
                              unidad_obra_id, paquete_id, clasificacion_id, costo_unitario, importe,
                              calidad, carga_id, numero, hoja, fila_excel)
SELECT u.empresa_id, DATE '2026-06-22', 'salida', m.id, 3, u.frente_id, u.contrato_id, u.id,
       (SELECT id FROM estructura.paquete WHERE clave = '9'), 1, 10, 30, 'valida',
       current_setting('pruebas.carga_a')::bigint, 1, 'Salidas', 4
  FROM estructura.unidad_obra u, catalogo.material m WHERE u.clave = 'M3-L1' AND m.clave = 'INV-1';
INSERT INTO historico.salida (empresa_id, fecha, tipo, material_id, cantidad, frente_id,
                              clasificacion_id, costo_unitario, importe, costo_estimado, calidad,
                              carga_id, numero, hoja, fila_excel)
SELECT f.empresa_id, DATE '2026-06-23', 'salida', m.id, 1.33333333, f.id, 1, 10, 13.3333, true,
       'corregida', current_setting('pruebas.carga_a')::bigint, 2, 'Salidas', 5
  FROM estructura.frente f, catalogo.material m WHERE f.clave = 'PALERMO' AND m.clave = 'INV-1';
INSERT INTO historico.salida (empresa_id, fecha, tipo, material_id, cantidad, frente_id, entregado,
                              calidad, carga_id, numero, hoja, fila_excel)
SELECT f.empresa_id, DATE '2026-06-24', 'salida', m.id, 7, f.id, false, 'valida',
       current_setting('pruebas.carga_a')::bigint, 3, 'Salidas', 6
  FROM estructura.frente f, catalogo.material m WHERE f.clave = 'PALERMO' AND m.clave = 'INV-1';

DO $$ BEGIN
  IF (SELECT count(*) FROM historico.salida) <> 3 THEN RAISE 'A debería ver sus 3 salidas'; END IF;
  RAISE NOTICE 'ok · histórico: la app inserta salidas al lote, sin asignar y no entregadas';
END $$;

DO $$ BEGIN
  UPDATE historico.salida SET cantidad = 99;
  RAISE 'la app cambió el histórico';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · la app no cambia el histórico';
END $$;
DO $$ BEGIN
  DELETE FROM historico.salida;
  RAISE 'la app borró el histórico';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · la app no borra el histórico';
END $$;

DO $$ BEGIN
  INSERT INTO historico.salida (empresa_id, fecha, tipo, material_id, cantidad, frente_id, contrato_id,
                                unidad_obra_id, calidad, carga_id, numero, hoja)
  SELECT u.empresa_id, DATE '2026-06-22', 'salida', m.id, 1, u.frente_id,
         (SELECT id FROM estructura.contrato WHERE codigo = '60-PAL-UNO'), u.id, 'valida',
         current_setting('pruebas.carga_a')::bigint, 9, 'Salidas'
    FROM estructura.unidad_obra u, catalogo.material m WHERE u.clave = 'M9-L9' AND m.clave = 'INV-1';
  RAISE 'se aceptó un lote de otro contrato';
EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'ok · el lote es del contrato de la salida';
END $$;
DO $$ BEGIN
  INSERT INTO historico.salida (empresa_id, fecha, tipo, material_id, cantidad, calidad, carga_id, numero, hoja)
  SELECT empresa_id, DATE '2026-06-22', 'salida', id, -2, 'valida', current_setting('pruebas.carga_a')::bigint, 9, 'Salidas'
    FROM catalogo.material WHERE clave = 'INV-1';
  RAISE 'una salida quedó con cantidad negativa';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · una cantidad negativa solo es devolución';
END $$;
DO $$ BEGIN
  INSERT INTO historico.salida (empresa_id, fecha, tipo, material_id, cantidad, calidad, carga_id, numero, hoja)
  SELECT empresa_id, DATE '2026-06-22', 'salida', id, 1, 'cuarentena', current_setting('pruebas.carga_a')::bigint, 9, 'Salidas'
    FROM catalogo.material WHERE clave = 'INV-1';
  RAISE 'entró un renglón en cuarentena';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · al histórico solo llegan renglones válidos o corregidos';
END $$;
DO $$ BEGIN
  INSERT INTO historico.salida (empresa_id, fecha, tipo, material_id, cantidad, calidad, carga_id, numero, hoja)
  SELECT empresa_id, DATE '2026-06-22', 'salida', id, 1, 'valida', current_setting('pruebas.carga_b')::bigint, 9, 'Salidas'
    FROM catalogo.material WHERE clave = 'INV-1';
  RAISE 'A ligó un renglón a una carga de B';
EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'ok · cada renglón es de una carga de su empresa';
END $$;

-- Destajo: semanas válidas y trabajadores de la empresa.
INSERT INTO historico.pago_destajo (empresa_id, anio, semanas, frente_id, contrato_id, unidad_obra_id,
                                    monto, calidad, carga_id, numero, hoja, celda)
SELECT u.empresa_id, 2026, '{38,39}', u.frente_id, u.contrato_id, u.id, 2500, 'corregida',
       current_setting('pruebas.carga_a')::bigint, 10, 'Pagos Palermo P-45', 'I8'
  FROM estructura.unidad_obra u WHERE u.clave = 'M3-L1';
DO $$ BEGIN
  INSERT INTO historico.pago_destajo (empresa_id, anio, semanas, frente_id, contrato_id, unidad_obra_id,
                                      monto, calidad, carga_id, numero, hoja)
  SELECT u.empresa_id, 2026, '{54}', u.frente_id, u.contrato_id, u.id, 100, 'valida',
         current_setting('pruebas.carga_a')::bigint, 11, 'Pagos Palermo P-45'
    FROM estructura.unidad_obra u WHERE u.clave = 'M3-L1';
  RAISE 'se aceptó la semana 54';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · las semanas van de 1 a 53';
END $$;
DO $$ BEGIN
  INSERT INTO historico.pago_trabajador (empresa_id, pago_id, persona_id)
  SELECT p.empresa_id, p.id, (SELECT max(id) FROM organizacion.persona) + 1000
    FROM historico.pago_destajo p;
  RAISE 'se ligó un pago a una persona que no es de A';
EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'ok · el trabajador es una persona de la empresa';
END $$;

-- Personal: una marca de asistencia vale 0, 0.5 o 1.
INSERT INTO organizacion.persona (empresa_id, nombre)
VALUES (current_setting('app.empresa_id')::bigint, 'Persona del SUA H5');
DO $$ BEGIN
  INSERT INTO historico.asistencia_dia (empresa_id, fecha, persona_id, valor, calidad, carga_id, numero, hoja)
  SELECT empresa_id, DATE '2026-10-01', id, 2, 'valida', current_setting('pruebas.carga_a')::bigint, 12, 'ASISTENCIA OCTUBRE'
    FROM organizacion.persona WHERE nombre = 'Persona del SUA H5';
  RAISE 'se aceptó una asistencia de 2';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · la asistencia va de 0 a 1';
END $$;
DO $$ BEGIN
  INSERT INTO historico.alta_imss (empresa_id, mes, origen, persona_id, calidad, carga_id, numero, hoja)
  SELECT empresa_id, DATE '2026-10-15', 'mes', id, 'valida', current_setting('pruebas.carga_a')::bigint, 13, 'IMSS OCTUBRE'
    FROM organizacion.persona WHERE nombre = 'Persona del SUA H5';
  RAISE 'se aceptó un mes que no empieza el día 1';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · el mes del SUA es el primer día del mes';
END $$;

-- Alias de paquetes
INSERT INTO migracion.alias (empresa_id, entidad, texto, destino_id, origen)
SELECT empresa_id, 'paquete', 'ACABADO EN MUROS INTERIORES', id, 'manual'
  FROM estructura.paquete WHERE clave = '9';
DO $$ BEGIN
  RAISE NOTICE 'ok · los nombres de paquete del Excel se ligan por alias';
END $$;

-- Vista de costo: histórico entregado + cargos del sistema, con el RLS de quien consulta.
DO $$
DECLARE
  v_hist numeric; v_sis numeric; v_filas bigint;
BEGIN
  SELECT count(*), sum(importe) FILTER (WHERE fuente = 'historico'),
         sum(importe) FILTER (WHERE fuente = 'sistema')
    INTO v_filas, v_hist, v_sis
    FROM reportes.v_costo_unidad;
  IF v_filas <> 3 OR v_hist <> 43.3333 OR v_sis <> 100 THEN
    RAISE 'la vista de costo dio % renglones, histórico % y sistema %', v_filas, v_hist, v_sis;
  END IF;
  IF (SELECT count(*) FROM reportes.v_costo_unidad WHERE fuente = 'historico' AND unidad_obra_id IS NULL) <> 1 THEN
    RAISE 'la salida sin lote no quedó como Sin asignar del frente';
  END IF;
  RAISE NOTICE 'ok · costo por lote = histórico entregado + cargos del sistema';
END $$;
DO $$ BEGIN
  IF (SELECT count(*) FROM reportes.v_destajo_unidad) <> 1 THEN RAISE 'la vista de destajo no ve el pago'; END IF;
  RAISE NOTICE 'ok · vista de destajo por lote';
END $$;

-- ============ Otra empresa: no ve nada, ni por las vistas, ni revierte lo de A ============
RESET ROLE;
SELECT set_config('app.empresa_id', current_setting('pruebas.empresa_b'), false) \gset
SELECT set_config('app.usuario_id', current_setting('pruebas.usuario_b'), false) \gset
SET ROLE app;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM historico.salida) OR EXISTS (SELECT 1 FROM historico.pago_destajo)
     OR EXISTS (SELECT 1 FROM reportes.v_costo_unidad) OR EXISTS (SELECT 1 FROM reportes.v_destajo_unidad) THEN
    RAISE 'B ve el histórico de A';
  END IF;
  RAISE NOTICE 'ok · cada empresa ve solo su histórico, también por las vistas';
END $$;
DO $$ BEGIN
  IF historico.revertir_carga(current_setting('pruebas.carga_a')::bigint) <> 0 THEN
    RAISE 'B revirtió una carga de A';
  END IF;
  RAISE NOTICE 'ok · revertir solo toca la empresa en curso';
END $$;

-- ============ El dueño tampoco cambia el histórico ============
RESET ROLE;
SET ROLE migrador;
DO $$ BEGIN
  UPDATE historico.salida SET cantidad = 99;
  RAISE 'el dueño cambió el histórico';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · ni el dueño de las tablas cambia el histórico';
END $$;
DO $$ BEGIN
  DELETE FROM historico.salida;
  RAISE 'el dueño borró el histórico fuera de una reversa';
EXCEPTION WHEN restrict_violation THEN
  RAISE NOTICE 'ok · solo la reversa de una carga borra el histórico';
END $$;

-- ============ Revertir la carga de A ============
RESET ROLE;
SELECT set_config('app.empresa_id', current_setting('pruebas.empresa_a'), false) \gset
SELECT set_config('app.usuario_id', current_setting('pruebas.usuario_a'), false) \gset
SET ROLE app;
DO $$
DECLARE
  v_borrados integer;
BEGIN
  v_borrados := historico.revertir_carga(current_setting('pruebas.carga_a')::bigint);
  IF v_borrados <> 4 THEN RAISE 'la reversa borró % renglones y no 4', v_borrados; END IF;
  IF EXISTS (SELECT 1 FROM historico.salida) OR EXISTS (SELECT 1 FROM historico.pago_destajo) THEN
    RAISE 'quedó histórico de la carga revertida';
  END IF;
  IF current_setting('historico.reversa', true) = 'on' THEN
    RAISE 'la reversa dejó encendido el permiso de borrar';
  END IF;
  RAISE NOTICE 'ok · revertir la carga borra todo lo que produjo';
END $$;
RESET ROLE;
SELECT set_config('app.empresa_id', '', false) \gset
SET ROLE app;
DO $$ BEGIN
  PERFORM historico.revertir_carga(current_setting('pruebas.carga_a')::bigint);
  RAISE 'se revirtió sin empresa en el contexto';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · sin empresa no se revierte nada';
END $$;
RESET ROLE;

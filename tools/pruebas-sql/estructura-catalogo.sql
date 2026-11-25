-- Pruebas de las defensas de estructura y catálogo (H2). Corre después de seguridad.sql, sobre
-- sus empresas A y B. Cada bloque lanza una excepción si la regla no se cumple.
\set ON_ERROR_STOP on
\set QUIET on

-- ============ Normalización ============
DO $$ BEGIN
  IF compartido.normalizar('  Cemento   gris ') <> compartido.normalizar('CEMENTO GRÍS') THEN
    RAISE 'normalizar no unifica espacios, acentos y mayúsculas';
  END IF;
  IF compartido.normalizar('Ñandú  Áéíóú') <> 'NANDU AEIOU' THEN RAISE 'normalizar con Ñ y acentos: %', compartido.normalizar('Ñandú  Áéíóú'); END IF;
  IF compartido.normalizar('   ') IS NOT NULL THEN RAISE 'texto vacío debería ser NULL'; END IF;
  RAISE NOTICE 'ok · normalizar quita acentos, unifica espacios y mayúsculas';
END $$;

-- ============ Datos de B (como migrador) ============
RESET ROLE;
SET ROLE migrador;
INSERT INTO estructura.cliente (empresa_id, nombre)
SELECT id, 'Cliente de B' FROM organizacion.empresa WHERE rfc = 'BBB010101BB1';
INSERT INTO estructura.prototipo (empresa_id, clave, nombre)
SELECT id, 'P-99', 'Prototipo de B' FROM organizacion.empresa WHERE rfc = 'BBB010101BB1';
RESET ROLE;
-- Los bloques DO no ven variables de psql: los ids de B se pasan como ajustes de la sesión.
SELECT set_config('pruebas.cliente_b', (SELECT id::text FROM estructura.cliente WHERE nombre = 'Cliente de B'), false) \gset
SELECT set_config('pruebas.prototipo_b', (SELECT id::text FROM estructura.prototipo WHERE clave = 'P-99'), false) \gset

-- ============ Como app en la empresa A ============
SELECT set_config('app.empresa_id', (SELECT id::text FROM organizacion.empresa WHERE rfc = 'AAA010101AA1'), false) \gset
SELECT set_config('app.usuario_id', (SELECT id::text FROM organizacion.usuario WHERE usuario = 'a.admin'), false) \gset
SET ROLE app;

INSERT INTO estructura.cliente (empresa_id, nombre, rfc)
VALUES (current_setting('app.empresa_id')::bigint, 'Ruba Desarrollos', 'RDE010101AB1'),
       (current_setting('app.empresa_id')::bigint, 'Gobierno del Estado', NULL);
INSERT INTO estructura.frente (empresa_id, cliente_id, clave, nombre)
SELECT empresa_id, id, 'CORCELES', 'Corceles Fraccionamiento' FROM estructura.cliente WHERE nombre = 'Ruba Desarrollos';
INSERT INTO estructura.contrato (empresa_id, cliente_id, frente_id, codigo, numero_obra, inicio, fin, monto)
SELECT f.empresa_id, f.cliente_id, f.id, '60-KZ7-MF6-C002-2026', 'C1269209', '2026-07-12', '2027-03-28', 4882033.11
  FROM estructura.frente f WHERE f.clave = 'CORCELES';
INSERT INTO estructura.contrato (empresa_id, cliente_id, frente_id, codigo)
SELECT f.empresa_id, f.cliente_id, f.id, '60-KZ7-ME6-C002-2026' FROM estructura.frente f WHERE f.clave = 'CORCELES';
INSERT INTO estructura.prototipo (empresa_id, clave, nombre)
VALUES (current_setting('app.empresa_id')::bigint, 'P-69', 'Prototipo 69');

DO $$ BEGIN
  IF (SELECT count(*) FROM estructura.cliente) <> 2 THEN RAISE 'A debería ver solo sus 2 clientes'; END IF;
  IF EXISTS (SELECT 1 FROM estructura.prototipo WHERE clave = 'P-99') THEN RAISE 'A ve prototipos de B'; END IF;
  RAISE NOTICE 'ok · estructura: cada empresa ve solo lo suyo';
END $$;

-- Ligar a un cliente de B conociendo su id: la FK compuesta lo impide aunque la FK no aplique RLS
DO $$ BEGIN
  INSERT INTO estructura.contrato (empresa_id, cliente_id, codigo)
  VALUES (current_setting('app.empresa_id')::bigint, current_setting('pruebas.cliente_b')::bigint, 'X-1');
  RAISE 'se ligó un contrato a un cliente de otra empresa';
EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'ok · un contrato no se liga a un cliente de otra empresa';
END $$;

-- Frente de un cliente y contrato de otro
DO $$ BEGIN
  INSERT INTO estructura.contrato (empresa_id, cliente_id, frente_id, codigo)
  SELECT current_setting('app.empresa_id')::bigint,
         (SELECT id FROM estructura.cliente WHERE nombre = 'Gobierno del Estado'),
         (SELECT id FROM estructura.frente WHERE clave = 'CORCELES'), 'X-2';
  RAISE 'se aceptó un contrato con frente de otro cliente';
EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'ok · el frente del contrato es del mismo cliente';
END $$;

-- Código con diagonal: se debe guardar normalizado
DO $$ BEGIN
  INSERT INTO estructura.contrato (empresa_id, cliente_id, codigo)
  SELECT empresa_id, id, '60-KBU-MEK/C001/2026' FROM estructura.cliente WHERE nombre = 'Gobierno del Estado';
  RAISE 'se aceptó un código con /';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · el código del contrato se guarda normalizado (sin /)';
END $$;

-- ============ Unidades de obra ============
INSERT INTO estructura.unidad_obra (empresa_id, contrato_id, frente_id, tipo_id, clave, manzana, lote, prototipo_id)
SELECT c.empresa_id, c.id, c.frente_id, 1, 'M20-L' || n, '20', n::text, (SELECT id FROM estructura.prototipo WHERE clave = 'P-69')
  FROM estructura.contrato c, generate_series(29, 31) n WHERE c.codigo = '60-KZ7-MF6-C002-2026';

DO $$ BEGIN
  INSERT INTO estructura.unidad_obra (empresa_id, contrato_id, frente_id, tipo_id, clave, manzana, lote)
  SELECT c.empresa_id, c.id, c.frente_id, 1, 'M20-L29', '20', '29' FROM estructura.contrato c
   WHERE c.codigo = '60-KZ7-ME6-C002-2026';
  RAISE 'el mismo lote quedó en dos contratos del frente';
EXCEPTION WHEN unique_violation THEN
  RAISE NOTICE 'ok · un lote no se repite en dos contratos del mismo frente';
END $$;

DO $$ BEGIN
  INSERT INTO estructura.unidad_obra (empresa_id, contrato_id, frente_id, tipo_id, clave, manzana, lote)
  SELECT c.empresa_id, c.id, NULL, 1, 'M21-L1', '21', '1' FROM estructura.contrato c
   WHERE c.codigo = '60-KZ7-ME6-C002-2026';
  IF NOT EXISTS (SELECT 1 FROM estructura.unidad_obra WHERE clave = 'M21-L1') THEN RAISE 'no se insertó'; END IF;
  DELETE FROM estructura.unidad_obra WHERE clave = 'M21-L1';
  RAISE 'app pudo borrar una unidad de obra';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · las unidades de obra no se borran (se desactivan)';
END $$;

DO $$ BEGIN
  INSERT INTO estructura.unidad_obra (empresa_id, contrato_id, frente_id, tipo_id, clave, manzana, lote)
  SELECT c.empresa_id, c.id, c.frente_id, 1, 'M20-L40', '20', '41' FROM estructura.contrato c
   WHERE c.codigo = '60-KZ7-MF6-C002-2026';
  RAISE 'se aceptó una clave que no corresponde a manzana y lote';
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'ok · la clave del lote coincide con su manzana y lote';
END $$;

DO $$ BEGIN
  INSERT INTO estructura.unidad_obra (empresa_id, contrato_id, frente_id, tipo_id, clave, manzana, lote, prototipo_id)
  SELECT c.empresa_id, c.id, c.frente_id, 1, 'M20-L50', '20', '50', current_setting('pruebas.prototipo_b')::bigint
    FROM estructura.contrato c WHERE c.codigo = '60-KZ7-MF6-C002-2026';
  RAISE 'se usó un prototipo de otra empresa';
EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'ok · el prototipo del lote es de la misma empresa';
END $$;

-- ============ Paquetes y equivalencias ============
INSERT INTO estructura.paquete (empresa_id, cliente_id, clave, nombre)
SELECT empresa_id, id, '1', 'Cimentación' FROM estructura.cliente WHERE nombre = 'Ruba Desarrollos';
INSERT INTO estructura.paquete (empresa_id, clave, nombre, unidad)
VALUES (current_setting('app.empresa_id')::bigint, '1', 'Cimentación', NULL);
INSERT INTO estructura.paquete (empresa_id, padre_id, clave, nombre, unidad)
SELECT empresa_id, id, '1.1', 'Trazo, excavación y cimbra', 'Lote' FROM estructura.paquete
 WHERE clave = '1' AND cliente_id IS NULL;

INSERT INTO estructura.paquete_equivalencia (empresa_id, paquete_cliente_id, paquete_propio_id)
SELECT c.empresa_id, c.id, p.id FROM estructura.paquete c, estructura.paquete p
 WHERE c.clave = '1' AND c.cliente_id IS NOT NULL AND p.clave = '1' AND p.cliente_id IS NULL;

DO $$ BEGIN
  INSERT INTO estructura.paquete_equivalencia (empresa_id, paquete_cliente_id, paquete_propio_id)
  SELECT p.empresa_id, p.id, h.id FROM estructura.paquete p, estructura.paquete h
   WHERE p.clave = '1' AND p.cliente_id IS NULL AND h.clave = '1.1';
  RAISE 'se aceptó una equivalencia entre dos paquetes propios';
EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'ok · la equivalencia es siempre paquete del cliente ↔ paquete propio';
END $$;

DO $$ BEGIN
  INSERT INTO estructura.paquete (empresa_id, cliente_id, padre_id, clave, nombre)
  SELECT c.empresa_id, c.cliente_id, p.id, '1.9', 'Hijo cruzado'
    FROM estructura.paquete c, estructura.paquete p
   WHERE c.clave = '1' AND c.cliente_id IS NOT NULL AND p.clave = '1' AND p.cliente_id IS NULL;
  RAISE 'un paquete del cliente quedó bajo un paquete propio';
EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'ok · el padre de un paquete es del mismo catálogo';
END $$;

DO $$ BEGIN
  INSERT INTO estructura.paquete (empresa_id, clave, nombre)
  VALUES (current_setting('app.empresa_id')::bigint, '1', 'Otra cimentación');
  RAISE 'se repitió la clave de paquete en el catálogo propio';
EXCEPTION WHEN unique_violation THEN
  RAISE NOTICE 'ok · la clave del paquete es única en su catálogo (también con cliente NULL)';
END $$;

-- ============ Bitácora y borrado ============
UPDATE estructura.contrato SET monto = 5000000, version = version + 1 WHERE codigo = '60-KZ7-MF6-C002-2026';
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM compartido.bitacora
                  WHERE esquema = 'estructura' AND tabla = 'contrato' AND operacion = 'UPDATE'
                    AND (antes ->> 'monto')::numeric = 4882033.11 AND (despues ->> 'monto')::numeric = 5000000
                    AND usuario_id = current_setting('app.usuario_id')::bigint) THEN
    RAISE 'el cambio de monto no quedó en bitácora';
  END IF;
  IF EXISTS (SELECT 1 FROM compartido.bitacora WHERE despues ? 'nombre_normal') THEN
    RAISE 'la bitácora guardó columnas derivadas';
  END IF;
  RAISE NOTICE 'ok · los cambios de estructura quedan en bitácora con el usuario';
END $$;

DO $$ BEGIN
  DELETE FROM estructura.contrato;
  RAISE 'app pudo borrar contratos';
EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'ok · los contratos no se borran';
END $$;

-- ============ Catálogo ============
DO $$ BEGIN
  IF (SELECT count(*) FROM catalogo.unidad_medida WHERE empresa_id IS NULL) < 13 THEN
    RAISE 'la empresa no ve las unidades del sistema';
  END IF;
  UPDATE catalogo.unidad_medida SET nombre = 'Pieza hackeada' WHERE clave = 'PZA';
  RAISE 'app pudo modificar una unidad del sistema';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'ok · las unidades del sistema se ven pero no se modifican (%)', SQLERRM;
END $$;

INSERT INTO catalogo.unidad_medida (empresa_id, clave, nombre, magnitud)
VALUES (current_setting('app.empresa_id')::bigint, 'JGO', 'Juego', 'conteo');
INSERT INTO catalogo.categoria (empresa_id, nombre) VALUES (current_setting('app.empresa_id')::bigint, 'Cementantes');
INSERT INTO catalogo.material (empresa_id, clave, nombre, categoria_id, unidad_control_id, precio_referencia)
SELECT current_setting('app.empresa_id')::bigint, v.clave, v.nombre,
       (SELECT id FROM catalogo.categoria WHERE nombre = 'Cementantes'),
       (SELECT id FROM catalogo.unidad_medida WHERE clave = v.unidad AND empresa_id IS NULL), v.precio
  FROM (VALUES ('001', 'Cemento gris 50 kg', 'SAC', 210.5),
               ('002', 'Adeblock plus AP Cemix 40 kg (acabado final)', 'SAC', 180),
               ('003', 'Abrazadera omega de 3/4"', 'PZA', 3.2),
               ('004', 'Cable THW cal. 12', 'ROL', 1450),
               ('005', 'Lavamanos blanco', 'PZA', 520)) AS v(clave, nombre, unidad, precio);

DO $$ BEGIN
  INSERT INTO catalogo.material (empresa_id, clave, nombre, unidad_control_id)
  SELECT current_setting('app.empresa_id')::bigint, '006', '  CEMENTO  GRÍS 50 KG ', id
    FROM catalogo.unidad_medida WHERE clave = 'SAC' AND empresa_id IS NULL;
  RAISE 'se dio de alta el mismo material escrito distinto';
EXCEPTION WHEN unique_violation THEN
  RAISE NOTICE 'ok · un material no se duplica por acentos, espacios o mayúsculas';
END $$;

DO $$ BEGIN
  IF (SELECT clave FROM catalogo.material
       WHERE nombre_normal LIKE '%' || compartido.normalizar('cemento') || '%'
       ORDER BY similarity(nombre_normal, compartido.normalizar('cemento gris')) DESC LIMIT 1) <> '001' THEN
    RAISE 'la búsqueda por nombre parcial no encuentra el cemento';
  END IF;
  RAISE NOTICE 'ok · búsqueda de material por nombre parcial con pg_trgm';
END $$;

INSERT INTO catalogo.conversion_unidad (empresa_id, material_id, origen_id, destino_id, factor)
SELECT current_setting('app.empresa_id')::bigint, m.id,
       (SELECT id FROM catalogo.unidad_medida WHERE clave = 'CJA' AND empresa_id IS NULL),
       m.unidad_control_id, 4
  FROM catalogo.material m WHERE m.clave = '005';

-- B no ve lo de A
RESET ROLE;
SELECT set_config('app.empresa_id', (SELECT id::text FROM organizacion.empresa WHERE rfc = 'BBB010101BB1'), false) \gset
SET ROLE app;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM catalogo.material) THEN RAISE 'B ve materiales de A'; END IF;
  IF EXISTS (SELECT 1 FROM catalogo.unidad_medida WHERE clave = 'JGO') THEN RAISE 'B ve unidades propias de A'; END IF;
  IF EXISTS (SELECT 1 FROM catalogo.conversion_unidad WHERE material_id IS NOT NULL) THEN RAISE 'B ve conversiones de A'; END IF;
  IF NOT EXISTS (SELECT 1 FROM catalogo.conversion_unidad WHERE material_id IS NULL) THEN RAISE 'B no ve conversiones del sistema'; END IF;
  IF EXISTS (SELECT 1 FROM estructura.contrato) THEN RAISE 'B ve contratos de A'; END IF;
  RAISE NOTICE 'ok · catálogo: cada empresa ve lo suyo y lo del sistema';
END $$;

-- Sin contexto: solo lo del sistema
RESET ROLE;
SELECT set_config('app.empresa_id', '', false) \gset
SET ROLE app;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM catalogo.material) OR EXISTS (SELECT 1 FROM estructura.cliente) THEN
    RAISE 'sin empresa se ven datos de negocio';
  END IF;
  RAISE NOTICE 'ok · sin empresa en el contexto no se ven materiales ni clientes';
END $$;
RESET ROLE;

-- V005 · Catálogo: unidades de medida, conversiones, categorías, materiales, proveedores y
-- clasificaciones de costo.
--
-- Las unidades de medida y las conversiones generales (1 KG = 1000 G) son del sistema y las ven
-- todas las empresas (empresa_id NULL); cada empresa puede agregar las suyas, y las conversiones
-- de un material (1 CJA de lavamanos = 4 PZA) siempre son de su empresa.

-- Búsqueda de materiales por nombre parcial ("cement gris" encuentra "Cemento gris 50 kg").
-- pg_trgm es una extensión confiable: la crea el dueño de la base (migrador) sin superusuario.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE SCHEMA catalogo;
GRANT USAGE ON SCHEMA catalogo TO app;

-- ---------------------------------------------------------------------------------------------
-- Unidades de medida
-- ---------------------------------------------------------------------------------------------
CREATE TABLE catalogo.unidad_medida (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id   bigint      REFERENCES organizacion.empresa,      -- NULL: del sistema
  clave        text        NOT NULL CHECK (clave ~ '^[A-Z0-9]{1,6}$'),
  nombre       text        NOT NULL CHECK (length(btrim(nombre)) BETWEEN 1 AND 40),
  magnitud     text        NOT NULL
               CHECK (magnitud IN ('conteo', 'masa', 'volumen', 'longitud', 'superficie')),
  fraccionable boolean     NOT NULL DEFAULT false,               -- admite 0.5 (kilo sí, pieza no)
  activa       boolean     NOT NULL DEFAULT true,
  version      integer     NOT NULL DEFAULT 0,
  creada       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_unidad_sistema ON catalogo.unidad_medida (clave) WHERE empresa_id IS NULL;
CREATE UNIQUE INDEX ux_unidad_empresa ON catalogo.unidad_medida (empresa_id, clave)
  WHERE empresa_id IS NOT NULL;

-- Las 22 formas de escribir unidades del Excel de almacén caben en estas 13 (más m² y m³).
INSERT INTO catalogo.unidad_medida (clave, nombre, magnitud, fraccionable) VALUES
  ('PZA', 'Pieza', 'conteo', false),
  ('CJA', 'Caja', 'conteo', false),
  ('PAQ', 'Paquete', 'conteo', false),
  ('ROL', 'Rollo', 'conteo', false),
  ('SAC', 'Saco', 'conteo', false),
  ('BOL', 'Bolsa', 'conteo', false),
  ('CUB', 'Cubeta', 'conteo', false),
  ('TAM', 'Tambo', 'conteo', false),
  ('TRA', 'Tramo', 'conteo', false),
  ('KG', 'Kilogramo', 'masa', true),
  ('G', 'Gramo', 'masa', true),
  ('L', 'Litro', 'volumen', true),
  ('M', 'Metro', 'longitud', true),
  ('M2', 'Metro cuadrado', 'superficie', true),
  ('M3', 'Metro cúbico', 'volumen', true);

-- ---------------------------------------------------------------------------------------------
-- Categorías de material (eléctrico, plomería, acabados…)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE catalogo.categoria (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id    bigint      NOT NULL REFERENCES organizacion.empresa,
  nombre        text        NOT NULL CHECK (length(btrim(nombre)) BETWEEN 1 AND 80),
  nombre_normal text        GENERATED ALWAYS AS (compartido.normalizar(nombre)) STORED,
  activa        boolean     NOT NULL DEFAULT true,
  version       integer     NOT NULL DEFAULT 0,
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, nombre_normal)
);

-- ---------------------------------------------------------------------------------------------
-- Materiales. La unidad de control es en la que se lleva la existencia y el costo; se captura
-- en cualquier unidad con conversión. El precio de referencia es sin IVA y solo orienta: el
-- costo real sale del promedio ponderado de las recepciones (H4).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE catalogo.material (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id        bigint        NOT NULL REFERENCES organizacion.empresa,
  clave             text          NOT NULL CHECK (clave ~ '^[A-Z0-9][A-Z0-9-]{0,19}$'),
  nombre            text          NOT NULL CHECK (length(btrim(nombre)) BETWEEN 1 AND 200),
  nombre_normal     text          GENERATED ALWAYS AS (compartido.normalizar(nombre)) STORED,
  categoria_id      bigint,
  unidad_control_id bigint        NOT NULL REFERENCES catalogo.unidad_medida,
  precio_referencia numeric(16,6) CHECK (precio_referencia >= 0),
  activo            boolean       NOT NULL DEFAULT true,
  version           integer       NOT NULL DEFAULT 0,
  creado            timestamptz   NOT NULL DEFAULT now(),
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, clave),
  UNIQUE (empresa_id, nombre_normal),
  FOREIGN KEY (empresa_id, categoria_id) REFERENCES catalogo.categoria (empresa_id, id)
);
CREATE INDEX ix_material_nombre_trgm ON catalogo.material USING gin (nombre_normal gin_trgm_ops);

-- ---------------------------------------------------------------------------------------------
-- Conversiones: 1 origen = factor × destino. Del sistema (sin empresa ni material), de la
-- empresa (sin material) o de un material.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE catalogo.conversion_unidad (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id  bigint        REFERENCES organizacion.empresa,
  material_id bigint,
  origen_id   bigint        NOT NULL REFERENCES catalogo.unidad_medida,
  destino_id  bigint        NOT NULL REFERENCES catalogo.unidad_medida,
  factor      numeric(18,8) NOT NULL CHECK (factor > 0),
  version     integer       NOT NULL DEFAULT 0,
  CHECK (origen_id <> destino_id),
  CHECK (material_id IS NULL OR empresa_id IS NOT NULL),
  UNIQUE NULLS NOT DISTINCT (empresa_id, material_id, origen_id, destino_id),
  FOREIGN KEY (empresa_id, material_id) REFERENCES catalogo.material (empresa_id, id)
);

INSERT INTO catalogo.conversion_unidad (origen_id, destino_id, factor)
SELECT o.id, d.id, 1000 FROM catalogo.unidad_medida o, catalogo.unidad_medida d
 WHERE o.empresa_id IS NULL AND d.empresa_id IS NULL AND o.clave = 'KG' AND d.clave = 'G';
INSERT INTO catalogo.conversion_unidad (origen_id, destino_id, factor)
SELECT o.id, d.id, 1000 FROM catalogo.unidad_medida o, catalogo.unidad_medida d
 WHERE o.empresa_id IS NULL AND d.empresa_id IS NULL AND o.clave = 'M3' AND d.clave = 'L';

-- ---------------------------------------------------------------------------------------------
-- Proveedores. Tasa de IVA por defecto: la usa la migración cuando una recepción no la trae.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE catalogo.proveedor (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id    bigint       NOT NULL REFERENCES organizacion.empresa,
  nombre        text         NOT NULL CHECK (length(btrim(nombre)) BETWEEN 1 AND 160),
  nombre_normal text         GENERATED ALWAYS AS (compartido.normalizar(nombre)) STORED,
  rfc           text         CHECK (rfc ~ '^[A-ZÑ&]{3,4}[0-9]{6}[A-Z0-9]{3}$'),
  contacto      text         CHECK (length(contacto) <= 120),
  telefono      text         CHECK (telefono ~ '^[0-9 +()-]{7,20}$'),
  correo        text         CHECK (correo ~ '^[^@\s]+@[^@\s]+$'),
  tasa_iva      numeric(5,4) CHECK (tasa_iva BETWEEN 0 AND 0.16),
  dias_credito  smallint     CHECK (dias_credito BETWEEN 0 AND 365),
  activo        boolean      NOT NULL DEFAULT true,
  version       integer      NOT NULL DEFAULT 0,
  creado        timestamptz  NOT NULL DEFAULT now(),
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, nombre_normal)
);
CREATE UNIQUE INDEX ux_proveedor_rfc ON catalogo.proveedor (empresa_id, rfc) WHERE rfc IS NOT NULL;
CREATE INDEX ix_proveedor_nombre_trgm ON catalogo.proveedor USING gin (nombre_normal gin_trgm_ops);

-- ---------------------------------------------------------------------------------------------
-- Clasificación de costo: la "Razón" de cada salida. Catálogo fijo del sistema; los nombres son
-- los que ya usa el almacén. Solo Programa consume el presupuesto del lote.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE catalogo.clasificacion_costo (
  id               smallint PRIMARY KEY,
  clave            text     NOT NULL UNIQUE CHECK (clave ~ '^[A-Z_]{2,30}$'),
  nombre           text     NOT NULL,
  consume_programa boolean  NOT NULL
);
INSERT INTO catalogo.clasificacion_costo (id, clave, nombre, consume_programa) VALUES
  (1, 'PROGRAMA', 'Programa', true),
  (2, 'GARANTIA_CLIENTE', 'Garantía Ruba', false),
  (3, 'GARANTIA_PROPIA', 'Garantía RM', false),
  (4, 'RETRABAJO', 'Retrabajo', false),
  (5, 'ESCRITURABLES', 'Escriturables', false),
  (6, 'ENTREGA', 'Entrega', false),
  (7, 'RESCATE', 'Rescate', false),
  (8, 'REEMPLAZO_FALLA', 'Reemplazo por falla', false);
GRANT SELECT ON catalogo.clasificacion_costo TO app;

-- ---------------------------------------------------------------------------------------------
-- Seguridad por empresa. Unidades y conversiones del sistema se ven desde todas las empresas
-- pero la app no puede modificarlas: WITH CHECK exige la empresa en curso.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['unidad_medida', 'conversion_unidad'] LOOP
    EXECUTE format('ALTER TABLE catalogo.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE catalogo.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY por_empresa ON catalogo.%I'
                   ' USING (empresa_id IS NULL OR empresa_id = compartido.empresa_actual())'
                   ' WITH CHECK (empresa_id = compartido.empresa_actual())', t);
  END LOOP;
  FOREACH t IN ARRAY ARRAY['categoria', 'material', 'proveedor'] LOOP
    EXECUTE format('ALTER TABLE catalogo.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE catalogo.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY por_empresa ON catalogo.%I'
                   ' USING (empresa_id = compartido.empresa_actual())'
                   ' WITH CHECK (empresa_id = compartido.empresa_actual())', t);
  END LOOP;
END
$$;

GRANT SELECT, INSERT, UPDATE ON catalogo.unidad_medida, catalogo.conversion_unidad,
  catalogo.categoria, catalogo.material, catalogo.proveedor TO app;

-- La bitácora se activa después de sembrar: los renglones del sistema no tienen empresa.
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON catalogo.unidad_medida
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON catalogo.conversion_unidad
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON catalogo.categoria
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id', 'nombre_normal');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON catalogo.material
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id', 'nombre_normal');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON catalogo.proveedor
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id', 'nombre_normal');

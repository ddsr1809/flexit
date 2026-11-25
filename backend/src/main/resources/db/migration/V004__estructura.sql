-- V004 · Estructura del negocio: Cliente → Frente → Contrato → Unidad de obra → Paquete.
--
-- Todo costo e ingreso se cuelga del contrato. Cada tabla con empresa_id lleva RLS, permisos
-- explícitos para `app` y bitácora (regla 10 de CLAUDE.md). Las llaves foráneas son compuestas
-- con empresa_id: PostgreSQL revisa las FK sin aplicar RLS, así que una FK simple dejaría ligar
-- un registro a un cliente o contrato de otra empresa con solo conocer su id.
--
-- Nada se borra: clientes, frentes, contratos, lotes y paquetes se desactivan o cambian de
-- estado. Solo se borran las asignaciones (residentes de un contrato, equivalencias).

CREATE SCHEMA estructura;
GRANT USAGE ON SCHEMA estructura TO app;

-- ---------------------------------------------------------------------------------------------
-- Cliente: quien paga la obra (Ruba, gobierno, cadena), persona física o moral.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE estructura.cliente (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id    bigint      NOT NULL REFERENCES organizacion.empresa,
  nombre        text        NOT NULL CHECK (length(btrim(nombre)) BETWEEN 1 AND 160),
  nombre_normal text        GENERATED ALWAYS AS (compartido.normalizar(nombre)) STORED,
  rfc           text        CHECK (rfc ~ '^[A-ZÑ&]{3,4}[0-9]{6}[A-Z0-9]{3}$'),
  activo        boolean     NOT NULL DEFAULT true,
  version       integer     NOT NULL DEFAULT 0,
  creado        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, nombre_normal)
);
CREATE UNIQUE INDEX ux_cliente_rfc ON estructura.cliente (empresa_id, rfc) WHERE rfc IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- Frente: fraccionamiento u obra de un cliente (Corceles, Quinta Granada, Palermo). La clave
-- corta es la que encabeza la clave de lote al importar: CORCELES-M16-L34.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE estructura.frente (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id    bigint      NOT NULL,
  cliente_id    bigint      NOT NULL,
  clave         text        NOT NULL CHECK (clave ~ '^[A-Z0-9]{2,12}$'),
  nombre        text        NOT NULL CHECK (length(btrim(nombre)) BETWEEN 1 AND 160),
  activo        boolean     NOT NULL DEFAULT true,
  version       integer     NOT NULL DEFAULT 0,
  creado        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, id, cliente_id),
  UNIQUE (empresa_id, clave),
  FOREIGN KEY (empresa_id, cliente_id) REFERENCES estructura.cliente (empresa_id, id)
);

-- ---------------------------------------------------------------------------------------------
-- Contrato: el centro de costo. Código como lo emite el cliente, normalizado (la "/" de algunos
-- códigos del SUA se guarda como "-"): 60-KZ7-MF6-C002-2026. El frente es opcional (obra pública).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE estructura.contrato (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id   bigint        NOT NULL,
  cliente_id   bigint        NOT NULL,
  frente_id    bigint,
  codigo       text          NOT NULL
               CHECK (codigo ~ '^[A-Z0-9]+(-[A-Z0-9]+)*$' AND length(codigo) <= 40),
  numero_obra  text          CHECK (numero_obra ~ '^[A-Z0-9-]{1,20}$'),
  descripcion  text          CHECK (length(descripcion) <= 300),
  inicio       date,
  fin          date,
  monto        numeric(16,2) CHECK (monto >= 0),
  estado       text          NOT NULL DEFAULT 'activo'
               CHECK (estado IN ('activo', 'terminado', 'cancelado')),
  version      integer       NOT NULL DEFAULT 0,
  creado       timestamptz   NOT NULL DEFAULT now(),
  CHECK (fin IS NULL OR inicio IS NULL OR fin >= inicio),
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, id, frente_id),
  UNIQUE (empresa_id, codigo),
  FOREIGN KEY (empresa_id, cliente_id) REFERENCES estructura.cliente (empresa_id, id),
  -- Si tiene frente, el frente es del mismo cliente.
  FOREIGN KEY (empresa_id, frente_id, cliente_id)
    REFERENCES estructura.frente (empresa_id, id, cliente_id)
);
CREATE INDEX ix_contrato_frente ON estructura.contrato (empresa_id, frente_id);

-- Alcance: qué contratos ve un usuario cuyo rol no ve todos (el residente). Vive en estructura,
-- no en organizacion, porque organizacion no puede depender de estructura.
CREATE TABLE estructura.contrato_usuario (
  empresa_id  bigint NOT NULL,
  contrato_id bigint NOT NULL,
  usuario_id  bigint NOT NULL,
  PRIMARY KEY (contrato_id, usuario_id),
  FOREIGN KEY (empresa_id, contrato_id) REFERENCES estructura.contrato (empresa_id, id),
  FOREIGN KEY (empresa_id, usuario_id) REFERENCES organizacion.usuario (empresa_id, id)
);
CREATE INDEX ix_contrato_usuario_usuario ON estructura.contrato_usuario (empresa_id, usuario_id);

-- ---------------------------------------------------------------------------------------------
-- Tipo de unidad de obra. Catálogo fijo del sistema, igual para todas las empresas.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE estructura.tipo_unidad (
  id               smallint PRIMARY KEY,
  clave            text     NOT NULL UNIQUE CHECK (clave ~ '^[A-Z_]{2,20}$'),
  nombre           text     NOT NULL,
  usa_manzana_lote boolean  NOT NULL
);
INSERT INTO estructura.tipo_unidad (id, clave, nombre, usa_manzana_lote) VALUES
  (1, 'LOTE', 'Lote', true),
  (2, 'EDIFICIO', 'Edificio', false),
  (3, 'TRAMO', 'Tramo', false),
  (4, 'AREA_COMUN', 'Área común', false),
  (5, 'SIN_ASIGNAR', 'Sin asignar', false);
GRANT SELECT ON estructura.tipo_unidad TO app;

-- ---------------------------------------------------------------------------------------------
-- Prototipo de vivienda (P-45, P-65, P-69…).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE estructura.prototipo (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id  bigint      NOT NULL REFERENCES organizacion.empresa,
  clave       text        NOT NULL CHECK (clave ~ '^[A-Z0-9][A-Z0-9-]{0,19}$'),
  nombre      text        NOT NULL CHECK (length(btrim(nombre)) BETWEEN 1 AND 120),
  descripcion text        CHECK (length(descripcion) <= 300),
  activo      boolean     NOT NULL DEFAULT true,
  version     integer     NOT NULL DEFAULT 0,
  creado      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, clave)
);

-- ---------------------------------------------------------------------------------------------
-- Unidad de obra: lote, edificio, tramo. Un lote pertenece a un solo contrato y su clave no se
-- repite dentro del frente (M16-L34 solo existe una vez en Corceles). frente_id es copia del
-- frente del contrato; la FK compuesta impide que difiera.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE estructura.unidad_obra (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id           bigint        NOT NULL,
  contrato_id          bigint        NOT NULL,
  frente_id            bigint,
  tipo_id              smallint      NOT NULL REFERENCES estructura.tipo_unidad,
  clave                text          NOT NULL
                       CHECK (clave ~ '^[A-Z0-9]+(-[A-Z0-9]+)*$' AND length(clave) <= 30),
  manzana              text          CHECK (manzana ~ '^[0-9]{1,4}[A-Z]?$'),
  lote                 text          CHECK (lote ~ '^[0-9]{1,4}[A-Z]?$'),
  prototipo_id         bigint,
  presupuesto_material numeric(16,2) CHECK (presupuesto_material >= 0),
  activa               boolean       NOT NULL DEFAULT true,
  version              integer       NOT NULL DEFAULT 0,
  creado               timestamptz   NOT NULL DEFAULT now(),
  CHECK ((manzana IS NULL) = (lote IS NULL)),
  CHECK (manzana IS NULL OR clave = 'M' || manzana || '-L' || lote),
  UNIQUE (empresa_id, id),
  UNIQUE (contrato_id, clave),
  FOREIGN KEY (empresa_id, contrato_id) REFERENCES estructura.contrato (empresa_id, id),
  FOREIGN KEY (empresa_id, contrato_id, frente_id)
    REFERENCES estructura.contrato (empresa_id, id, frente_id),
  FOREIGN KEY (empresa_id, prototipo_id) REFERENCES estructura.prototipo (empresa_id, id)
);
CREATE UNIQUE INDEX ux_unidad_frente_clave ON estructura.unidad_obra (empresa_id, frente_id, clave)
  WHERE frente_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- Paquete: la unidad de trabajo con la que se cobra, se paga destajo y se carga material.
-- Con cliente_id es el catálogo de cobro de ese cliente (paquetes Ruba); sin cliente_id es el
-- catálogo propio (destajo RM). Jerárquico: sección → concepto, por padre_id; el padre es del
-- mismo catálogo.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE estructura.paquete (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id    bigint      NOT NULL REFERENCES organizacion.empresa,
  cliente_id    bigint,
  es_cliente    boolean     GENERATED ALWAYS AS (cliente_id IS NOT NULL) STORED,
  padre_id      bigint,
  clave         text        NOT NULL
                CHECK (clave ~ '^[A-Z0-9]+([.-][A-Z0-9]+)*$' AND length(clave) <= 20),
  nombre        text        NOT NULL CHECK (length(btrim(nombre)) BETWEEN 1 AND 160),
  nombre_normal text        GENERATED ALWAYS AS (compartido.normalizar(nombre)) STORED,
  unidad        text        CHECK (length(btrim(unidad)) BETWEEN 1 AND 20),
  orden         integer     NOT NULL DEFAULT 0,
  activo        boolean     NOT NULL DEFAULT true,
  version       integer     NOT NULL DEFAULT 0,
  creado        timestamptz NOT NULL DEFAULT now(),
  CHECK (padre_id IS NULL OR padre_id <> id),
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, id, es_cliente),
  UNIQUE (empresa_id, id, cliente_id),
  UNIQUE NULLS NOT DISTINCT (empresa_id, cliente_id, clave),
  FOREIGN KEY (empresa_id, cliente_id) REFERENCES estructura.cliente (empresa_id, id),
  FOREIGN KEY (empresa_id, padre_id) REFERENCES estructura.paquete (empresa_id, id),
  -- El padre está del mismo lado (cliente o propio) y, si es de cliente, del mismo cliente.
  FOREIGN KEY (empresa_id, padre_id, es_cliente)
    REFERENCES estructura.paquete (empresa_id, id, es_cliente),
  FOREIGN KEY (empresa_id, padre_id, cliente_id)
    REFERENCES estructura.paquete (empresa_id, id, cliente_id)
);
CREATE INDEX ix_paquete_padre ON estructura.paquete (empresa_id, padre_id);

-- Equivalencia paquete del cliente ↔ paquete propio (N:M). Las columnas constantes lado_* hacen
-- que la FK compuesta exija un paquete de cliente a la izquierda y uno propio a la derecha.
CREATE TABLE estructura.paquete_equivalencia (
  empresa_id         bigint  NOT NULL,
  paquete_cliente_id bigint  NOT NULL,
  paquete_propio_id  bigint  NOT NULL,
  lado_cliente       boolean NOT NULL DEFAULT true CHECK (lado_cliente),
  lado_propio        boolean NOT NULL DEFAULT false CHECK (NOT lado_propio),
  PRIMARY KEY (paquete_cliente_id, paquete_propio_id),
  FOREIGN KEY (empresa_id, paquete_cliente_id, lado_cliente)
    REFERENCES estructura.paquete (empresa_id, id, es_cliente),
  FOREIGN KEY (empresa_id, paquete_propio_id, lado_propio)
    REFERENCES estructura.paquete (empresa_id, id, es_cliente)
);
CREATE INDEX ix_equivalencia_propio ON estructura.paquete_equivalencia (empresa_id, paquete_propio_id);

-- Precio unitario de cobro (al cliente) y de destajo (a la cuadrilla) de un paquete en un
-- contrato, opcionalmente por prototipo.
CREATE TABLE estructura.contrato_paquete (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id     bigint        NOT NULL,
  contrato_id    bigint        NOT NULL,
  paquete_id     bigint        NOT NULL,
  prototipo_id   bigint,
  precio_cobro   numeric(16,2) CHECK (precio_cobro >= 0),
  precio_destajo numeric(16,2) CHECK (precio_destajo >= 0),
  version        integer       NOT NULL DEFAULT 0,
  CHECK (precio_cobro IS NOT NULL OR precio_destajo IS NOT NULL),
  UNIQUE NULLS NOT DISTINCT (contrato_id, paquete_id, prototipo_id),
  FOREIGN KEY (empresa_id, contrato_id) REFERENCES estructura.contrato (empresa_id, id),
  FOREIGN KEY (empresa_id, paquete_id) REFERENCES estructura.paquete (empresa_id, id),
  FOREIGN KEY (empresa_id, prototipo_id) REFERENCES estructura.prototipo (empresa_id, id)
);

-- ---------------------------------------------------------------------------------------------
-- Seguridad por empresa, permisos de app y bitácora en todas las tablas con empresa_id.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['cliente', 'frente', 'contrato', 'contrato_usuario', 'prototipo',
                           'unidad_obra', 'paquete', 'paquete_equivalencia', 'contrato_paquete']
  LOOP
    EXECUTE format('ALTER TABLE estructura.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE estructura.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY por_empresa ON estructura.%I'
                   ' USING (empresa_id = compartido.empresa_actual())'
                   ' WITH CHECK (empresa_id = compartido.empresa_actual())', t);
  END LOOP;
END
$$;

GRANT SELECT, INSERT, UPDATE ON estructura.cliente, estructura.frente, estructura.contrato,
  estructura.prototipo, estructura.unidad_obra, estructura.paquete, estructura.contrato_paquete
  TO app;
GRANT SELECT, INSERT, DELETE ON estructura.contrato_usuario, estructura.paquete_equivalencia TO app;

CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON estructura.cliente
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id', 'nombre_normal');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON estructura.frente
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON estructura.contrato
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON estructura.contrato_usuario
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('contrato_id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON estructura.prototipo
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON estructura.unidad_obra
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON estructura.paquete
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id', 'nombre_normal');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON estructura.paquete_equivalencia
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('paquete_cliente_id', 'lado_cliente', 'lado_propio');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON estructura.contrato_paquete
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');

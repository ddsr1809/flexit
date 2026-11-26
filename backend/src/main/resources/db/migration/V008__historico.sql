-- V008 · Histórico del Excel (H5): lo que se capturó en los Excel desde junio de 2026, tal como
-- pasó, ligado a catálogos reales y sin tocar el inventario vivo.
--
-- Por qué no entra al kardex: el Excel tiene existencias negativas, salidas sin lote y fechas mal
-- capturadas. El inventario vivo arranca del conteo físico (H8); el histórico se guarda aparte y
-- los reportes de costo suman las dos fuentes (vistas de abajo).
--
-- Reglas que viven aquí:
--   1. Cada renglón sabe de qué carga, hoja y fila salió, y si entró válido o corregido. Los que
--      quedaron en cuarentena o se descartaron no llegan aquí: se ven en migracion.fila.
--   2. El histórico no se edita: la app solo inserta y un trigger impide UPDATE incluso al dueño.
--      Solo historico.revertir_carga borra, y solo lo de una carga de la empresa en curso.
--   3. RLS por empresa en todas las tablas. Los datos personales del SUA (altas, asistencia,
--      asignaciones) además los filtra el servicio con personas:administrar.
--   4. Sin trigger de bitácora: cada renglón ya es un registro de lo que pasó y la carga que lo
--      trajo está en la bitácora (igual que el kardex).
--
-- La vista reportes.v_costo_unidad une historico.salida con inventario.cargo (ADR-010 y ADR-011).

-- ---------------------------------------------------------------------------------------------
-- Alias de paquetes: "ACABADO EN MUROS INTERIORES " de Salidas → un paquete del catálogo propio.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE migracion.alias DROP CONSTRAINT alias_entidad_check;
ALTER TABLE migracion.alias ADD CONSTRAINT alias_entidad_check
  CHECK (entidad IN ('frente', 'contrato', 'prototipo', 'unidad_medida', 'material', 'proveedor',
                     'persona', 'paquete'));

CREATE SCHEMA historico;
GRANT USAGE ON SCHEMA historico TO app;

-- ---------------------------------------------------------------------------------------------
-- Almacén
-- ---------------------------------------------------------------------------------------------

-- Hoja Entradas. Precio e importe sin IVA y netos de descuento. Una recepción sin precio se
-- valora con el ponderado de las entradas cercanas del material (precio_estimado).
CREATE TABLE historico.entrada (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id      bigint        NOT NULL REFERENCES organizacion.empresa,
  fecha           date          NOT NULL,
  semana_texto    text          CHECK (length(semana_texto) <= 60),
  material_id     bigint        NOT NULL,
  cantidad        numeric(18,6) NOT NULL CHECK (cantidad > 0),
  precio_unitario numeric(16,6) CHECK (precio_unitario >= 0),
  descuento       numeric(16,4) NOT NULL DEFAULT 0 CHECK (descuento >= 0),
  importe         numeric(18,4) CHECK (importe >= 0),
  tasa_iva        numeric(5,4)  NOT NULL CHECK (tasa_iva BETWEEN 0 AND 0.16),
  precio_estimado boolean       NOT NULL DEFAULT false,
  proveedor_id    bigint,
  proveedor_texto text          CHECK (length(proveedor_texto) <= 160),
  folio           text          CHECK (length(folio) <= 60),
  recibe_id       bigint,
  recibe_texto    text          CHECK (length(recibe_texto) <= 160),
  observaciones   text          CHECK (length(observaciones) <= 500),
  calidad         text          NOT NULL CHECK (calidad IN ('valida', 'corregida')),
  carga_id        bigint        NOT NULL,
  numero          integer       NOT NULL,
  hoja            text          NOT NULL,
  fila_excel      integer,
  CHECK ((precio_unitario IS NULL) = (importe IS NULL)),
  CHECK (NOT precio_estimado OR precio_unitario IS NOT NULL),
  UNIQUE (empresa_id, id),
  FOREIGN KEY (empresa_id, material_id) REFERENCES catalogo.material (empresa_id, id),
  FOREIGN KEY (empresa_id, proveedor_id) REFERENCES catalogo.proveedor (empresa_id, id),
  FOREIGN KEY (empresa_id, recibe_id) REFERENCES organizacion.persona (empresa_id, id),
  FOREIGN KEY (empresa_id, carga_id) REFERENCES migracion.carga (empresa_id, id)
);
CREATE INDEX ix_entrada_material ON historico.entrada (empresa_id, material_id, fecha);
CREATE INDEX ix_entrada_carga ON historico.entrada (carga_id);

-- Hoja Salidas. Cantidad con signo: negativa en una devolución. "Ajuste de materiales en lotes" es
-- una reasignación. Sin lote (unidad_obra_id NULL) es la unidad "Sin asignar" de su frente: suma
-- al frente (y al contrato si la manzana lo dice) pero a ningún lote. Sin frente: salió para el
-- propio almacén (herramienta, equipo de seguridad).
CREATE TABLE historico.salida (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id       bigint        NOT NULL REFERENCES organizacion.empresa,
  fecha            date          NOT NULL,
  tipo             text          NOT NULL CHECK (tipo IN ('salida', 'devolucion', 'reasignacion')),
  documento        text          CHECK (length(documento) <= 40),
  folio_excel      text          CHECK (length(folio_excel) <= 40),
  material_id      bigint        NOT NULL,
  cantidad         numeric(18,8) NOT NULL CHECK (cantidad <> 0),
  frente_id        bigint,
  contrato_id      bigint,
  unidad_obra_id   bigint,
  lotes_texto      text          CHECK (length(lotes_texto) <= 120),
  paquete_id       bigint,
  paquete_texto    text          CHECK (length(paquete_texto) <= 160),
  clasificacion_id smallint      REFERENCES catalogo.clasificacion_costo,
  solicitante_id   bigint,
  solicitante_texto text         CHECK (length(solicitante_texto) <= 160),
  entregar_a_texto text          CHECK (length(entregar_a_texto) <= 160),
  entregado        boolean       NOT NULL DEFAULT true,
  costo_unitario   numeric(16,6) CHECK (costo_unitario >= 0),
  importe          numeric(18,4),
  costo_estimado   boolean       NOT NULL DEFAULT false,
  observaciones    text          CHECK (length(observaciones) <= 500),
  calidad          text          NOT NULL CHECK (calidad IN ('valida', 'corregida')),
  carga_id         bigint        NOT NULL,
  numero           integer       NOT NULL,
  hoja             text          NOT NULL,
  fila_excel       integer,
  CHECK (tipo <> 'salida' OR cantidad > 0),
  CHECK (tipo <> 'devolucion' OR cantidad < 0),
  CHECK (unidad_obra_id IS NULL OR contrato_id IS NOT NULL),
  CHECK (contrato_id IS NULL OR frente_id IS NOT NULL),
  CHECK ((costo_unitario IS NULL) = (importe IS NULL)),
  UNIQUE (empresa_id, id),
  FOREIGN KEY (empresa_id, material_id) REFERENCES catalogo.material (empresa_id, id),
  FOREIGN KEY (empresa_id, frente_id) REFERENCES estructura.frente (empresa_id, id),
  FOREIGN KEY (empresa_id, contrato_id, frente_id)
    REFERENCES estructura.contrato (empresa_id, id, frente_id),
  FOREIGN KEY (empresa_id, unidad_obra_id, contrato_id)
    REFERENCES estructura.unidad_obra (empresa_id, id, contrato_id),
  FOREIGN KEY (empresa_id, paquete_id) REFERENCES estructura.paquete (empresa_id, id),
  FOREIGN KEY (empresa_id, solicitante_id) REFERENCES organizacion.persona (empresa_id, id),
  FOREIGN KEY (empresa_id, carga_id) REFERENCES migracion.carga (empresa_id, id)
);
CREATE INDEX ix_salida_unidad ON historico.salida (empresa_id, unidad_obra_id);
CREATE INDEX ix_salida_contrato ON historico.salida (empresa_id, contrato_id);
CREATE INDEX ix_salida_material ON historico.salida (empresa_id, material_id, fecha);
CREATE INDEX ix_salida_carga ON historico.salida (carga_id);

-- Hojas Semanal (origen 'semanal') y Faltante solicitud diaria ('diaria'). La unidad va tal como se
-- pidió ("Paquete" en el 98% del Semanal): no se convierte. Suministrado y faltantes del Semanal
-- son fórmulas del Excel y no se cargan; los del diario sí se capturaron.
CREATE TABLE historico.requisicion_linea (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id        bigint        NOT NULL REFERENCES organizacion.empresa,
  origen            text          NOT NULL CHECK (origen IN ('semanal', 'diaria')),
  fecha             date,
  semana            smallint      CHECK (semana BETWEEN 1 AND 53),
  semana_texto      text          CHECK (length(semana_texto) <= 60),
  urgencia          text          CHECK (length(urgencia) <= 40),
  frente_id         bigint,
  prototipo_texto   text          CHECK (length(prototipo_texto) <= 40),
  contrato_id       bigint,
  unidad_obra_id    bigint,
  lotes_texto       text          CHECK (length(lotes_texto) <= 120),
  material_id       bigint        NOT NULL,
  cantidad          numeric(18,6) CHECK (cantidad >= 0),
  unidad_texto      text          CHECK (length(unidad_texto) <= 40),
  cantidad_pedida   numeric(18,6) CHECK (cantidad_pedida >= 0),
  cantidad_cancelada numeric(18,6) CHECK (cantidad_cancelada >= 0),
  suministrado      numeric(18,6) CHECK (suministrado >= 0),
  paquete_id        bigint,
  paquete_texto     text          CHECK (length(paquete_texto) <= 160),
  solicitante_id    bigint,
  solicitante_texto text          CHECK (length(solicitante_texto) <= 160),
  autorizacion      text          CHECK (length(autorizacion) <= 40),
  estado            text          CHECK (length(estado) <= 60),
  observaciones     text          CHECK (length(observaciones) <= 500),
  calidad           text          NOT NULL CHECK (calidad IN ('valida', 'corregida')),
  carga_id          bigint        NOT NULL,
  numero            integer       NOT NULL,
  hoja              text          NOT NULL,
  fila_excel        integer,
  CHECK (origen <> 'diaria' OR fecha IS NOT NULL),
  CHECK (unidad_obra_id IS NULL OR contrato_id IS NOT NULL),
  UNIQUE (empresa_id, id),
  FOREIGN KEY (empresa_id, frente_id) REFERENCES estructura.frente (empresa_id, id),
  FOREIGN KEY (empresa_id, contrato_id) REFERENCES estructura.contrato (empresa_id, id),
  FOREIGN KEY (empresa_id, unidad_obra_id, contrato_id)
    REFERENCES estructura.unidad_obra (empresa_id, id, contrato_id),
  FOREIGN KEY (empresa_id, material_id) REFERENCES catalogo.material (empresa_id, id),
  FOREIGN KEY (empresa_id, paquete_id) REFERENCES estructura.paquete (empresa_id, id),
  FOREIGN KEY (empresa_id, solicitante_id) REFERENCES organizacion.persona (empresa_id, id),
  FOREIGN KEY (empresa_id, carga_id) REFERENCES migracion.carga (empresa_id, id)
);
CREATE INDEX ix_requisicion_unidad ON historico.requisicion_linea (empresa_id, unidad_obra_id);
CREATE INDEX ix_requisicion_carga ON historico.requisicion_linea (carga_id);

-- Hoja Préstamo Herramientas-Material. Todos los préstamos, devueltos o no; los abiertos pasan a
-- inventario.prestamo como préstamos vivos en el corte (H8, issue 54). La herramienta puede no ser
-- un material del catálogo todavía (decisión pendiente de H4): entonces queda solo el texto.
CREATE TABLE historico.prestamo (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id        bigint        NOT NULL REFERENCES organizacion.empresa,
  fecha             date,
  material_id       bigint,
  articulo          text          NOT NULL CHECK (length(btrim(articulo)) BETWEEN 1 AND 200),
  cantidad          numeric(14,4) NOT NULL CHECK (cantidad > 0),
  unidad_texto      text          CHECK (length(unidad_texto) <= 40),
  frente_id         bigint,
  lotes_texto       text          CHECK (length(lotes_texto) <= 120),
  solicitante_id    bigint,
  solicitante_texto text          CHECK (length(solicitante_texto) <= 160),
  entregar_a_id     bigint,
  entregar_a_texto  text          CHECK (length(entregar_a_texto) <= 160),
  devuelto          boolean       NOT NULL,
  devuelto_en       date,
  observaciones     text          CHECK (length(observaciones) <= 500),
  calidad           text          NOT NULL CHECK (calidad IN ('valida', 'corregida')),
  carga_id          bigint        NOT NULL,
  numero            integer       NOT NULL,
  hoja              text          NOT NULL,
  fila_excel        integer,
  CHECK (devuelto OR devuelto_en IS NULL),
  UNIQUE (empresa_id, id),
  FOREIGN KEY (empresa_id, material_id) REFERENCES catalogo.material (empresa_id, id),
  FOREIGN KEY (empresa_id, frente_id) REFERENCES estructura.frente (empresa_id, id),
  FOREIGN KEY (empresa_id, solicitante_id) REFERENCES organizacion.persona (empresa_id, id),
  FOREIGN KEY (empresa_id, entregar_a_id) REFERENCES organizacion.persona (empresa_id, id),
  FOREIGN KEY (empresa_id, carga_id) REFERENCES migracion.carga (empresa_id, id)
);
CREATE INDEX ix_prestamo_carga ON historico.prestamo (carga_id);

-- ---------------------------------------------------------------------------------------------
-- Personal (SUA). Datos personales: el servicio los muestra solo con personas:administrar.
-- ---------------------------------------------------------------------------------------------

-- IMSS del mes (origen 'mes') y SUA ANTERIOR (origen 'anterior', el mes previo).
CREATE TABLE historico.alta_imss (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id     bigint      NOT NULL REFERENCES organizacion.empresa,
  mes            date        NOT NULL CHECK (extract(day FROM mes) = 1),
  origen         text        NOT NULL CHECK (origen IN ('mes', 'anterior')),
  persona_id     bigint      NOT NULL,
  estatus        text        CHECK (length(estatus) <= 20),
  dias           smallint    CHECK (dias BETWEEN 0 AND 31),
  frente_texto   text        CHECK (length(frente_texto) <= 80),
  contrato_id    bigint,
  contrato_texto text        CHECK (length(contrato_texto) <= 80),
  numero_obra    text        CHECK (length(numero_obra) <= 40),
  calidad        text        NOT NULL CHECK (calidad IN ('valida', 'corregida')),
  carga_id       bigint      NOT NULL,
  numero         integer     NOT NULL,
  hoja           text        NOT NULL,
  fila_excel     integer,
  UNIQUE (empresa_id, id),
  FOREIGN KEY (empresa_id, persona_id) REFERENCES organizacion.persona (empresa_id, id),
  FOREIGN KEY (empresa_id, contrato_id) REFERENCES estructura.contrato (empresa_id, id),
  FOREIGN KEY (empresa_id, carga_id) REFERENCES migracion.carga (empresa_id, id)
);
CREATE INDEX ix_alta_contrato ON historico.alta_imss (empresa_id, contrato_id, mes);
CREATE INDEX ix_alta_carga ON historico.alta_imss (carga_id);

-- Una marca por persona, frente y día: 1 asistió, 0.5 medio día, 0 faltó. "Doble asistencia": la
-- misma persona marcada en dos frentes el mismo día; se cargan ambas.
CREATE TABLE historico.asistencia_dia (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id       bigint       NOT NULL REFERENCES organizacion.empresa,
  fecha            date         NOT NULL,
  persona_id       bigint       NOT NULL,
  frente_id        bigint,
  frente_texto     text         CHECK (length(frente_texto) <= 80),
  tipo_pago        text         CHECK (length(tipo_pago) <= 40),
  ocupacion        text         CHECK (length(ocupacion) <= 120),
  valor            numeric(3,2) NOT NULL CHECK (valor BETWEEN 0 AND 1),
  doble_asistencia boolean      NOT NULL DEFAULT false,
  calidad          text         NOT NULL CHECK (calidad IN ('valida', 'corregida')),
  carga_id         bigint       NOT NULL,
  numero           integer      NOT NULL,
  hoja             text         NOT NULL,
  fila_excel       integer,
  UNIQUE (empresa_id, id),
  FOREIGN KEY (empresa_id, persona_id) REFERENCES organizacion.persona (empresa_id, id),
  FOREIGN KEY (empresa_id, frente_id) REFERENCES estructura.frente (empresa_id, id),
  FOREIGN KEY (empresa_id, carga_id) REFERENCES migracion.carga (empresa_id, id)
);
CREATE INDEX ix_asistencia_persona ON historico.asistencia_dia (empresa_id, persona_id, fecha);
CREATE INDEX ix_asistencia_carga ON historico.asistencia_dia (carga_id);

-- Hoja BD: frente asignado para el IMSS.
CREATE TABLE historico.asignacion (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id       bigint  NOT NULL REFERENCES organizacion.empresa,
  mes              date    NOT NULL CHECK (extract(day FROM mes) = 1),
  persona_id       bigint  NOT NULL,
  frente_id        bigint,
  frente_texto     text    CHECK (length(frente_texto) <= 80),
  asignacion_texto text    CHECK (length(asignacion_texto) <= 80),
  calidad          text    NOT NULL CHECK (calidad IN ('valida', 'corregida')),
  carga_id         bigint  NOT NULL,
  numero           integer NOT NULL,
  hoja             text    NOT NULL,
  fila_excel       integer,
  UNIQUE (empresa_id, id),
  FOREIGN KEY (empresa_id, persona_id) REFERENCES organizacion.persona (empresa_id, id),
  FOREIGN KEY (empresa_id, frente_id) REFERENCES estructura.frente (empresa_id, id),
  FOREIGN KEY (empresa_id, carga_id) REFERENCES migracion.carga (empresa_id, id)
);
CREATE INDEX ix_asignacion_carga ON historico.asignacion (carga_id);

-- ---------------------------------------------------------------------------------------------
-- Destajo: un pago por lote, concepto, semana y trabajador. "=2500+2500" son dos pagos parciales
-- (parcial 1 y 2). Varias semanas o trabajadores en una celda: el monto no se reparte
-- (monto_compartido). Lo pagado de más sobre el importe del concepto lleva la bandera
-- sobre_importe para revisión de coordinación, no es un cargo automático.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE historico.pago_destajo (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id       bigint        NOT NULL REFERENCES organizacion.empresa,
  anio             smallint      NOT NULL CHECK (anio BETWEEN 2020 AND 2100),
  semanas          smallint[]    NOT NULL DEFAULT '{}'
                   CHECK (1 <= ALL (semanas) AND 53 >= ALL (semanas)),
  semana_texto     text          CHECK (length(semana_texto) <= 60),
  frente_id        bigint        NOT NULL,
  contrato_id      bigint        NOT NULL,
  unidad_obra_id   bigint        NOT NULL,
  prototipo_id     bigint,
  paquete_id       bigint,
  seccion_texto    text          CHECK (length(seccion_texto) <= 160),
  concepto_texto   text          CHECK (length(concepto_texto) <= 160),
  importe_concepto numeric(14,2),
  monto            numeric(14,2) NOT NULL CHECK (monto <> 0),
  parcial          smallint      NOT NULL DEFAULT 1 CHECK (parcial >= 1),
  monto_compartido boolean       NOT NULL DEFAULT false,
  sobre_importe    boolean       NOT NULL DEFAULT false,
  trabajador_texto text          CHECK (length(trabajador_texto) <= 160),
  calidad          text          NOT NULL CHECK (calidad IN ('valida', 'corregida')),
  carga_id         bigint        NOT NULL,
  numero           integer       NOT NULL,
  hoja             text          NOT NULL,
  fila_excel       integer,
  celda            text          CHECK (celda ~ '^[A-Z]{1,3}[0-9]{1,7}$'),
  UNIQUE (empresa_id, id),
  FOREIGN KEY (empresa_id, frente_id) REFERENCES estructura.frente (empresa_id, id),
  FOREIGN KEY (empresa_id, contrato_id, frente_id)
    REFERENCES estructura.contrato (empresa_id, id, frente_id),
  FOREIGN KEY (empresa_id, unidad_obra_id, contrato_id)
    REFERENCES estructura.unidad_obra (empresa_id, id, contrato_id),
  FOREIGN KEY (empresa_id, prototipo_id) REFERENCES estructura.prototipo (empresa_id, id),
  FOREIGN KEY (empresa_id, paquete_id) REFERENCES estructura.paquete (empresa_id, id),
  FOREIGN KEY (empresa_id, carga_id) REFERENCES migracion.carga (empresa_id, id)
);
CREATE INDEX ix_pago_unidad ON historico.pago_destajo (empresa_id, unidad_obra_id);
CREATE INDEX ix_pago_carga ON historico.pago_destajo (carga_id);

-- Quién cobró cada pago. Varias personas en un pago compartido.
CREATE TABLE historico.pago_trabajador (
  empresa_id bigint   NOT NULL REFERENCES organizacion.empresa,
  pago_id    bigint   NOT NULL,
  persona_id bigint   NOT NULL,
  orden      smallint NOT NULL DEFAULT 1,
  PRIMARY KEY (pago_id, persona_id),
  FOREIGN KEY (empresa_id, pago_id) REFERENCES historico.pago_destajo (empresa_id, id),
  FOREIGN KEY (empresa_id, persona_id) REFERENCES organizacion.persona (empresa_id, id)
);
CREATE INDEX ix_pago_trabajador_persona ON historico.pago_trabajador (empresa_id, persona_id);

-- ---------------------------------------------------------------------------------------------
-- Inmutable: ni la app ni el dueño cambian un renglón. Borrar solo se puede desde
-- historico.revertir_carga, que enciende historico.reversa en su propia transacción.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION historico.proteger() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
BEGIN
  IF TG_OP = 'DELETE' AND current_setting('historico.reversa', true) = 'on' THEN
    RETURN OLD;
  END IF;
  RAISE EXCEPTION 'historico.% no se modifica: se revierte la carga completa y se vuelve a cargar',
    TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END
$$;

DO $$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['entrada', 'salida', 'requisicion_linea', 'prestamo', 'alta_imss',
                           'asistencia_dia', 'asignacion', 'pago_destajo', 'pago_trabajador']
  LOOP
    EXECUTE format('ALTER TABLE historico.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE historico.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY por_empresa ON historico.%I'
                   ' USING (empresa_id = compartido.empresa_actual())'
                   ' WITH CHECK (empresa_id = compartido.empresa_actual())', t);
    EXECUTE format('CREATE TRIGGER trg_inmutable BEFORE UPDATE OR DELETE ON historico.%I'
                   ' FOR EACH ROW EXECUTE FUNCTION historico.proteger()', t);
    EXECUTE format('GRANT SELECT, INSERT ON historico.%I TO app', t);
  END LOOP;
END
$$;

-- Revertir una carga del histórico: borra todo lo que produjo, solo de la empresa en curso. Corre
-- como migrador (dueño, BYPASSRLS), por eso filtra la empresa de forma explícita.
CREATE FUNCTION historico.revertir_carga(p_carga bigint) RETURNS integer
  LANGUAGE plpgsql SECURITY DEFINER
  SET search_path = pg_catalog, pg_temp
  AS $$
DECLARE
  v_empresa bigint := compartido.empresa_actual();
  v_total   integer := 0;
  v_filas   integer;
BEGIN
  IF v_empresa IS NULL THEN
    RAISE EXCEPTION 'No hay empresa en el contexto' USING ERRCODE = 'insufficient_privilege';
  END IF;
  PERFORM set_config('historico.reversa', 'on', true);
  DELETE FROM historico.pago_trabajador t
   USING historico.pago_destajo p
   WHERE t.pago_id = p.id AND p.empresa_id = v_empresa AND p.carga_id = p_carga;
  DELETE FROM historico.pago_destajo WHERE empresa_id = v_empresa AND carga_id = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  DELETE FROM historico.entrada WHERE empresa_id = v_empresa AND carga_id = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  DELETE FROM historico.salida WHERE empresa_id = v_empresa AND carga_id = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  DELETE FROM historico.requisicion_linea WHERE empresa_id = v_empresa AND carga_id = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  DELETE FROM historico.prestamo WHERE empresa_id = v_empresa AND carga_id = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  DELETE FROM historico.alta_imss WHERE empresa_id = v_empresa AND carga_id = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  DELETE FROM historico.asistencia_dia WHERE empresa_id = v_empresa AND carga_id = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  DELETE FROM historico.asignacion WHERE empresa_id = v_empresa AND carga_id = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  PERFORM set_config('historico.reversa', 'off', true);
  RETURN v_total;
END
$$;
REVOKE ALL ON FUNCTION historico.revertir_carga(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION historico.revertir_carga(bigint) TO app;

-- ---------------------------------------------------------------------------------------------
-- Reportes: costo de obra = histórico + vivo. security_invoker: la vista se lee con los permisos
-- y el RLS de quien consulta, no con los del dueño (que salta el RLS).
-- ---------------------------------------------------------------------------------------------
CREATE SCHEMA reportes;
GRANT USAGE ON SCHEMA reportes TO app;

-- Material: salidas entregadas del Excel (valuadas con el ponderado de sus entradas) y cargos del
-- sistema (ADR-010). Sin lote: unidad_obra_id NULL ("Sin asignar" del frente).
CREATE VIEW reportes.v_costo_unidad WITH (security_invoker = true) AS
  SELECT s.empresa_id, 'historico'::text AS fuente, s.fecha, s.frente_id, s.contrato_id,
         s.unidad_obra_id, s.paquete_id, s.clasificacion_id, s.material_id,
         s.cantidad, coalesce(s.importe, 0)::numeric(18,4) AS importe, s.costo_estimado
    FROM historico.salida s
   WHERE s.entregado
  UNION ALL
  SELECT c.empresa_id, 'sistema', c.fecha, k.frente_id, c.contrato_id,
         c.unidad_obra_id, c.paquete_id, c.clasificacion_id, c.material_id,
         c.cantidad, c.importe, false
    FROM inventario.cargo c
    JOIN estructura.contrato k ON k.empresa_id = c.empresa_id AND k.id = c.contrato_id;

-- Destajo pagado por lote y paquete (por ahora solo del Excel; el módulo de destajo sumará el suyo).
CREATE VIEW reportes.v_destajo_unidad WITH (security_invoker = true) AS
  SELECT p.empresa_id, 'historico'::text AS fuente, p.anio, p.semanas, p.frente_id,
         p.contrato_id, p.unidad_obra_id, p.paquete_id, p.monto, p.sobre_importe
    FROM historico.pago_destajo p;

GRANT SELECT ON reportes.v_costo_unidad, reportes.v_destajo_unidad TO app;

-- V007 · Inventario (H4): almacenes, movimientos, kardex, saldo, cargos a obra, conteo físico,
-- préstamos de herramienta y cierre de mes. Más los archivos subidos (fotos de merma).
--
-- Cuatro defensas viven aquí y no solo en el código:
--   1. La existencia nunca queda negativa: CHECK en inventario.saldo.
--   2. El kardex y los cargos a obra no se editan ni se borran: la app no tiene UPDATE ni DELETE
--      y un trigger lo impide incluso al dueño de las tablas.
--   3. Quien captura un documento no lo autoriza: CHECK en inventario.movimiento.
--   4. Toda salida lleva contrato, unidad de obra, paquete y clasificación, y la unidad es de ese
--      contrato: CHECK y FK compuesta en inventario.movimiento_linea.
-- Además, un documento aplicado ya no cambia (solo se cancela) y un mes cerrado no recibe
-- movimientos.
--
-- Valuación: costo promedio ponderado, sin IVA, por almacén y material. El saldo guarda la
-- existencia y el valor; el promedio es valor ÷ existencia. Una salida congela el promedio en su
-- línea; si se lleva toda la existencia, se lleva todo el valor (no quedan centavos sueltos).
-- El costo de un lote sale de inventario.cargo: cada vale suma, cada devolución resta, cada
-- reasignación resta en un lote y suma en otro, y cada ajuste de costo corrige lo ya consumido.

CREATE SCHEMA inventario;
GRANT USAGE ON SCHEMA inventario TO app;

-- La FK compuesta (unidad, contrato) de las líneas exige esta llave en la unidad de obra.
ALTER TABLE estructura.unidad_obra
  ADD CONSTRAINT uq_unidad_obra_contrato UNIQUE (empresa_id, id, contrato_id);

-- ---------------------------------------------------------------------------------------------
-- Archivos subidos (fotos de merma). Se guardan en la base hasta que exista el bucket S3 (H7);
-- el código los lee y escribe por compartido.Almacenamiento, así que cambiar de lugar no toca a
-- quien los usa. Solo imágenes y PDF, hasta 10 MB.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE compartido.archivo (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id  bigint      NOT NULL,
  nombre      text        NOT NULL CHECK (length(btrim(nombre)) BETWEEN 1 AND 200),
  tipo        text        NOT NULL
              CHECK (tipo IN ('image/jpeg', 'image/png', 'image/webp', 'application/pdf')),
  tamano      integer     NOT NULL CHECK (tamano BETWEEN 1 AND 10485760),
  sha256      text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  contenido   bytea       NOT NULL,
  usuario_id  bigint      NOT NULL,
  creado      timestamptz NOT NULL DEFAULT now(),
  CHECK (octet_length(contenido) = tamano),
  UNIQUE (empresa_id, id)
);
ALTER TABLE compartido.archivo ENABLE ROW LEVEL SECURITY;
ALTER TABLE compartido.archivo FORCE ROW LEVEL SECURITY;
CREATE POLICY por_empresa ON compartido.archivo
  USING (empresa_id = compartido.empresa_actual())
  WITH CHECK (empresa_id = compartido.empresa_actual());
-- Un archivo no se cambia ni se borra: lo que se subió como evidencia queda.
GRANT SELECT, INSERT ON compartido.archivo TO app;
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON compartido.archivo
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id', 'contenido');

-- ---------------------------------------------------------------------------------------------
-- Almacén: uno por frente (decisión de H4). Los traspasos mueven material entre almacenes.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE inventario.almacen (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id bigint      NOT NULL REFERENCES organizacion.empresa,
  frente_id  bigint      NOT NULL,
  clave      text        NOT NULL CHECK (clave ~ '^[A-Z0-9]{2,12}$'),
  nombre     text        NOT NULL CHECK (length(btrim(nombre)) BETWEEN 1 AND 120),
  activo     boolean     NOT NULL DEFAULT true,
  version    integer     NOT NULL DEFAULT 0,
  creado     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, clave),
  UNIQUE (empresa_id, frente_id),
  FOREIGN KEY (empresa_id, frente_id) REFERENCES estructura.frente (empresa_id, id)
);

-- ---------------------------------------------------------------------------------------------
-- Meses cerrados. Solo existen los renglones de meses cerrados; la app no puede reabrirlos
-- (sin UPDATE ni DELETE). La foto del cierre queda en cierre_saldo.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE inventario.periodo (
  empresa_id  bigint      NOT NULL REFERENCES organizacion.empresa,
  anio        smallint    NOT NULL CHECK (anio BETWEEN 2020 AND 2100),
  mes         smallint    NOT NULL CHECK (mes BETWEEN 1 AND 12),
  cerrado_por bigint      NOT NULL,
  cerrado_en  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (empresa_id, anio, mes),
  FOREIGN KEY (empresa_id, cerrado_por) REFERENCES organizacion.usuario (empresa_id, id)
);

CREATE FUNCTION inventario.periodo_cerrado(p_empresa bigint, p_fecha date) RETURNS boolean
  LANGUAGE sql STABLE
  AS $$
  SELECT EXISTS (SELECT 1 FROM inventario.periodo
                  WHERE empresa_id = p_empresa
                    AND anio = extract(year FROM p_fecha)
                    AND mes = extract(month FROM p_fecha))
$$;

-- ---------------------------------------------------------------------------------------------
-- Conteo físico. Mientras está abierto, el almacén no aplica movimientos. Al cerrarlo, la
-- diferencia contra el sistema se vuelve un ajuste de entrada y uno de salida.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE inventario.conteo (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id     bigint      NOT NULL REFERENCES organizacion.empresa,
  folio          text        NOT NULL,
  almacen_id     bigint      NOT NULL,
  fecha          date        NOT NULL,
  estado         text        NOT NULL DEFAULT 'abierto'
                 CHECK (estado IN ('abierto', 'cerrado', 'cancelado')),
  usuario_crea   bigint      NOT NULL,
  usuario_cierra bigint,
  cerrado_en     timestamptz,
  observaciones  text        CHECK (length(observaciones) <= 500),
  version        integer     NOT NULL DEFAULT 0,
  creado         timestamptz NOT NULL DEFAULT now(),
  -- Quien abre y captura el conteo no lo cierra (regla 5).
  CHECK (usuario_cierra IS NULL OR usuario_cierra <> usuario_crea),
  CHECK ((estado = 'cerrado') = (usuario_cierra IS NOT NULL)),
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, folio),
  FOREIGN KEY (empresa_id, almacen_id) REFERENCES inventario.almacen (empresa_id, id),
  FOREIGN KEY (empresa_id, usuario_crea) REFERENCES organizacion.usuario (empresa_id, id),
  FOREIGN KEY (empresa_id, usuario_cierra) REFERENCES organizacion.usuario (empresa_id, id)
);
CREATE UNIQUE INDEX ux_conteo_abierto ON inventario.conteo (empresa_id, almacen_id)
  WHERE estado = 'abierto';

CREATE TABLE inventario.conteo_linea (
  empresa_id         bigint        NOT NULL,
  conteo_id          bigint        NOT NULL,
  material_id        bigint        NOT NULL,
  contado            numeric(14,4) CHECK (contado >= 0),
  -- Costo para lo que sobra de un material que no tiene costo en el almacén (apertura de H8).
  costo_unitario     numeric(16,6) CHECK (costo_unitario >= 0),
  existencia_sistema numeric(14,4) CHECK (existencia_sistema >= 0),   -- se fija al cerrar
  contado_por        bigint,
  contado_en         timestamptz,
  PRIMARY KEY (conteo_id, material_id),
  FOREIGN KEY (empresa_id, conteo_id) REFERENCES inventario.conteo (empresa_id, id),
  FOREIGN KEY (empresa_id, material_id) REFERENCES catalogo.material (empresa_id, id),
  FOREIGN KEY (empresa_id, contado_por) REFERENCES organizacion.usuario (empresa_id, id)
);

-- ---------------------------------------------------------------------------------------------
-- Préstamo de herramienta (o de material). No mueve la existencia: la aparta en saldo.prestado
-- hasta que regresa. Si se pierde, se registra una merma ligada al préstamo.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE inventario.prestamo (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id       bigint        NOT NULL REFERENCES organizacion.empresa,
  folio            text          NOT NULL,
  almacen_id       bigint        NOT NULL,
  material_id      bigint        NOT NULL,
  cantidad         numeric(14,4) NOT NULL CHECK (cantidad > 0),
  responsable_id   bigint        NOT NULL,
  contrato_id      bigint,
  fecha            date          NOT NULL,
  compromiso       date,
  estado           text          NOT NULL DEFAULT 'abierto'
                   CHECK (estado IN ('abierto', 'devuelto', 'perdido')),
  devuelto_en      date,
  usuario_presta   bigint        NOT NULL,
  usuario_recibe   bigint,
  observaciones    text          CHECK (length(observaciones) <= 500),
  version          integer       NOT NULL DEFAULT 0,
  creado           timestamptz   NOT NULL DEFAULT now(),
  CHECK (compromiso IS NULL OR compromiso >= fecha),
  CHECK ((estado = 'devuelto') = (devuelto_en IS NOT NULL)),
  CHECK (devuelto_en IS NULL OR devuelto_en >= fecha),
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, folio),
  FOREIGN KEY (empresa_id, almacen_id) REFERENCES inventario.almacen (empresa_id, id),
  FOREIGN KEY (empresa_id, material_id) REFERENCES catalogo.material (empresa_id, id),
  FOREIGN KEY (empresa_id, responsable_id) REFERENCES organizacion.persona (empresa_id, id),
  FOREIGN KEY (empresa_id, contrato_id) REFERENCES estructura.contrato (empresa_id, id),
  FOREIGN KEY (empresa_id, usuario_presta) REFERENCES organizacion.usuario (empresa_id, id),
  FOREIGN KEY (empresa_id, usuario_recibe) REFERENCES organizacion.usuario (empresa_id, id)
);
CREATE INDEX ix_prestamo_abierto ON inventario.prestamo (empresa_id, almacen_id)
  WHERE estado = 'abierto';

-- ---------------------------------------------------------------------------------------------
-- Movimiento: el documento (recepción, vale, devolución, traspaso, reasignación, merma, ajuste).
-- Ciclo: borrador → pendiente (enviado a autorizar) → autorizado → aplicado. Los tipos que no se
-- autorizan pasan de borrador a aplicado. Aplicar es lo único que mueve existencia y costo.
-- Un documento aplicado no cambia: se cancela y la cancelación crea el movimiento inverso.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE inventario.movimiento (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id           bigint        NOT NULL REFERENCES organizacion.empresa,
  folio                text          NOT NULL,
  tipo                 text          NOT NULL
                       CHECK (tipo IN ('entrada', 'salida', 'devolucion', 'devolucion_proveedor',
                                       'traspaso', 'reasignacion', 'merma', 'ajuste_entrada',
                                       'ajuste_salida', 'ajuste_costo')),
  estado               text          NOT NULL DEFAULT 'borrador'
                       CHECK (estado IN ('borrador', 'pendiente', 'autorizado', 'aplicado',
                                         'cancelado')),
  fecha                date          NOT NULL,
  almacen_id           bigint        NOT NULL,       -- origen, o el único almacén
  almacen_destino_id   bigint,                       -- solo traspaso
  proveedor_id         bigint,                       -- recepción y devolución a proveedor
  folio_factura        text          CHECK (length(btrim(folio_factura)) BETWEEN 1 AND 40),
  flete                numeric(16,4) CHECK (flete >= 0),   -- se prorratea por importe
  solicitante_id       bigint,                       -- persona que pide (vale) o regresa
  movimiento_origen_id bigint,                       -- cancelación o ajuste de costo
  conteo_id            bigint,                       -- ajustes de un conteo físico
  prestamo_id          bigint,                       -- merma de una herramienta perdida
  archivo_id           bigint,                       -- foto o acta de la merma
  usuario_captura      bigint        NOT NULL,
  usuario_autoriza     bigint,
  usuario_aplica       bigint,
  autorizado_en        timestamptz,
  aplicado_en          timestamptz,
  cancelado_en         timestamptz,
  motivo               text          CHECK (length(btrim(motivo)) BETWEEN 1 AND 500),
  nota_rechazo         text          CHECK (length(nota_rechazo) <= 500),
  motivo_cancelacion   text          CHECK (length(btrim(motivo_cancelacion)) BETWEEN 1 AND 500),
  observaciones        text          CHECK (length(observaciones) <= 500),
  idempotencia         uuid,
  version              integer       NOT NULL DEFAULT 0,
  creado               timestamptz   NOT NULL DEFAULT now(),
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, id, tipo),
  UNIQUE (empresa_id, folio),
  UNIQUE (empresa_id, idempotencia),
  -- Regla 5: quien captura no autoriza su propio documento.
  CHECK (usuario_autoriza IS NULL OR usuario_autoriza <> usuario_captura),
  -- Vale, traspaso, reasignación, merma y ajuste de costo no se aplican sin autorización.
  CHECK (estado NOT IN ('autorizado', 'aplicado')
         OR tipo NOT IN ('salida', 'traspaso', 'reasignacion', 'merma', 'ajuste_costo')
         OR usuario_autoriza IS NOT NULL),
  CHECK (estado <> 'aplicado' OR (aplicado_en IS NOT NULL AND usuario_aplica IS NOT NULL)),
  CHECK (estado <> 'cancelado' OR (cancelado_en IS NOT NULL AND motivo_cancelacion IS NOT NULL)),
  CHECK ((tipo = 'traspaso') = (almacen_destino_id IS NOT NULL)),
  CHECK (almacen_destino_id IS NULL OR almacen_destino_id <> almacen_id),
  CHECK ((tipo IN ('entrada', 'devolucion_proveedor')) = (proveedor_id IS NOT NULL)),
  CHECK (tipo = 'entrada' OR (folio_factura IS NULL AND flete IS NULL)
         OR tipo = 'ajuste_costo' AND flete IS NULL),
  CHECK (tipo <> 'merma' OR (motivo IS NOT NULL AND (archivo_id IS NOT NULL OR prestamo_id IS NOT NULL))),
  CHECK (tipo <> 'ajuste_costo' OR movimiento_origen_id IS NOT NULL),
  CHECK (tipo IN ('ajuste_entrada', 'ajuste_salida') OR conteo_id IS NULL),
  CHECK (tipo = 'merma' OR prestamo_id IS NULL),
  FOREIGN KEY (empresa_id, almacen_id) REFERENCES inventario.almacen (empresa_id, id),
  FOREIGN KEY (empresa_id, almacen_destino_id) REFERENCES inventario.almacen (empresa_id, id),
  FOREIGN KEY (empresa_id, proveedor_id) REFERENCES catalogo.proveedor (empresa_id, id),
  FOREIGN KEY (empresa_id, solicitante_id) REFERENCES organizacion.persona (empresa_id, id),
  FOREIGN KEY (empresa_id, movimiento_origen_id) REFERENCES inventario.movimiento (empresa_id, id),
  FOREIGN KEY (empresa_id, conteo_id) REFERENCES inventario.conteo (empresa_id, id),
  FOREIGN KEY (empresa_id, prestamo_id) REFERENCES inventario.prestamo (empresa_id, id),
  FOREIGN KEY (empresa_id, archivo_id) REFERENCES compartido.archivo (empresa_id, id),
  FOREIGN KEY (empresa_id, usuario_captura) REFERENCES organizacion.usuario (empresa_id, id),
  FOREIGN KEY (empresa_id, usuario_autoriza) REFERENCES organizacion.usuario (empresa_id, id),
  FOREIGN KEY (empresa_id, usuario_aplica) REFERENCES organizacion.usuario (empresa_id, id)
);
CREATE INDEX ix_movimiento_bandeja ON inventario.movimiento (empresa_id, estado, tipo, fecha);
CREATE INDEX ix_movimiento_almacen ON inventario.movimiento (empresa_id, almacen_id, fecha);

-- ---------------------------------------------------------------------------------------------
-- Línea: un material. La cantidad va en unidad de control; la capturada se guarda como se
-- capturó. Un reparto (un bote para 18 lotes) crea varias líneas con el mismo renglón; la última
-- se lleva el residuo para que la suma cuadre exacta. El costo y el importe se congelan al aplicar.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE inventario.movimiento_linea (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id        bigint        NOT NULL,
  movimiento_id     bigint        NOT NULL,
  tipo              text          NOT NULL,              -- copia del documento, por la FK
  renglon           smallint      NOT NULL CHECK (renglon > 0),
  material_id       bigint        NOT NULL,
  cantidad          numeric(14,4) NOT NULL CHECK (cantidad > 0),
  cantidad_captura  numeric(14,4) NOT NULL CHECK (cantidad_captura > 0),
  unidad_captura_id bigint        NOT NULL REFERENCES catalogo.unidad_medida,
  precio_captura    numeric(16,6) CHECK (precio_captura >= 0),   -- recepción: sin IVA, por unidad capturada
  descuento         numeric(16,4) CHECK (descuento >= 0),        -- recepción: importe del renglón
  tasa_iva          numeric(5,4)  CHECK (tasa_iva BETWEEN 0 AND 0.16),
  costo_nuevo       numeric(16,6) CHECK (costo_nuevo >= 0),      -- ajuste de costo: costo correcto
  costo_unitario    numeric(16,6) CHECK (costo_unitario >= 0),   -- se congela al aplicar
  importe           numeric(18,4),                               -- se congela al aplicar (ajuste de costo: con signo)
  contrato_id       bigint,
  unidad_obra_id    bigint,
  paquete_id        bigint,
  clasificacion_id  smallint      REFERENCES catalogo.clasificacion_costo,
  linea_origen_id   bigint,
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, id, material_id),
  FOREIGN KEY (empresa_id, movimiento_id, tipo)
    REFERENCES inventario.movimiento (empresa_id, id, tipo),
  FOREIGN KEY (empresa_id, material_id) REFERENCES catalogo.material (empresa_id, id),
  FOREIGN KEY (empresa_id, contrato_id) REFERENCES estructura.contrato (empresa_id, id),
  -- La unidad de obra es del contrato de la línea.
  FOREIGN KEY (empresa_id, unidad_obra_id, contrato_id)
    REFERENCES estructura.unidad_obra (empresa_id, id, contrato_id),
  FOREIGN KEY (empresa_id, paquete_id) REFERENCES estructura.paquete (empresa_id, id),
  -- La línea de origen es del mismo material.
  FOREIGN KEY (empresa_id, linea_origen_id, material_id)
    REFERENCES inventario.movimiento_linea (empresa_id, id, material_id),
  -- Regla 4: lo que se carga a obra lleva contrato, unidad, paquete y clasificación.
  CHECK (tipo NOT IN ('salida', 'devolucion', 'reasignacion')
         OR (contrato_id IS NOT NULL AND unidad_obra_id IS NOT NULL AND paquete_id IS NOT NULL
             AND clasificacion_id IS NOT NULL)),
  CHECK (tipo IN ('salida', 'devolucion', 'reasignacion')
         OR (contrato_id IS NULL AND unidad_obra_id IS NULL AND paquete_id IS NULL
             AND clasificacion_id IS NULL)),
  CHECK ((tipo IN ('devolucion', 'devolucion_proveedor', 'reasignacion', 'ajuste_costo'))
         = (linea_origen_id IS NOT NULL)),
  CHECK ((tipo = 'entrada') = (precio_captura IS NOT NULL)),
  CHECK (tipo = 'entrada' OR (descuento IS NULL AND tasa_iva IS NULL)),
  CHECK ((tipo = 'ajuste_costo') = (costo_nuevo IS NOT NULL)),
  CHECK ((costo_unitario IS NULL) = (importe IS NULL))
);
CREATE INDEX ix_linea_movimiento ON inventario.movimiento_linea (empresa_id, movimiento_id, renglon);
CREATE INDEX ix_linea_origen ON inventario.movimiento_linea (empresa_id, linea_origen_id)
  WHERE linea_origen_id IS NOT NULL;
CREATE INDEX ix_linea_material ON inventario.movimiento_linea (empresa_id, material_id);

-- ---------------------------------------------------------------------------------------------
-- Saldo por almacén y material. Con existencia en cero, costo_promedio guarda el último costo.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE inventario.saldo (
  empresa_id     bigint        NOT NULL,
  almacen_id     bigint        NOT NULL,
  material_id    bigint        NOT NULL,
  existencia     numeric(14,4) NOT NULL DEFAULT 0 CHECK (existencia >= 0),
  valor          numeric(18,4) NOT NULL DEFAULT 0 CHECK (valor >= 0),
  costo_promedio numeric(16,6) NOT NULL DEFAULT 0 CHECK (costo_promedio >= 0),
  reservado      numeric(14,4) NOT NULL DEFAULT 0 CHECK (reservado >= 0),   -- requisiciones (H6)
  prestado       numeric(14,4) NOT NULL DEFAULT 0 CHECK (prestado >= 0),
  actualizado    timestamptz   NOT NULL DEFAULT now(),
  PRIMARY KEY (empresa_id, almacen_id, material_id),
  CHECK (reservado + prestado <= existencia),
  CHECK (existencia > 0 OR valor = 0),
  FOREIGN KEY (empresa_id, almacen_id) REFERENCES inventario.almacen (empresa_id, id),
  FOREIGN KEY (empresa_id, material_id) REFERENCES catalogo.material (empresa_id, id)
);

-- ---------------------------------------------------------------------------------------------
-- Kardex: cada cambio de existencia o de valor, con signo, en el orden en que se aplicó.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE inventario.kardex (
  id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id             bigint        NOT NULL,
  almacen_id             bigint        NOT NULL,
  material_id            bigint        NOT NULL,
  movimiento_id          bigint        NOT NULL,
  linea_id               bigint        NOT NULL,
  fecha                  date          NOT NULL,          -- fecha del documento
  cantidad               numeric(14,4) NOT NULL,          -- + entra, − sale, 0 ajuste de costo
  costo_unitario         numeric(16,6) NOT NULL CHECK (costo_unitario >= 0),
  importe                numeric(18,4) NOT NULL,          -- con signo
  existencia_despues     numeric(14,4) NOT NULL CHECK (existencia_despues >= 0),
  valor_despues          numeric(18,4) NOT NULL CHECK (valor_despues >= 0),
  costo_promedio_despues numeric(16,6) NOT NULL CHECK (costo_promedio_despues >= 0),
  registrado             timestamptz   NOT NULL DEFAULT now(),
  FOREIGN KEY (empresa_id, almacen_id) REFERENCES inventario.almacen (empresa_id, id),
  FOREIGN KEY (empresa_id, movimiento_id) REFERENCES inventario.movimiento (empresa_id, id),
  FOREIGN KEY (empresa_id, linea_id, material_id)
    REFERENCES inventario.movimiento_linea (empresa_id, id, material_id)
);
CREATE INDEX ix_kardex ON inventario.kardex (empresa_id, almacen_id, material_id, id);
CREATE INDEX ix_kardex_fecha ON inventario.kardex (empresa_id, fecha);

-- ---------------------------------------------------------------------------------------------
-- Cargo a obra: el costo de cada lote, con signo. linea_base_id es la línea de vale (o de
-- reasignación) a la que pertenece el cargo: lo que queda de ella es la suma de sus cargos, y
-- eso limita cuánto se puede devolver o reasignar.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE inventario.cargo (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id       bigint        NOT NULL,
  movimiento_id    bigint        NOT NULL,
  linea_id         bigint        NOT NULL,
  linea_base_id    bigint        NOT NULL,
  fecha            date          NOT NULL,
  material_id      bigint        NOT NULL,
  contrato_id      bigint        NOT NULL,
  unidad_obra_id   bigint        NOT NULL,
  paquete_id       bigint        NOT NULL,
  clasificacion_id smallint      NOT NULL REFERENCES catalogo.clasificacion_costo,
  cantidad         numeric(14,4) NOT NULL,          -- con signo; 0 en un ajuste de costo
  importe          numeric(18,4) NOT NULL,          -- con signo
  registrado       timestamptz   NOT NULL DEFAULT now(),
  FOREIGN KEY (empresa_id, movimiento_id) REFERENCES inventario.movimiento (empresa_id, id),
  FOREIGN KEY (empresa_id, linea_id) REFERENCES inventario.movimiento_linea (empresa_id, id),
  FOREIGN KEY (empresa_id, linea_base_id, material_id)
    REFERENCES inventario.movimiento_linea (empresa_id, id, material_id),
  FOREIGN KEY (empresa_id, unidad_obra_id, contrato_id)
    REFERENCES estructura.unidad_obra (empresa_id, id, contrato_id),
  FOREIGN KEY (empresa_id, paquete_id) REFERENCES estructura.paquete (empresa_id, id)
);
CREATE INDEX ix_cargo_base ON inventario.cargo (empresa_id, linea_base_id);
CREATE INDEX ix_cargo_costo ON inventario.cargo (empresa_id, contrato_id, unidad_obra_id, paquete_id);
CREATE INDEX ix_cargo_material ON inventario.cargo (empresa_id, material_id, id);

-- ---------------------------------------------------------------------------------------------
-- Foto del inventario al cerrar el mes: existencia y valor con fecha de documento hasta el
-- último día del mes (suma del kardex).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE inventario.cierre_saldo (
  empresa_id  bigint        NOT NULL,
  anio        smallint      NOT NULL,
  mes         smallint      NOT NULL,
  almacen_id  bigint        NOT NULL,
  material_id bigint        NOT NULL,
  existencia  numeric(14,4) NOT NULL,
  valor       numeric(18,4) NOT NULL,
  PRIMARY KEY (empresa_id, anio, mes, almacen_id, material_id),
  FOREIGN KEY (empresa_id, anio, mes) REFERENCES inventario.periodo (empresa_id, anio, mes),
  FOREIGN KEY (empresa_id, almacen_id) REFERENCES inventario.almacen (empresa_id, id),
  FOREIGN KEY (empresa_id, material_id) REFERENCES catalogo.material (empresa_id, id)
);

-- ---------------------------------------------------------------------------------------------
-- Defensas en triggers
-- ---------------------------------------------------------------------------------------------

-- Kardex y cargos: solo se agregan renglones. Ni el dueño de las tablas los cambia.
CREATE FUNCTION inventario.prohibir_cambio() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
BEGIN
  RAISE EXCEPTION 'inventario.% no se modifica: se corrige con un movimiento inverso', TG_TABLE_NAME
    USING ERRCODE = 'restrict_violation';
END
$$;
CREATE TRIGGER trg_inmutable BEFORE UPDATE OR DELETE ON inventario.kardex
  FOR EACH ROW EXECUTE FUNCTION inventario.prohibir_cambio();
CREATE TRIGGER trg_inmutable BEFORE UPDATE OR DELETE ON inventario.cargo
  FOR EACH ROW EXECUTE FUNCTION inventario.prohibir_cambio();
CREATE TRIGGER trg_inmutable BEFORE UPDATE OR DELETE ON inventario.cierre_saldo
  FOR EACH ROW EXECUTE FUNCTION inventario.prohibir_cambio();

-- Movimiento: transiciones válidas, mes cerrado, y nada cambia después de aplicar.
CREATE FUNCTION inventario.validar_movimiento() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  v_ignorar text[] := ARRAY['estado', 'cancelado_en', 'motivo_cancelacion', 'version'];
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.estado <> 'borrador' THEN
      RAISE EXCEPTION 'un movimiento nace en borrador' USING ERRCODE = 'check_violation';
    END IF;
    IF inventario.periodo_cerrado(NEW.empresa_id, NEW.fecha) THEN
      RAISE EXCEPTION 'PERIODO_CERRADO: % pertenece a un mes cerrado', NEW.fecha
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF OLD.estado IN ('aplicado', 'cancelado') THEN
    IF NOT (OLD.estado = 'aplicado' AND NEW.estado = 'cancelado')
       OR (to_jsonb(NEW) - v_ignorar) <> (to_jsonb(OLD) - v_ignorar) THEN
      RAISE EXCEPTION 'el movimiento % ya está %: solo se cancela', OLD.folio, OLD.estado
        USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF NEW.estado <> OLD.estado AND (OLD.estado, NEW.estado) NOT IN (
       ('borrador', 'pendiente'), ('borrador', 'aplicado'), ('borrador', 'cancelado'),
       ('pendiente', 'autorizado'), ('pendiente', 'borrador'), ('pendiente', 'cancelado'),
       ('autorizado', 'aplicado'), ('autorizado', 'cancelado')) THEN
    RAISE EXCEPTION 'transición inválida de % a %', OLD.estado, NEW.estado
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.tipo <> OLD.tipo OR NEW.folio <> OLD.folio OR NEW.usuario_captura <> OLD.usuario_captura THEN
    RAISE EXCEPTION 'el tipo, el folio y quien capturó no cambian' USING ERRCODE = 'check_violation';
  END IF;
  IF (NEW.estado = 'aplicado' OR NEW.fecha <> OLD.fecha)
     AND inventario.periodo_cerrado(NEW.empresa_id, NEW.fecha) THEN
    RAISE EXCEPTION 'PERIODO_CERRADO: % pertenece a un mes cerrado', NEW.fecha
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER trg_validar BEFORE INSERT OR UPDATE ON inventario.movimiento
  FOR EACH ROW EXECUTE FUNCTION inventario.validar_movimiento();

-- Líneas: se agregan y quitan solo en borrador; después solo se congela su costo, y ya aplicado
-- el documento no cambian.
CREATE FUNCTION inventario.validar_linea() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  v_fila   inventario.movimiento_linea := coalesce(NEW, OLD);
  v_estado text;
BEGIN
  SELECT estado INTO v_estado FROM inventario.movimiento
   WHERE empresa_id = v_fila.empresa_id AND id = v_fila.movimiento_id;
  IF TG_OP IN ('INSERT', 'DELETE') THEN
    IF v_estado IS DISTINCT FROM 'borrador' THEN
      RAISE EXCEPTION 'las líneas solo cambian en borrador (el documento está %)', v_estado
        USING ERRCODE = 'restrict_violation';
    END IF;
  ELSE
    IF v_estado IN ('aplicado', 'cancelado')
       OR (to_jsonb(NEW) - ARRAY['costo_unitario', 'importe'])
          <> (to_jsonb(OLD) - ARRAY['costo_unitario', 'importe']) THEN
      RAISE EXCEPTION 'la línea ya no cambia (el documento está %)', v_estado
        USING ERRCODE = 'restrict_violation';
    END IF;
  END IF;
  RETURN coalesce(NEW, OLD);
END
$$;
CREATE TRIGGER trg_validar BEFORE INSERT OR UPDATE OR DELETE ON inventario.movimiento_linea
  FOR EACH ROW EXECUTE FUNCTION inventario.validar_linea();

-- Conteo: las líneas cambian solo con el conteo abierto.
CREATE FUNCTION inventario.validar_conteo_linea() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  v_fila   inventario.conteo_linea := coalesce(NEW, OLD);
  v_estado text;
BEGIN
  SELECT estado INTO v_estado FROM inventario.conteo
   WHERE empresa_id = v_fila.empresa_id AND id = v_fila.conteo_id;
  IF v_estado IS DISTINCT FROM 'abierto' THEN
    RAISE EXCEPTION 'el conteo ya está %', v_estado USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN coalesce(NEW, OLD);
END
$$;
CREATE TRIGGER trg_validar BEFORE INSERT OR UPDATE OR DELETE ON inventario.conteo_linea
  FOR EACH ROW EXECUTE FUNCTION inventario.validar_conteo_linea();

-- ---------------------------------------------------------------------------------------------
-- Seguridad por empresa, permisos de app y bitácora (regla 10). El kardex y los cargos ya son una
-- bitácora; el saldo se deriva del kardex: no llevan trigger de bitácora.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['almacen', 'periodo', 'conteo', 'conteo_linea', 'prestamo', 'movimiento',
                           'movimiento_linea', 'saldo', 'kardex', 'cargo', 'cierre_saldo']
  LOOP
    EXECUTE format('ALTER TABLE inventario.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE inventario.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY por_empresa ON inventario.%I'
                   ' USING (empresa_id = compartido.empresa_actual())'
                   ' WITH CHECK (empresa_id = compartido.empresa_actual())', t);
  END LOOP;
END
$$;

GRANT SELECT, INSERT, UPDATE ON inventario.almacen, inventario.conteo, inventario.prestamo,
  inventario.movimiento, inventario.saldo TO app;
GRANT SELECT, INSERT, UPDATE, DELETE ON inventario.movimiento_linea, inventario.conteo_linea TO app;
GRANT SELECT, INSERT ON inventario.periodo, inventario.kardex, inventario.cargo,
  inventario.cierre_saldo TO app;
GRANT EXECUTE ON FUNCTION inventario.periodo_cerrado(bigint, date) TO app;

CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON inventario.almacen
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON inventario.periodo
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('mes');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON inventario.conteo
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON inventario.conteo_linea
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('conteo_id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON inventario.prestamo
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON inventario.movimiento
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON inventario.movimiento_linea
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');

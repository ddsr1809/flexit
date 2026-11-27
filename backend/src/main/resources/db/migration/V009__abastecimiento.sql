-- V009 · Abastecimiento y reportes (H6): requisición con autorización, reserva de existencia,
-- pedido a proveedor con sus requisiciones, recepción contra pedido y vistas de reportes.
--
-- El ciclo: el residente pide (requisición) → coordinación autoriza y el sistema aparta lo que hay
-- en el almacén (reserva) → lo que falta lo pide compras al proveedor (pedido, ligado a los
-- renglones de requisición que cubre) → el almacén recibe contra el pedido y lo recibido queda
-- apartado para esas requisiciones → el almacén surte con un vale ligado a la requisición, que
-- consume su reserva al aplicarse.
--
-- Defensas que viven aquí y no solo en el código:
--   1. Quien captura una requisición no la autoriza (regla 5).
--   2. Una requisición se surte del almacén del frente de su contrato, y sus lotes son de ese
--      contrato: FK compuestas.
--   3. Requisición y pedido solo cambian en borrador; después solo avanzan de estado.
--   4. Lo reservado en el saldo es siempre la suma de las reservas: un trigger lo mantiene, y el
--      CHECK de inventario.saldo impide reservar más de lo que hay.
--   5. Un vale (o una recepción) ligado a una requisición (o a un pedido) es del mismo material
--      que el renglón al que se liga: FK compuesta con el material.
--
-- Lo surtido de una requisición y lo recibido de un pedido no se guardan: salen de los documentos
-- aplicados del inventario (ADR-012).

CREATE SCHEMA abastecimiento;
GRANT USAGE ON SCHEMA abastecimiento TO app;

-- La FK compuesta (almacén, frente) de la requisición exige esta llave en el almacén.
ALTER TABLE inventario.almacen
  ADD CONSTRAINT uq_almacen_frente UNIQUE (empresa_id, id, frente_id);

-- ---------------------------------------------------------------------------------------------
-- Requisición: lo que pide el residente para los lotes de un contrato. Reemplaza la hoja Semanal.
-- Ciclo: borrador → pendiente → autorizada → cerrada. Rechazar regresa a borrador con la nota.
-- Se cancela en borrador o pendiente (y autorizada sin nada surtido, lo revisa el código).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE abastecimiento.requisicion (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id       bigint      NOT NULL REFERENCES organizacion.empresa,
  folio            text        NOT NULL,
  contrato_id      bigint      NOT NULL,
  frente_id        bigint      NOT NULL,
  almacen_id       bigint      NOT NULL,
  fecha            date        NOT NULL,                -- de captura
  fecha_requerida  date        NOT NULL,                -- para cuándo se necesita en obra
  urgente          boolean     NOT NULL DEFAULT false,
  estado           text        NOT NULL DEFAULT 'borrador'
                   CHECK (estado IN ('borrador', 'pendiente', 'autorizada', 'cerrada', 'cancelada')),
  usuario_captura  bigint      NOT NULL,
  usuario_autoriza bigint,
  autorizada_en    timestamptz,
  cerrada_en       timestamptz,
  nota_rechazo     text        CHECK (length(nota_rechazo) <= 500),
  motivo_cierre    text        CHECK (length(btrim(motivo_cierre)) BETWEEN 1 AND 500),
  observaciones    text        CHECK (length(observaciones) <= 500),
  version          integer     NOT NULL DEFAULT 0,
  creado           timestamptz NOT NULL DEFAULT now(),
  CHECK (fecha_requerida >= fecha),
  -- Regla 5: quien captura no autoriza su propia requisición.
  CHECK (usuario_autoriza IS NULL OR usuario_autoriza <> usuario_captura),
  CHECK (estado NOT IN ('autorizada', 'cerrada')
         OR (usuario_autoriza IS NOT NULL AND autorizada_en IS NOT NULL)),
  CHECK ((estado IN ('cerrada', 'cancelada')) = (cerrada_en IS NOT NULL AND motivo_cierre IS NOT NULL)),
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, id, contrato_id),
  UNIQUE (empresa_id, folio),
  FOREIGN KEY (empresa_id, contrato_id, frente_id)
    REFERENCES estructura.contrato (empresa_id, id, frente_id),
  -- El almacén es el del frente del contrato.
  FOREIGN KEY (empresa_id, almacen_id, frente_id)
    REFERENCES inventario.almacen (empresa_id, id, frente_id),
  FOREIGN KEY (empresa_id, usuario_captura) REFERENCES organizacion.usuario (empresa_id, id),
  FOREIGN KEY (empresa_id, usuario_autoriza) REFERENCES organizacion.usuario (empresa_id, id)
);
CREATE INDEX ix_requisicion_estado ON abastecimiento.requisicion (empresa_id, estado, fecha_requerida);
CREATE INDEX ix_requisicion_contrato ON abastecimiento.requisicion (empresa_id, contrato_id, fecha);

-- Renglón: un material para un lote. Pedir para varios lotes crea un renglón por lote con el mismo
-- número (el reparto que suma exacto, como en el vale). La cantidad va en unidad de control; la
-- capturada se guarda como se capturó.
CREATE TABLE abastecimiento.requisicion_linea (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id        bigint        NOT NULL,
  requisicion_id    bigint        NOT NULL,
  renglon           smallint      NOT NULL CHECK (renglon > 0),
  material_id       bigint        NOT NULL,
  cantidad          numeric(14,4) NOT NULL CHECK (cantidad > 0),
  cantidad_captura  numeric(14,4) NOT NULL CHECK (cantidad_captura > 0),
  unidad_captura_id bigint        NOT NULL REFERENCES catalogo.unidad_medida,
  contrato_id       bigint        NOT NULL,             -- copia de la cabecera, por las FK
  unidad_obra_id    bigint        NOT NULL,
  paquete_id        bigint        NOT NULL,
  clasificacion_id  smallint      NOT NULL REFERENCES catalogo.clasificacion_costo,
  observaciones     text          CHECK (length(observaciones) <= 300),
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, id, material_id),
  FOREIGN KEY (empresa_id, requisicion_id, contrato_id)
    REFERENCES abastecimiento.requisicion (empresa_id, id, contrato_id),
  FOREIGN KEY (empresa_id, material_id) REFERENCES catalogo.material (empresa_id, id),
  -- El lote es del contrato de la requisición.
  FOREIGN KEY (empresa_id, unidad_obra_id, contrato_id)
    REFERENCES estructura.unidad_obra (empresa_id, id, contrato_id),
  FOREIGN KEY (empresa_id, paquete_id) REFERENCES estructura.paquete (empresa_id, id)
);
CREATE INDEX ix_requisicion_linea ON abastecimiento.requisicion_linea (empresa_id, requisicion_id, renglon);
CREATE INDEX ix_requisicion_linea_material ON abastecimiento.requisicion_linea (empresa_id, material_id);

-- ---------------------------------------------------------------------------------------------
-- Pedido a proveedor. Ciclo: borrador → enviado → recibido (todo llegó) o cerrado (se da por
-- terminado con faltantes). Se cancela en borrador, o enviado sin nada recibido (el código).
-- El precio es sin IVA por unidad capturada; la recepción lo toma de aquí.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE abastecimiento.pedido (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id      bigint      NOT NULL REFERENCES organizacion.empresa,
  folio           text        NOT NULL,
  proveedor_id    bigint      NOT NULL,
  almacen_id      bigint      NOT NULL,
  fecha           date        NOT NULL,
  fecha_entrega   date,
  estado          text        NOT NULL DEFAULT 'borrador'
                  CHECK (estado IN ('borrador', 'enviado', 'recibido', 'cerrado', 'cancelado')),
  usuario_captura bigint      NOT NULL,
  usuario_envia   bigint,
  enviado_en      timestamptz,
  cerrado_en      timestamptz,
  motivo_cierre   text        CHECK (length(btrim(motivo_cierre)) BETWEEN 1 AND 500),
  observaciones   text        CHECK (length(observaciones) <= 500),
  version         integer     NOT NULL DEFAULT 0,
  creado          timestamptz NOT NULL DEFAULT now(),
  CHECK (fecha_entrega IS NULL OR fecha_entrega >= fecha),
  CHECK (estado IN ('borrador', 'cancelado') OR (usuario_envia IS NOT NULL AND enviado_en IS NOT NULL)),
  CHECK ((estado IN ('cerrado', 'cancelado')) = (cerrado_en IS NOT NULL AND motivo_cierre IS NOT NULL)),
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, folio),
  FOREIGN KEY (empresa_id, proveedor_id) REFERENCES catalogo.proveedor (empresa_id, id),
  FOREIGN KEY (empresa_id, almacen_id) REFERENCES inventario.almacen (empresa_id, id),
  FOREIGN KEY (empresa_id, usuario_captura) REFERENCES organizacion.usuario (empresa_id, id),
  FOREIGN KEY (empresa_id, usuario_envia) REFERENCES organizacion.usuario (empresa_id, id)
);
CREATE INDEX ix_pedido_estado ON abastecimiento.pedido (empresa_id, estado, fecha);
CREATE INDEX ix_pedido_proveedor ON abastecimiento.pedido (empresa_id, proveedor_id, fecha);

CREATE TABLE abastecimiento.pedido_linea (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id        bigint        NOT NULL,
  pedido_id         bigint        NOT NULL,
  renglon           smallint      NOT NULL CHECK (renglon > 0),
  material_id       bigint        NOT NULL,
  cantidad          numeric(14,4) NOT NULL CHECK (cantidad > 0),          -- unidad de control
  cantidad_captura  numeric(14,4) NOT NULL CHECK (cantidad_captura > 0),
  unidad_captura_id bigint        NOT NULL REFERENCES catalogo.unidad_medida,
  precio            numeric(16,6) NOT NULL CHECK (precio >= 0),            -- sin IVA, por unidad capturada
  tasa_iva          numeric(5,4)  NOT NULL CHECK (tasa_iva BETWEEN 0 AND 0.16),
  observaciones     text          CHECK (length(observaciones) <= 300),
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, id, material_id),
  UNIQUE (empresa_id, pedido_id, renglon),
  FOREIGN KEY (empresa_id, pedido_id) REFERENCES abastecimiento.pedido (empresa_id, id),
  FOREIGN KEY (empresa_id, material_id) REFERENCES catalogo.material (empresa_id, id)
);

-- Qué renglones de requisición cubre cada renglón del pedido, y cuánto ya llegó para cada uno.
-- El mismo material en los dos lados (FK compuestas).
CREATE TABLE abastecimiento.pedido_requisicion (
  empresa_id           bigint        NOT NULL,
  pedido_linea_id      bigint        NOT NULL,
  requisicion_linea_id bigint        NOT NULL,
  material_id          bigint        NOT NULL,
  cantidad             numeric(14,4) NOT NULL CHECK (cantidad > 0),
  recibido             numeric(14,4) NOT NULL DEFAULT 0 CHECK (recibido >= 0 AND recibido <= cantidad),
  PRIMARY KEY (pedido_linea_id, requisicion_linea_id),
  FOREIGN KEY (empresa_id, pedido_linea_id, material_id)
    REFERENCES abastecimiento.pedido_linea (empresa_id, id, material_id) ON DELETE CASCADE,
  FOREIGN KEY (empresa_id, requisicion_linea_id, material_id)
    REFERENCES abastecimiento.requisicion_linea (empresa_id, id, material_id)
);
CREATE INDEX ix_pedido_requisicion ON abastecimiento.pedido_requisicion (empresa_id, requisicion_linea_id);

-- ---------------------------------------------------------------------------------------------
-- Reserva: lo apartado en un almacén para un renglón de requisición. Vive en inventario porque
-- el vale la consume al aplicarse en la misma transacción que mueve la existencia (ADR-012).
-- Una por renglón; su cantidad baja cuando se surte o se libera (nunca se borra).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE inventario.reserva (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id           bigint        NOT NULL,
  almacen_id           bigint        NOT NULL,
  material_id          bigint        NOT NULL,
  requisicion_linea_id bigint        NOT NULL,
  cantidad             numeric(14,4) NOT NULL DEFAULT 0 CHECK (cantidad >= 0),
  creado               timestamptz   NOT NULL DEFAULT now(),
  actualizado          timestamptz   NOT NULL DEFAULT now(),
  UNIQUE (empresa_id, requisicion_linea_id),
  FOREIGN KEY (empresa_id, almacen_id) REFERENCES inventario.almacen (empresa_id, id),
  FOREIGN KEY (empresa_id, requisicion_linea_id, material_id)
    REFERENCES abastecimiento.requisicion_linea (empresa_id, id, material_id)
);
CREATE INDEX ix_reserva_saldo ON inventario.reserva (empresa_id, almacen_id, material_id)
  WHERE cantidad > 0;

-- Lo reservado en el saldo = suma de las reservas. El saldo ya existe: quien reserva lo bloquea
-- antes (FOR UPDATE). El CHECK de saldo (reservado + prestado <= existencia) rechaza reservar de
-- más, aunque el código fallara.
CREATE FUNCTION inventario.sincronizar_reservado() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  v_fila  inventario.reserva := coalesce(NEW, OLD);
  v_delta numeric := CASE TG_OP
                       WHEN 'INSERT' THEN NEW.cantidad
                       WHEN 'DELETE' THEN -OLD.cantidad
                       ELSE NEW.cantidad - OLD.cantidad
                     END;
  v_filas integer;
BEGIN
  IF TG_OP = 'UPDATE' AND (NEW.almacen_id <> OLD.almacen_id OR NEW.material_id <> OLD.material_id
                           OR NEW.requisicion_linea_id <> OLD.requisicion_linea_id) THEN
    RAISE EXCEPTION 'una reserva no cambia de almacén, material ni renglón'
      USING ERRCODE = 'check_violation';
  END IF;
  IF v_delta <> 0 THEN
    UPDATE inventario.saldo SET reservado = reservado + v_delta, actualizado = now()
     WHERE empresa_id = v_fila.empresa_id AND almacen_id = v_fila.almacen_id
       AND material_id = v_fila.material_id;
    GET DIAGNOSTICS v_filas = ROW_COUNT;
    IF v_filas <> 1 THEN
      RAISE EXCEPTION 'no hay saldo del material % en el almacén %', v_fila.material_id,
        v_fila.almacen_id USING ERRCODE = 'foreign_key_violation';
    END IF;
  END IF;
  RETURN NULL;
END
$$;
CREATE TRIGGER trg_reservado AFTER INSERT OR UPDATE OR DELETE ON inventario.reserva
  FOR EACH ROW EXECUTE FUNCTION inventario.sincronizar_reservado();

-- ---------------------------------------------------------------------------------------------
-- Documentos del almacén ligados al abastecimiento: el vale al renglón de requisición que surte,
-- la recepción al renglón del pedido que recibe. Mismo material en los dos lados.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE inventario.movimiento_linea
  ADD COLUMN requisicion_linea_id bigint,
  ADD COLUMN pedido_linea_id bigint,
  ADD CONSTRAINT movimiento_linea_requisicion_fk FOREIGN KEY (empresa_id, requisicion_linea_id, material_id)
    REFERENCES abastecimiento.requisicion_linea (empresa_id, id, material_id),
  ADD CONSTRAINT movimiento_linea_pedido_fk FOREIGN KEY (empresa_id, pedido_linea_id, material_id)
    REFERENCES abastecimiento.pedido_linea (empresa_id, id, material_id),
  ADD CONSTRAINT movimiento_linea_requisicion_check CHECK (requisicion_linea_id IS NULL OR tipo = 'salida'),
  ADD CONSTRAINT movimiento_linea_pedido_check CHECK (pedido_linea_id IS NULL OR tipo = 'entrada');
CREATE INDEX ix_linea_requisicion ON inventario.movimiento_linea (empresa_id, requisicion_linea_id)
  WHERE requisicion_linea_id IS NOT NULL;
CREATE INDEX ix_linea_pedido ON inventario.movimiento_linea (empresa_id, pedido_linea_id)
  WHERE pedido_linea_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- Defensas en triggers: transiciones válidas y nada cambia fuera de borrador.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION abastecimiento.validar_requisicion() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  v_ciclo text[] := ARRAY['estado', 'usuario_autoriza', 'autorizada_en', 'cerrada_en',
                          'motivo_cierre', 'nota_rechazo', 'version'];
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.estado <> 'borrador' THEN
      RAISE EXCEPTION 'una requisición nace en borrador' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.estado IN ('cerrada', 'cancelada') THEN
    RAISE EXCEPTION 'la requisición % ya está %', OLD.folio, OLD.estado
      USING ERRCODE = 'restrict_violation';
  END IF;
  IF NEW.estado <> OLD.estado AND (OLD.estado, NEW.estado) NOT IN (
       ('borrador', 'pendiente'), ('borrador', 'cancelada'), ('pendiente', 'borrador'),
       ('pendiente', 'autorizada'), ('pendiente', 'cancelada'), ('autorizada', 'cerrada'),
       ('autorizada', 'cancelada')) THEN
    RAISE EXCEPTION 'transición inválida de % a %', OLD.estado, NEW.estado
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.folio <> OLD.folio OR NEW.contrato_id <> OLD.contrato_id
     OR NEW.almacen_id <> OLD.almacen_id OR NEW.usuario_captura <> OLD.usuario_captura THEN
    RAISE EXCEPTION 'el folio, el contrato, el almacén y quien capturó no cambian'
      USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.estado <> 'borrador' AND (to_jsonb(NEW) - v_ciclo) <> (to_jsonb(OLD) - v_ciclo) THEN
    RAISE EXCEPTION 'la requisición % ya se envió: solo avanza de estado', OLD.folio
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER trg_validar BEFORE INSERT OR UPDATE ON abastecimiento.requisicion
  FOR EACH ROW EXECUTE FUNCTION abastecimiento.validar_requisicion();

CREATE FUNCTION abastecimiento.validar_requisicion_linea() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  v_fila   abastecimiento.requisicion_linea := coalesce(NEW, OLD);
  v_estado text;
BEGIN
  SELECT estado INTO v_estado FROM abastecimiento.requisicion
   WHERE empresa_id = v_fila.empresa_id AND id = v_fila.requisicion_id;
  IF v_estado IS DISTINCT FROM 'borrador' THEN
    RAISE EXCEPTION 'los renglones solo cambian en borrador (la requisición está %)', v_estado
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN coalesce(NEW, OLD);
END
$$;
CREATE TRIGGER trg_validar BEFORE INSERT OR UPDATE OR DELETE ON abastecimiento.requisicion_linea
  FOR EACH ROW EXECUTE FUNCTION abastecimiento.validar_requisicion_linea();

CREATE FUNCTION abastecimiento.validar_pedido() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  v_ciclo text[] := ARRAY['estado', 'usuario_envia', 'enviado_en', 'cerrado_en', 'motivo_cierre',
                          'version'];
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.estado <> 'borrador' THEN
      RAISE EXCEPTION 'un pedido nace en borrador' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.estado IN ('recibido', 'cerrado', 'cancelado') THEN
    RAISE EXCEPTION 'el pedido % ya está %', OLD.folio, OLD.estado
      USING ERRCODE = 'restrict_violation';
  END IF;
  IF NEW.estado <> OLD.estado AND (OLD.estado, NEW.estado) NOT IN (
       ('borrador', 'enviado'), ('borrador', 'cancelado'), ('enviado', 'recibido'),
       ('enviado', 'cerrado'), ('enviado', 'cancelado')) THEN
    RAISE EXCEPTION 'transición inválida de % a %', OLD.estado, NEW.estado
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.folio <> OLD.folio OR NEW.usuario_captura <> OLD.usuario_captura THEN
    RAISE EXCEPTION 'el folio y quien capturó no cambian' USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.estado <> 'borrador' AND (to_jsonb(NEW) - v_ciclo) <> (to_jsonb(OLD) - v_ciclo) THEN
    RAISE EXCEPTION 'el pedido % ya se envió: solo avanza de estado', OLD.folio
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER trg_validar BEFORE INSERT OR UPDATE ON abastecimiento.pedido
  FOR EACH ROW EXECUTE FUNCTION abastecimiento.validar_pedido();

CREATE FUNCTION abastecimiento.validar_pedido_linea() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  v_fila   abastecimiento.pedido_linea := coalesce(NEW, OLD);
  v_estado text;
BEGIN
  SELECT estado INTO v_estado FROM abastecimiento.pedido
   WHERE empresa_id = v_fila.empresa_id AND id = v_fila.pedido_id;
  IF v_estado IS DISTINCT FROM 'borrador' THEN
    RAISE EXCEPTION 'los renglones solo cambian en borrador (el pedido está %)', v_estado
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN coalesce(NEW, OLD);
END
$$;
CREATE TRIGGER trg_validar BEFORE INSERT OR UPDATE OR DELETE ON abastecimiento.pedido_linea
  FOR EACH ROW EXECUTE FUNCTION abastecimiento.validar_pedido_linea();

-- La liga pedido ↔ requisición se arma en borrador; después solo crece lo recibido.
CREATE FUNCTION abastecimiento.validar_pedido_requisicion() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  v_fila   abastecimiento.pedido_requisicion := coalesce(NEW, OLD);
  v_estado text;
BEGIN
  SELECT p.estado INTO v_estado
    FROM abastecimiento.pedido_linea l
    JOIN abastecimiento.pedido p ON p.empresa_id = l.empresa_id AND p.id = l.pedido_id
   WHERE l.empresa_id = v_fila.empresa_id AND l.id = v_fila.pedido_linea_id;
  IF TG_OP IN ('INSERT', 'DELETE') THEN
    IF v_estado IS DISTINCT FROM 'borrador' THEN
      RAISE EXCEPTION 'la liga con la requisición solo cambia en borrador (el pedido está %)',
        v_estado USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN coalesce(NEW, OLD);
  END IF;
  IF NEW.cantidad <> OLD.cantidad OR NEW.material_id <> OLD.material_id
     OR NEW.requisicion_linea_id <> OLD.requisicion_linea_id
     OR NEW.pedido_linea_id <> OLD.pedido_linea_id THEN
    IF v_estado IS DISTINCT FROM 'borrador' THEN
      RAISE EXCEPTION 'la liga con la requisición solo cambia en borrador'
        USING ERRCODE = 'restrict_violation';
    END IF;
  END IF;
  IF NEW.recibido < OLD.recibido THEN
    RAISE EXCEPTION 'lo recibido no baja' USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.recibido <> OLD.recibido AND v_estado IS DISTINCT FROM 'enviado' THEN
    RAISE EXCEPTION 'solo se recibe un pedido enviado' USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER trg_validar BEFORE INSERT OR UPDATE OR DELETE ON abastecimiento.pedido_requisicion
  FOR EACH ROW EXECUTE FUNCTION abastecimiento.validar_pedido_requisicion();

-- ---------------------------------------------------------------------------------------------
-- Seguridad por empresa, permisos de app y bitácora (regla 10).
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['requisicion', 'requisicion_linea', 'pedido', 'pedido_linea',
                           'pedido_requisicion']
  LOOP
    EXECUTE format('ALTER TABLE abastecimiento.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE abastecimiento.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY por_empresa ON abastecimiento.%I'
                   ' USING (empresa_id = compartido.empresa_actual())'
                   ' WITH CHECK (empresa_id = compartido.empresa_actual())', t);
  END LOOP;
END
$$;
ALTER TABLE inventario.reserva ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventario.reserva FORCE ROW LEVEL SECURITY;
CREATE POLICY por_empresa ON inventario.reserva
  USING (empresa_id = compartido.empresa_actual())
  WITH CHECK (empresa_id = compartido.empresa_actual());

GRANT SELECT, INSERT, UPDATE ON abastecimiento.requisicion, abastecimiento.pedido TO app;
GRANT SELECT, INSERT, UPDATE, DELETE ON abastecimiento.requisicion_linea,
  abastecimiento.pedido_linea, abastecimiento.pedido_requisicion TO app;
-- Una reserva no se borra: se libera dejando su cantidad en cero.
GRANT SELECT, INSERT, UPDATE ON inventario.reserva TO app;

CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON abastecimiento.requisicion
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON abastecimiento.requisicion_linea
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON abastecimiento.pedido
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON abastecimiento.pedido_linea
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON abastecimiento.pedido_requisicion
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('requisicion_linea_id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON inventario.reserva
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');

-- ---------------------------------------------------------------------------------------------
-- Reportes (security_invoker: se leen con el RLS de quien consulta). No se materializan: una
-- vista materializada no tiene RLS y mezclaría empresas (ADR-012).
-- ---------------------------------------------------------------------------------------------

-- Costo de cada lote por clasificación, histórico + sistema. Solo Programa consume presupuesto.
CREATE VIEW reportes.v_consumo_unidad WITH (security_invoker = true) AS
  SELECT c.empresa_id, c.contrato_id, c.unidad_obra_id,
         coalesce(sum(c.importe) FILTER (WHERE k.consume_programa), 0)::numeric(18,4) AS programa,
         coalesce(sum(c.importe), 0)::numeric(18,4) AS total
    FROM reportes.v_costo_unidad c
    LEFT JOIN catalogo.clasificacion_costo k ON k.id = c.clasificacion_id
   WHERE c.unidad_obra_id IS NOT NULL
   GROUP BY c.empresa_id, c.contrato_id, c.unidad_obra_id;

-- Presupuesto de material de cada lote (en pesos, sin IVA) contra lo consumido de Programa.
CREATE VIEW reportes.v_presupuesto_unidad WITH (security_invoker = true) AS
  SELECT u.empresa_id, u.contrato_id, u.id AS unidad_obra_id, u.clave, u.prototipo_id,
         u.presupuesto_material AS presupuesto,
         coalesce(c.programa, 0)::numeric(18,4) AS consumido
    FROM estructura.unidad_obra u
    LEFT JOIN reportes.v_consumo_unidad c
           ON c.empresa_id = u.empresa_id AND c.unidad_obra_id = u.id;

-- Existencia valorizada por almacén y material (costo promedio sin IVA).
CREATE VIEW reportes.v_existencia_valorizada WITH (security_invoker = true) AS
  SELECT s.empresa_id, s.almacen_id, s.material_id, s.existencia, s.valor, s.costo_promedio,
         s.reservado, s.prestado, s.existencia - s.reservado - s.prestado AS disponible
    FROM inventario.saldo s
   WHERE s.existencia > 0 OR s.reservado > 0 OR s.prestado > 0;

GRANT SELECT ON reportes.v_consumo_unidad, reportes.v_presupuesto_unidad,
  reportes.v_existencia_valorizada TO app;

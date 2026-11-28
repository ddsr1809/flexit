-- V011 · Corte y arranque del piloto (H8, issues 41 y 54): saldo de apertura desde el conteo
-- físico, valuado y firmado; préstamos abiertos del Excel como préstamos vivos; y la foto de la
-- hoja Existencias del Excel para comparar lo contado contra lo que decía el Excel.
--
-- Reglas que viven aquí:
--   1. Un almacén abre una sola vez. La apertura nace solo si el almacén no tiene kardex, hay una
--      sola apertura vigente por almacén y el movimiento de apertura se aplica antes que cualquier
--      otro en ese almacén.
--   2. La apertura la firman dos personas distintas, coordinación y administración, y ninguna de
--      las dos abrió el conteo ni contó. Con la primera firma se congela lo que se firma
--      (apertura_linea): el conteo, los préstamos y los costos ya no cambian. Para cambiar algo se
--      retiran las firmas.
--   3. El movimiento de apertura sale de una apertura con sus dos firmas, y solo él cierra el conteo
--      de la apertura. El conteo de una apertura nunca genera ajustes de sobrante o faltante.
--   4. historico.saldo_excel es histórico como el resto (V008): inmutable, con RLS y se revierte
--      por carga. Guarda solo celdas capturadas (Inventario inicial, Material defectuoso,
--      Apartados); la existencia del Excel se recalcula: inicial + entradas − salidas entregadas.

-- ---------------------------------------------------------------------------------------------
-- 1. Foto de la hoja Existencias del Excel
-- ---------------------------------------------------------------------------------------------
CREATE TABLE historico.saldo_excel (
  id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id         bigint        NOT NULL REFERENCES organizacion.empresa,
  material_id        bigint        NOT NULL,
  inventario_inicial numeric(18,6) NOT NULL,
  fecha_inicial      date,
  defectuoso         numeric(18,6),
  apartados          numeric(18,6),
  calidad            text          NOT NULL CHECK (calidad IN ('valida', 'corregida')),
  carga_id           bigint        NOT NULL,
  numero             integer       NOT NULL,
  hoja               text          NOT NULL,
  fila_excel         integer,
  UNIQUE (empresa_id, id),
  FOREIGN KEY (empresa_id, material_id) REFERENCES catalogo.material (empresa_id, id),
  FOREIGN KEY (empresa_id, carga_id) REFERENCES migracion.carga (empresa_id, id)
);
CREATE INDEX ix_saldo_excel_material ON historico.saldo_excel (empresa_id, material_id);
CREATE INDEX ix_saldo_excel_carga ON historico.saldo_excel (carga_id);

ALTER TABLE historico.saldo_excel ENABLE ROW LEVEL SECURITY;
ALTER TABLE historico.saldo_excel FORCE ROW LEVEL SECURITY;
CREATE POLICY por_empresa ON historico.saldo_excel
  USING (empresa_id = compartido.empresa_actual())
  WITH CHECK (empresa_id = compartido.empresa_actual());
CREATE TRIGGER trg_inmutable BEFORE UPDATE OR DELETE ON historico.saldo_excel
  FOR EACH ROW EXECUTE FUNCTION historico.proteger();
GRANT SELECT, INSERT ON historico.saldo_excel TO app;

-- Existencia del Excel por material, como la calcula la hoja (Existencias!J = O + M − N): inventario
-- inicial capturado + entradas − salidas entregadas (las devoluciones restan con signo negativo).
-- Es de todo el almacén del Excel: no tiene frente.
CREATE VIEW historico.v_existencia_excel WITH (security_invoker = true) AS
  WITH inicial AS (
    SELECT empresa_id, material_id, sum(inventario_inicial) AS inicial,
           sum(coalesce(defectuoso, 0)) AS defectuoso
      FROM historico.saldo_excel GROUP BY empresa_id, material_id),
  entradas AS (
    SELECT empresa_id, material_id, sum(cantidad) AS entradas
      FROM historico.entrada GROUP BY empresa_id, material_id),
  salidas AS (
    SELECT empresa_id, material_id, sum(cantidad) AS salidas
      FROM historico.salida WHERE entregado GROUP BY empresa_id, material_id)
  SELECT empresa_id, material_id,
         coalesce(i.inicial, 0) AS inicial,
         coalesce(e.entradas, 0) AS entradas,
         coalesce(s.salidas, 0) AS salidas,
         coalesce(i.defectuoso, 0) AS defectuoso,
         (coalesce(i.inicial, 0) + coalesce(e.entradas, 0) - coalesce(s.salidas, 0))
           ::numeric(18,6) AS existencia
    FROM inicial i
    FULL JOIN entradas e USING (empresa_id, material_id)
    FULL JOIN salidas s USING (empresa_id, material_id);
GRANT SELECT ON historico.v_existencia_excel TO app;

-- La reversa de una carga también borra su foto de Existencias.
CREATE OR REPLACE FUNCTION historico.revertir_carga(p_carga bigint) RETURNS integer
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
  DELETE FROM historico.saldo_excel WHERE empresa_id = v_empresa AND carga_id = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  PERFORM set_config('historico.reversa', 'off', true);
  RETURN v_total;
END
$$;
REVOKE ALL ON FUNCTION historico.revertir_carga(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION historico.revertir_carga(bigint) TO app;

-- ---------------------------------------------------------------------------------------------
-- 2. Movimiento de apertura: entra al almacén lo contado más lo prestado, al costo firmado.
--    Liga el conteo de la apertura (conteo_id).
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
  v_nombre text;
BEGIN
  FOR v_nombre IN
    SELECT conname FROM pg_constraint
     WHERE conrelid = 'inventario.movimiento'::regclass AND contype = 'c'
       AND (pg_get_constraintdef(oid) LIKE '%''ajuste_costo''::text])))'
            AND pg_get_constraintdef(oid) LIKE '%''entrada''::text, ''salida''%'
            OR pg_get_constraintdef(oid) LIKE '%conteo_id IS NULL%')
  LOOP
    EXECUTE format('ALTER TABLE inventario.movimiento DROP CONSTRAINT %I', v_nombre);
  END LOOP;
END
$$;
ALTER TABLE inventario.movimiento
  ADD CONSTRAINT movimiento_tipo_check
    CHECK (tipo IN ('entrada', 'salida', 'devolucion', 'devolucion_proveedor', 'traspaso',
                    'reasignacion', 'merma', 'ajuste_entrada', 'ajuste_salida', 'ajuste_costo',
                    'apertura')),
  ADD CONSTRAINT movimiento_conteo_check
    CHECK ((tipo IN ('ajuste_entrada', 'ajuste_salida', 'apertura')) OR conteo_id IS NULL),
  ADD CONSTRAINT movimiento_apertura_check
    CHECK (tipo <> 'apertura' OR conteo_id IS NOT NULL);

-- ---------------------------------------------------------------------------------------------
-- 3. Apertura: el documento que junta el conteo, los préstamos abiertos, los costos y las firmas.
--    abierta → en_firma (primera firma: se congela) → aplicada (segunda firma: se aplica).
--    en_firma → abierta (se retiran las firmas). abierta o en_firma → cancelada.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE inventario.apertura (
  id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id              bigint        NOT NULL REFERENCES organizacion.empresa,
  folio                   text          NOT NULL,
  almacen_id              bigint        NOT NULL,
  conteo_id               bigint        NOT NULL,
  fecha                   date          NOT NULL,
  estado                  text          NOT NULL DEFAULT 'abierta'
                          CHECK (estado IN ('abierta', 'en_firma', 'aplicada', 'cancelada')),
  usuario_crea            bigint        NOT NULL,
  firma_coordinacion      bigint,
  firma_coordinacion_en   timestamptz,
  firma_administracion    bigint,
  firma_administracion_en timestamptz,
  movimiento_id           bigint,
  importe                 numeric(18,4) CHECK (importe >= 0),   -- valor firmado
  cancelado_en            timestamptz,
  motivo_cancelacion      text          CHECK (length(btrim(motivo_cancelacion)) BETWEEN 1 AND 500),
  observaciones           text          CHECK (length(observaciones) <= 500),
  version                 integer       NOT NULL DEFAULT 0,
  creado                  timestamptz   NOT NULL DEFAULT now(),
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, folio),
  UNIQUE (empresa_id, conteo_id),
  -- Regla 5: dos personas distintas firman.
  CHECK (firma_coordinacion IS NULL OR firma_administracion IS NULL
         OR firma_coordinacion <> firma_administracion),
  CHECK ((firma_coordinacion IS NULL) = (firma_coordinacion_en IS NULL)),
  CHECK ((firma_administracion IS NULL) = (firma_administracion_en IS NULL)),
  CHECK (estado <> 'abierta'
         OR (firma_coordinacion IS NULL AND firma_administracion IS NULL AND importe IS NULL)),
  CHECK (estado <> 'en_firma'
         OR ((firma_coordinacion IS NOT NULL OR firma_administracion IS NOT NULL)
             AND importe IS NOT NULL)),
  CHECK ((estado = 'aplicada')
         = (firma_coordinacion IS NOT NULL AND firma_administracion IS NOT NULL
            AND movimiento_id IS NOT NULL)),
  CHECK ((estado = 'cancelada') = (cancelado_en IS NOT NULL AND motivo_cancelacion IS NOT NULL)),
  FOREIGN KEY (empresa_id, almacen_id) REFERENCES inventario.almacen (empresa_id, id),
  FOREIGN KEY (empresa_id, conteo_id) REFERENCES inventario.conteo (empresa_id, id),
  FOREIGN KEY (empresa_id, movimiento_id) REFERENCES inventario.movimiento (empresa_id, id),
  FOREIGN KEY (empresa_id, usuario_crea) REFERENCES organizacion.usuario (empresa_id, id),
  FOREIGN KEY (empresa_id, firma_coordinacion) REFERENCES organizacion.usuario (empresa_id, id),
  FOREIGN KEY (empresa_id, firma_administracion) REFERENCES organizacion.usuario (empresa_id, id)
);
CREATE UNIQUE INDEX ux_apertura_vigente ON inventario.apertura (empresa_id, almacen_id)
  WHERE estado <> 'cancelada';

-- Costo capturado a mano por administración: manda sobre el ponderado del histórico y el precio
-- de referencia. Solo mientras la apertura está abierta.
CREATE TABLE inventario.apertura_costo (
  empresa_id     bigint        NOT NULL,
  apertura_id    bigint        NOT NULL,
  material_id    bigint        NOT NULL,
  costo_unitario numeric(16,6) NOT NULL CHECK (costo_unitario >= 0),
  nota           text          CHECK (length(nota) <= 200),
  usuario_id     bigint        NOT NULL,
  capturado_en   timestamptz   NOT NULL DEFAULT now(),
  PRIMARY KEY (apertura_id, material_id),
  FOREIGN KEY (empresa_id, apertura_id) REFERENCES inventario.apertura (empresa_id, id),
  FOREIGN KEY (empresa_id, material_id) REFERENCES catalogo.material (empresa_id, id),
  FOREIGN KEY (empresa_id, usuario_id) REFERENCES organizacion.usuario (empresa_id, id)
);

-- Préstamos que siguen abiertos al corte: los del Excel (origen_id = historico.prestamo.id, sin FK
-- para no ligar el inventario a una carga que se puede revertir antes del corte) o agregados a
-- mano. Listo = tiene material y responsable; al aplicar se vuelve un préstamo vivo.
CREATE TABLE inventario.apertura_prestamo (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id        bigint        NOT NULL,
  apertura_id       bigint        NOT NULL,
  origen_id         bigint,
  fila_excel        integer,
  articulo          text          NOT NULL CHECK (length(btrim(articulo)) BETWEEN 1 AND 200),
  fecha             date,
  cantidad          numeric(14,4) NOT NULL CHECK (cantidad > 0),
  responsable_texto text          CHECK (length(responsable_texto) <= 160),
  material_id       bigint,
  responsable_id    bigint,
  estado            text          NOT NULL DEFAULT 'pendiente'
                    CHECK (estado IN ('pendiente', 'listo', 'descartado')),
  motivo            text          CHECK (length(btrim(motivo)) BETWEEN 1 AND 500),
  prestamo_id       bigint,
  version           integer       NOT NULL DEFAULT 0,
  UNIQUE (empresa_id, id),
  CHECK (estado <> 'listo' OR (material_id IS NOT NULL AND responsable_id IS NOT NULL)),
  CHECK ((estado = 'descartado') = (motivo IS NOT NULL)),
  CHECK (prestamo_id IS NULL OR estado = 'listo'),
  FOREIGN KEY (empresa_id, apertura_id) REFERENCES inventario.apertura (empresa_id, id),
  FOREIGN KEY (empresa_id, material_id) REFERENCES catalogo.material (empresa_id, id),
  FOREIGN KEY (empresa_id, responsable_id) REFERENCES organizacion.persona (empresa_id, id),
  FOREIGN KEY (empresa_id, prestamo_id) REFERENCES inventario.prestamo (empresa_id, id)
);
CREATE UNIQUE INDEX ux_apertura_prestamo_origen ON inventario.apertura_prestamo
  (empresa_id, apertura_id, origen_id) WHERE origen_id IS NOT NULL;

-- Lo que se firma, congelado con la primera firma: por material, lo contado, lo prestado, el costo
-- y de dónde salió, y lo que decía el Excel (referencia). La cantidad que entra es contado +
-- prestado; sin costo no se puede firmar lo que entra.
CREATE TABLE inventario.apertura_linea (
  empresa_id       bigint        NOT NULL,
  apertura_id      bigint        NOT NULL,
  material_id      bigint        NOT NULL,
  contado          numeric(14,4) NOT NULL CHECK (contado >= 0),
  prestado         numeric(14,4) NOT NULL DEFAULT 0 CHECK (prestado >= 0),
  costo_unitario   numeric(16,6) CHECK (costo_unitario >= 0),
  origen_costo     text          CHECK (origen_costo IN ('historico', 'referencia', 'capturado')),
  importe          numeric(18,4) CHECK (importe >= 0),
  existencia_excel numeric(18,6),
  PRIMARY KEY (apertura_id, material_id),
  CHECK (contado + prestado = 0 OR costo_unitario IS NOT NULL),
  CHECK ((costo_unitario IS NULL) = (importe IS NULL)),
  CHECK ((costo_unitario IS NULL) = (origen_costo IS NULL)),
  FOREIGN KEY (empresa_id, apertura_id) REFERENCES inventario.apertura (empresa_id, id),
  FOREIGN KEY (empresa_id, material_id) REFERENCES catalogo.material (empresa_id, id)
);

-- ---------------------------------------------------------------------------------------------
-- Defensas
-- ---------------------------------------------------------------------------------------------

-- Apertura: nace sobre un almacén sin kardex y con su propio conteo abierto; transiciones válidas;
-- quien abrió el conteo o contó no firma; aplicada o cancelada ya no cambia.
CREATE FUNCTION inventario.validar_apertura() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  v_conteo inventario.conteo;
BEGIN
  SELECT * INTO v_conteo FROM inventario.conteo
   WHERE empresa_id = NEW.empresa_id AND id = NEW.conteo_id;
  IF TG_OP = 'INSERT' THEN
    IF NEW.estado <> 'abierta' THEN
      RAISE EXCEPTION 'una apertura nace abierta' USING ERRCODE = 'check_violation';
    END IF;
    IF v_conteo.almacen_id IS DISTINCT FROM NEW.almacen_id OR v_conteo.estado <> 'abierto' THEN
      RAISE EXCEPTION 'el conteo de la apertura es del mismo almacén y está abierto'
        USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM inventario.kardex
                WHERE empresa_id = NEW.empresa_id AND almacen_id = NEW.almacen_id) THEN
      RAISE EXCEPTION 'YA_TIENE_MOVIMIENTOS: el almacén ya tiene movimientos, no se abre otra vez'
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF OLD.estado IN ('aplicada', 'cancelada') THEN
    RAISE EXCEPTION 'la apertura % ya está %', OLD.folio, OLD.estado
      USING ERRCODE = 'restrict_violation';
  END IF;
  IF NEW.folio <> OLD.folio OR NEW.almacen_id <> OLD.almacen_id OR NEW.conteo_id <> OLD.conteo_id
     OR NEW.usuario_crea <> OLD.usuario_crea OR NEW.fecha <> OLD.fecha THEN
    RAISE EXCEPTION 'el folio, el almacén, el conteo, la fecha y quien abrió no cambian'
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.estado <> OLD.estado AND (OLD.estado, NEW.estado) NOT IN (
       ('abierta', 'en_firma'), ('abierta', 'cancelada'), ('en_firma', 'abierta'),
       ('en_firma', 'aplicada'), ('en_firma', 'cancelada')) THEN
    RAISE EXCEPTION 'transición inválida de % a %', OLD.estado, NEW.estado
      USING ERRCODE = 'check_violation';
  END IF;
  -- Una firma puesta no cambia de dueño; se retira solo volviendo a abierta.
  IF NEW.estado <> 'abierta'
     AND ((OLD.firma_coordinacion IS NOT NULL
           AND NEW.firma_coordinacion IS DISTINCT FROM OLD.firma_coordinacion)
          OR (OLD.firma_administracion IS NOT NULL
              AND NEW.firma_administracion IS DISTINCT FROM OLD.firma_administracion)) THEN
    RAISE EXCEPTION 'una firma no se cambia: se retiran las firmas' USING ERRCODE = 'check_violation';
  END IF;
  IF (NEW.firma_coordinacion IS NOT NULL
      AND NEW.firma_coordinacion IS DISTINCT FROM OLD.firma_coordinacion
      AND (NEW.firma_coordinacion = v_conteo.usuario_crea
           OR EXISTS (SELECT 1 FROM inventario.conteo_linea
                       WHERE empresa_id = NEW.empresa_id AND conteo_id = NEW.conteo_id
                         AND contado_por = NEW.firma_coordinacion)))
     OR (NEW.firma_administracion IS NOT NULL
         AND NEW.firma_administracion IS DISTINCT FROM OLD.firma_administracion
         AND (NEW.firma_administracion = v_conteo.usuario_crea
              OR EXISTS (SELECT 1 FROM inventario.conteo_linea
                          WHERE empresa_id = NEW.empresa_id AND conteo_id = NEW.conteo_id
                            AND contado_por = NEW.firma_administracion))) THEN
    RAISE EXCEPTION 'FIRMA_DE_QUIEN_CONTO: quien abrió el conteo o contó no firma la apertura'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER trg_validar BEFORE INSERT OR UPDATE ON inventario.apertura
  FOR EACH ROW EXECUTE FUNCTION inventario.validar_apertura();

-- Costos y préstamos cambian solo con la apertura abierta. Al aplicar, al préstamo de la apertura
-- solo se le liga el préstamo vivo que creó.
CREATE FUNCTION inventario.validar_apertura_detalle() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  v_empresa  bigint := coalesce(NEW.empresa_id, OLD.empresa_id);
  v_apertura bigint := coalesce(NEW.apertura_id, OLD.apertura_id);
  v_estado   text;
BEGIN
  SELECT estado INTO v_estado FROM inventario.apertura
   WHERE empresa_id = v_empresa AND id = v_apertura;
  -- Sin apertura visible (otra empresa) deciden el RLS y la FK.
  IF v_estado IS NULL OR v_estado = 'abierta' THEN
    RETURN coalesce(NEW, OLD);
  END IF;
  IF TG_TABLE_NAME = 'apertura_prestamo' AND TG_OP = 'UPDATE' AND v_estado = 'en_firma' THEN
    IF to_jsonb(OLD) ->> 'prestamo_id' IS NULL AND to_jsonb(NEW) ->> 'prestamo_id' IS NOT NULL
       AND (to_jsonb(NEW) - ARRAY['prestamo_id', 'version'])
           = (to_jsonb(OLD) - ARRAY['prestamo_id', 'version']) THEN
      RETURN NEW;
    END IF;
  END IF;
  -- Retirar las firmas borra lo congelado (la apertura ya volvió a abierta en ese caso).
  RAISE EXCEPTION 'la apertura está %: lo firmado no cambia (retira las firmas)', v_estado
    USING ERRCODE = 'restrict_violation';
END
$$;
CREATE TRIGGER trg_validar BEFORE INSERT OR UPDATE OR DELETE ON inventario.apertura_costo
  FOR EACH ROW EXECUTE FUNCTION inventario.validar_apertura_detalle();
CREATE TRIGGER trg_validar BEFORE INSERT OR UPDATE OR DELETE ON inventario.apertura_prestamo
  FOR EACH ROW EXECUTE FUNCTION inventario.validar_apertura_detalle();

-- Lo congelado se escribe al pasar a en_firma (todavía abierta) y se borra al retirar las firmas
-- (ya abierta): solo cambia con la apertura abierta, y nunca se edita.
CREATE FUNCTION inventario.validar_apertura_linea() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  v_fila   inventario.apertura_linea := coalesce(NEW, OLD);
  v_estado text;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    RAISE EXCEPTION 'lo firmado no se edita' USING ERRCODE = 'restrict_violation';
  END IF;
  SELECT estado INTO v_estado FROM inventario.apertura
   WHERE empresa_id = v_fila.empresa_id AND id = v_fila.apertura_id;
  IF v_estado IS NOT NULL AND v_estado <> 'abierta' THEN
    RAISE EXCEPTION 'la apertura está %: lo firmado no cambia', v_estado
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN coalesce(NEW, OLD);
END
$$;
CREATE TRIGGER trg_validar BEFORE INSERT OR UPDATE OR DELETE ON inventario.apertura_linea
  FOR EACH ROW EXECUTE FUNCTION inventario.validar_apertura_linea();

-- El conteo de una apertura: se cuenta mientras la apertura está abierta; se cierra solo con las
-- dos firmas y se cancela solo con la apertura.
CREATE FUNCTION inventario.validar_conteo_apertura() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  v_apertura inventario.apertura;
BEGIN
  IF TG_TABLE_NAME = 'conteo_linea' THEN
    SELECT * INTO v_apertura FROM inventario.apertura
     WHERE empresa_id = coalesce(NEW.empresa_id, OLD.empresa_id)
       AND conteo_id = coalesce(NEW.conteo_id, OLD.conteo_id);
    IF FOUND AND v_apertura.estado <> 'abierta' THEN
      RAISE EXCEPTION 'el conteo es de la apertura %, que está %: lo contado ya no cambia',
        v_apertura.folio, v_apertura.estado USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN coalesce(NEW, OLD);
  END IF;
  IF NEW.estado = OLD.estado THEN
    RETURN NEW;
  END IF;
  SELECT * INTO v_apertura FROM inventario.apertura
   WHERE empresa_id = NEW.empresa_id AND conteo_id = NEW.id;
  IF NOT FOUND THEN
    RETURN NEW;
  END IF;
  IF NEW.estado = 'cerrado' AND NOT (v_apertura.estado = 'en_firma'
       AND v_apertura.firma_coordinacion IS NOT NULL
       AND v_apertura.firma_administracion IS NOT NULL) THEN
    RAISE EXCEPTION 'CONTEO_DE_APERTURA: el conteo de la apertura % se cierra con sus dos firmas',
      v_apertura.folio USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.estado = 'cancelado' AND v_apertura.estado <> 'cancelada' THEN
    RAISE EXCEPTION 'CONTEO_DE_APERTURA: el conteo de la apertura % se cancela con la apertura',
      v_apertura.folio USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER trg_apertura BEFORE UPDATE ON inventario.conteo
  FOR EACH ROW EXECUTE FUNCTION inventario.validar_conteo_apertura();
CREATE TRIGGER trg_apertura BEFORE INSERT OR UPDATE OR DELETE ON inventario.conteo_linea
  FOR EACH ROW EXECUTE FUNCTION inventario.validar_conteo_apertura();

-- Movimientos y apertura: el de apertura sale de una apertura con dos firmas sobre un almacén sin
-- kardex; el conteo de una apertura no genera ajustes; y mientras la apertura no se aplica, el
-- almacén no aplica otra cosa.
CREATE FUNCTION inventario.validar_movimiento_apertura() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  v_apertura inventario.apertura;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.tipo = 'apertura' THEN
      SELECT * INTO v_apertura FROM inventario.apertura
       WHERE empresa_id = NEW.empresa_id AND conteo_id = NEW.conteo_id;
      IF NOT FOUND OR v_apertura.almacen_id <> NEW.almacen_id OR v_apertura.estado <> 'en_firma'
         OR v_apertura.firma_coordinacion IS NULL OR v_apertura.firma_administracion IS NULL THEN
        RAISE EXCEPTION 'APERTURA_SIN_FIRMAS: el saldo de apertura sale de una apertura con sus dos firmas'
          USING ERRCODE = 'check_violation';
      END IF;
      IF EXISTS (SELECT 1 FROM inventario.kardex
                  WHERE empresa_id = NEW.empresa_id AND almacen_id = NEW.almacen_id) THEN
        RAISE EXCEPTION 'YA_TIENE_MOVIMIENTOS: el almacén ya tiene movimientos'
          USING ERRCODE = 'check_violation';
      END IF;
    ELSIF NEW.conteo_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM inventario.apertura
             WHERE empresa_id = NEW.empresa_id AND conteo_id = NEW.conteo_id) THEN
      RAISE EXCEPTION 'CONTEO_DE_APERTURA: el conteo de una apertura no genera ajustes'
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.estado = 'aplicado' AND OLD.estado <> 'aplicado' AND NEW.tipo <> 'apertura'
     AND EXISTS (SELECT 1 FROM inventario.apertura
                  WHERE empresa_id = NEW.empresa_id
                    AND almacen_id IN (NEW.almacen_id, NEW.almacen_destino_id)
                    AND estado IN ('abierta', 'en_firma')) THEN
    RAISE EXCEPTION 'ALMACEN_EN_APERTURA: el almacén aplica movimientos después de su saldo de apertura'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER trg_apertura BEFORE INSERT OR UPDATE ON inventario.movimiento
  FOR EACH ROW EXECUTE FUNCTION inventario.validar_movimiento_apertura();

-- ---------------------------------------------------------------------------------------------
-- Seguridad por empresa, permisos de app y bitácora (regla 10)
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['apertura', 'apertura_costo', 'apertura_prestamo', 'apertura_linea']
  LOOP
    EXECUTE format('ALTER TABLE inventario.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE inventario.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY por_empresa ON inventario.%I'
                   ' USING (empresa_id = compartido.empresa_actual())'
                   ' WITH CHECK (empresa_id = compartido.empresa_actual())', t);
  END LOOP;
END
$$;

GRANT SELECT, INSERT, UPDATE ON inventario.apertura, inventario.apertura_prestamo TO app;
GRANT SELECT, INSERT, UPDATE, DELETE ON inventario.apertura_costo TO app;
GRANT SELECT, INSERT, DELETE ON inventario.apertura_linea TO app;

CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON inventario.apertura
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON inventario.apertura_costo
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('apertura_id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON inventario.apertura_prestamo
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON inventario.apertura_linea
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('apertura_id');

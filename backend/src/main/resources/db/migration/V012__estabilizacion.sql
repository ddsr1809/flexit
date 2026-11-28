-- V012 · Estabilización (H9): incidentes del piloto y las defensas del primer cierre de mes.
--
-- Reglas que viven aquí:
--   1. Cualquier usuario reporta un incidente con su severidad (crítica, mayor o menor). Lo
--      atiende quien tiene incidente:atender (administración), que puede cambiar la severidad,
--      ligarlo a un issue de GitHub, resolverlo o descartarlo. Resuelto o descartado se reabre con
--      una nota. Cada cambio deja una nota que no se edita ni se borra.
--   2. Un mes no se cierra si le quedan documentos sin aplicar ni cancelar, ni si hay un conteo o
--      una apertura abiertos con fecha hasta el fin de ese mes: después del cierre ya no se
--      podrían cerrar ni aplicar (el kardex no acepta fechas de un mes cerrado). La aplicación lo
--      revisa antes; aquí se garantiza.

-- ---------------------------------------------------------------------------------------------
-- 1. Incidentes
-- ---------------------------------------------------------------------------------------------
CREATE SCHEMA soporte;
GRANT USAGE ON SCHEMA soporte TO app;

CREATE TABLE soporte.incidente (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id     bigint      NOT NULL REFERENCES organizacion.empresa,
  folio          text        NOT NULL,
  severidad      text        NOT NULL CHECK (severidad IN ('critica', 'mayor', 'menor')),
  estado         text        NOT NULL DEFAULT 'abierto'
                 CHECK (estado IN ('abierto', 'resuelto', 'descartado')),
  titulo         text        NOT NULL CHECK (length(btrim(titulo)) BETWEEN 3 AND 120),
  descripcion    text        NOT NULL CHECK (length(btrim(descripcion)) BETWEEN 1 AND 2000),
  almacen_id     bigint,                                   -- dónde pasó, si fue en un almacén
  pantalla       text        CHECK (length(pantalla) <= 200),   -- ruta del frontend
  peticion       text        CHECK (peticion ~ '^[A-Za-z0-9-]{1,64}$'),   -- X-Request-Id
  documento      text        CHECK (length(btrim(documento)) BETWEEN 1 AND 40),  -- folio relacionado
  issue          integer     CHECK (issue > 0),           -- número de issue en GitHub
  reportado_por  bigint      NOT NULL,
  reportado_en   timestamptz NOT NULL DEFAULT now(),
  cerrado_por    bigint,
  cerrado_en     timestamptz,
  resolucion     text        CHECK (length(btrim(resolucion)) BETWEEN 1 AND 1000),
  reaperturas    integer     NOT NULL DEFAULT 0 CHECK (reaperturas >= 0),
  version        integer     NOT NULL DEFAULT 0,
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, folio),
  -- Abierto no tiene cierre; resuelto o descartado lo tiene completo.
  CHECK ((estado = 'abierto')
         = (cerrado_por IS NULL AND cerrado_en IS NULL AND resolucion IS NULL)),
  CHECK (estado = 'abierto'
         OR (cerrado_por IS NOT NULL AND cerrado_en IS NOT NULL AND resolucion IS NOT NULL)),
  CHECK (cerrado_en IS NULL OR cerrado_en >= reportado_en),
  FOREIGN KEY (empresa_id, almacen_id) REFERENCES inventario.almacen (empresa_id, id),
  FOREIGN KEY (empresa_id, reportado_por) REFERENCES organizacion.usuario (empresa_id, id),
  FOREIGN KEY (empresa_id, cerrado_por) REFERENCES organizacion.usuario (empresa_id, id)
);
CREATE INDEX ix_incidente_estado ON soporte.incidente (empresa_id, estado, severidad);
CREATE INDEX ix_incidente_reporta ON soporte.incidente (empresa_id, reportado_por);

-- Seguimiento: lo que se hizo y cada cambio de estado o severidad. Solo se agrega.
CREATE TABLE soporte.incidente_nota (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id    bigint      NOT NULL,
  incidente_id  bigint      NOT NULL,
  tipo          text        NOT NULL
                CHECK (tipo IN ('nota', 'severidad', 'issue', 'resuelto', 'descartado', 'reabierto')),
  texto         text        NOT NULL CHECK (length(btrim(texto)) BETWEEN 1 AND 1000),
  usuario_id    bigint      NOT NULL,
  creado        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (empresa_id, id),
  FOREIGN KEY (empresa_id, incidente_id) REFERENCES soporte.incidente (empresa_id, id),
  FOREIGN KEY (empresa_id, usuario_id) REFERENCES organizacion.usuario (empresa_id, id)
);
CREATE INDEX ix_incidente_nota ON soporte.incidente_nota (empresa_id, incidente_id, id);

-- Transiciones: abierto → resuelto | descartado; resuelto | descartado → abierto (reabrir, que
-- borra el cierre y suma una reapertura). El folio, quién y cuándo reportó y lo reportado no
-- cambian.
CREATE FUNCTION soporte.validar_incidente() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.estado <> 'abierto' OR NEW.reaperturas <> 0 THEN
      RAISE EXCEPTION 'un incidente nace abierto' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.folio <> OLD.folio OR NEW.reportado_por <> OLD.reportado_por
     OR NEW.reportado_en <> OLD.reportado_en OR NEW.titulo <> OLD.titulo
     OR NEW.descripcion <> OLD.descripcion
     OR NEW.almacen_id IS DISTINCT FROM OLD.almacen_id
     OR NEW.pantalla IS DISTINCT FROM OLD.pantalla
     OR NEW.peticion IS DISTINCT FROM OLD.peticion
     OR NEW.documento IS DISTINCT FROM OLD.documento THEN
    RAISE EXCEPTION 'lo reportado no cambia: se agrega una nota' USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.estado <> OLD.estado THEN
    IF OLD.estado = 'abierto' AND NEW.estado IN ('resuelto', 'descartado') THEN
      NULL;
    ELSIF OLD.estado IN ('resuelto', 'descartado') AND NEW.estado = 'abierto' THEN
      IF NEW.reaperturas <> OLD.reaperturas + 1 THEN
        RAISE EXCEPTION 'reabrir suma una reapertura' USING ERRCODE = 'check_violation';
      END IF;
    ELSE
      RAISE EXCEPTION 'transición inválida de % a %', OLD.estado, NEW.estado
        USING ERRCODE = 'check_violation';
    END IF;
  ELSIF NEW.reaperturas <> OLD.reaperturas THEN
    RAISE EXCEPTION 'las reaperturas cambian solo al reabrir' USING ERRCODE = 'check_violation';
  ELSIF NEW.estado <> 'abierto' AND (NEW.severidad <> OLD.severidad
          OR NEW.resolucion IS DISTINCT FROM OLD.resolucion
          OR NEW.cerrado_por IS DISTINCT FROM OLD.cerrado_por) THEN
    RAISE EXCEPTION 'un incidente cerrado no cambia: se reabre' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER trg_validar BEFORE INSERT OR UPDATE ON soporte.incidente
  FOR EACH ROW EXECUTE FUNCTION soporte.validar_incidente();

ALTER TABLE soporte.incidente ENABLE ROW LEVEL SECURITY;
ALTER TABLE soporte.incidente FORCE ROW LEVEL SECURITY;
CREATE POLICY por_empresa ON soporte.incidente
  USING (empresa_id = compartido.empresa_actual())
  WITH CHECK (empresa_id = compartido.empresa_actual());
ALTER TABLE soporte.incidente_nota ENABLE ROW LEVEL SECURITY;
ALTER TABLE soporte.incidente_nota FORCE ROW LEVEL SECURITY;
CREATE POLICY por_empresa ON soporte.incidente_nota
  USING (empresa_id = compartido.empresa_actual())
  WITH CHECK (empresa_id = compartido.empresa_actual());

-- Un incidente no se borra; una nota no se edita ni se borra.
GRANT SELECT, INSERT, UPDATE ON soporte.incidente TO app;
GRANT SELECT, INSERT ON soporte.incidente_nota TO app;

CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON soporte.incidente
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON soporte.incidente_nota
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('incidente_id');

-- ---------------------------------------------------------------------------------------------
-- 2. Cierre de mes: no deja documentos, conteos ni aperturas que ya no se podrían terminar
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION inventario.validar_cierre() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  v_inicio date := make_date(NEW.anio, NEW.mes, 1);
  v_fin    date := (make_date(NEW.anio, NEW.mes, 1) + interval '1 month' - interval '1 day')::date;
  v_folio  text;
BEGIN
  SELECT folio INTO v_folio FROM inventario.movimiento
   WHERE empresa_id = NEW.empresa_id AND fecha BETWEEN v_inicio AND v_fin
     AND estado IN ('borrador', 'pendiente', 'autorizado')
   ORDER BY fecha, id LIMIT 1;
  IF FOUND THEN
    RAISE EXCEPTION 'MES_CON_PENDIENTES: % sigue sin aplicar ni cancelar', v_folio
      USING ERRCODE = 'check_violation';
  END IF;
  -- La apertura antes que el conteo: el conteo de una apertura sigue abierto hasta aplicarla.
  SELECT folio INTO v_folio FROM inventario.apertura
   WHERE empresa_id = NEW.empresa_id AND estado IN ('abierta', 'en_firma') AND fecha <= v_fin
   ORDER BY fecha, id LIMIT 1;
  IF FOUND THEN
    RAISE EXCEPTION 'MES_CON_APERTURA_ABIERTA: la apertura % no se ha aplicado', v_folio
      USING ERRCODE = 'check_violation';
  END IF;
  SELECT folio INTO v_folio FROM inventario.conteo
   WHERE empresa_id = NEW.empresa_id AND estado = 'abierto' AND fecha <= v_fin
   ORDER BY fecha, id LIMIT 1;
  IF FOUND THEN
    RAISE EXCEPTION 'MES_CON_CONTEO_ABIERTO: el conteo % sigue abierto', v_folio
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER trg_validar BEFORE INSERT ON inventario.periodo
  FOR EACH ROW EXECUTE FUNCTION inventario.validar_cierre();

-- V006 · Centro de migración desde Excel (H3) y registro de personas.
--
-- Cada carga lleva una hoja del Excel por cinco pasos: subir, normalizar, validar, conciliar y
-- confirmar. Lo que el sistema no resuelve solo queda en cuarentena hasta que una persona decide;
-- su decisión se guarda como alias (vale para todas las cargas) o como decisión del renglón.
--
-- Tres capas:
--   migracion.archivo / carga / fila / incidencia   lo que se subió y cómo se leyó
--   migracion.alias / decision                       lo que decidieron las personas
--   migracion.registro + origen_carga                qué creó cada carga, para poder revertirla
--
-- Revertir una carga confirmada borra lo que creó. La app no tiene DELETE sobre las tablas de
-- estructura, catálogo ni personas: borra por medio de funciones SECURITY DEFINER que solo tocan
-- renglones de la empresa en curso marcados con esa carga. Si algo ya usa esos renglones (un lote
-- que se capturó a mano sobre un contrato importado), la FK lo impide y no se borra nada.

-- ---------------------------------------------------------------------------------------------
-- Personas: trabajadores, solicitantes y cuadrillas. Las comparten el SUA, la asistencia, el
-- destajo y las salidas de almacén. Datos personales: solo las ve quien tiene personas:administrar.
-- NSS y CURP se cifrarán por columna cuando llegue el módulo de personal; hoy el SUA no los trae.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE organizacion.persona (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id    bigint      NOT NULL REFERENCES organizacion.empresa,
  nombre        text        NOT NULL CHECK (length(btrim(nombre)) BETWEEN 1 AND 160),
  nombre_normal text        GENERATED ALWAYS AS (compartido.normalizar(nombre)) STORED,
  nss           text        CHECK (nss ~ '^[0-9]{11}$'),
  curp          text        CHECK (curp ~ '^[A-Z][AEIOUX][A-Z]{2}[0-9]{6}[HM][A-Z]{5}[A-Z0-9][0-9]$'),
  -- Marcada para revisión: se dio de alta desde un apodo o puede estar repetida.
  revisar       boolean     NOT NULL DEFAULT false,
  nota          text        CHECK (length(nota) <= 300),
  activa        boolean     NOT NULL DEFAULT true,
  version       integer     NOT NULL DEFAULT 0,
  creada        timestamptz NOT NULL DEFAULT now(),
  origen_carga  bigint,
  UNIQUE (empresa_id, id)
);
CREATE UNIQUE INDEX ux_persona_nss ON organizacion.persona (empresa_id, nss) WHERE nss IS NOT NULL;
CREATE UNIQUE INDEX ux_persona_curp ON organizacion.persona (empresa_id, curp) WHERE curp IS NOT NULL;
CREATE INDEX ix_persona_nombre ON organizacion.persona (empresa_id, nombre_normal);
CREATE INDEX ix_persona_nombre_trgm ON organizacion.persona USING gin (nombre_normal gin_trgm_ops);

ALTER TABLE organizacion.persona ENABLE ROW LEVEL SECURITY;
ALTER TABLE organizacion.persona FORCE ROW LEVEL SECURITY;
CREATE POLICY por_empresa ON organizacion.persona
  USING (empresa_id = compartido.empresa_actual())
  WITH CHECK (empresa_id = compartido.empresa_actual());
-- Sin DELETE: una persona se desactiva. Solo la reversa de una carga la borra (función abajo).
GRANT SELECT, INSERT, UPDATE ON organizacion.persona TO app;
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON organizacion.persona
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id', 'nombre_normal');

-- ---------------------------------------------------------------------------------------------
-- Qué carga creó cada registro maestro. Sin FK a migracion: estructura y catálogo no dependen
-- de la migración; la columna solo sirve para revertir.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE estructura.frente ADD COLUMN origen_carga bigint;
ALTER TABLE estructura.contrato ADD COLUMN origen_carga bigint;
ALTER TABLE estructura.prototipo ADD COLUMN origen_carga bigint;
ALTER TABLE estructura.unidad_obra ADD COLUMN origen_carga bigint;
ALTER TABLE estructura.paquete ADD COLUMN origen_carga bigint;
ALTER TABLE estructura.paquete_equivalencia ADD COLUMN origen_carga bigint;
ALTER TABLE estructura.contrato_paquete ADD COLUMN origen_carga bigint;
ALTER TABLE catalogo.material ADD COLUMN origen_carga bigint;
ALTER TABLE catalogo.proveedor ADD COLUMN origen_carga bigint;

-- ---------------------------------------------------------------------------------------------
-- Funciones de reversa. Corren como migrador (dueño, con BYPASSRLS), así que filtran la empresa
-- en curso de forma explícita; sin empresa en el contexto no borran nada. El orden borra primero
-- lo que depende de lo demás. Una FK violada aborta todo: la carga queda como estaba.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION estructura.revertir_carga(p_carga bigint) RETURNS integer
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
  DELETE FROM estructura.contrato_paquete WHERE empresa_id = v_empresa AND origen_carga = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  DELETE FROM estructura.paquete_equivalencia WHERE empresa_id = v_empresa AND origen_carga = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  -- Paquetes: los conceptos (con padre) antes que las secciones.
  DELETE FROM estructura.paquete
   WHERE empresa_id = v_empresa AND origen_carga = p_carga AND padre_id IS NOT NULL;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  DELETE FROM estructura.paquete WHERE empresa_id = v_empresa AND origen_carga = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  DELETE FROM estructura.unidad_obra WHERE empresa_id = v_empresa AND origen_carga = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  DELETE FROM estructura.prototipo WHERE empresa_id = v_empresa AND origen_carga = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  DELETE FROM estructura.contrato WHERE empresa_id = v_empresa AND origen_carga = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  DELETE FROM estructura.frente WHERE empresa_id = v_empresa AND origen_carga = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  RETURN v_total;
END
$$;

CREATE FUNCTION catalogo.revertir_carga(p_carga bigint) RETURNS integer
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
  DELETE FROM catalogo.material WHERE empresa_id = v_empresa AND origen_carga = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  DELETE FROM catalogo.proveedor WHERE empresa_id = v_empresa AND origen_carga = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT; v_total := v_total + v_filas;
  RETURN v_total;
END
$$;

CREATE FUNCTION organizacion.revertir_carga_personas(p_carga bigint) RETURNS integer
  LANGUAGE plpgsql SECURITY DEFINER
  SET search_path = pg_catalog, pg_temp
  AS $$
DECLARE
  v_empresa bigint := compartido.empresa_actual();
  v_filas   integer;
BEGIN
  IF v_empresa IS NULL THEN
    RAISE EXCEPTION 'No hay empresa en el contexto' USING ERRCODE = 'insufficient_privilege';
  END IF;
  DELETE FROM organizacion.persona WHERE empresa_id = v_empresa AND origen_carga = p_carga;
  GET DIAGNOSTICS v_filas = ROW_COUNT;
  RETURN v_filas;
END
$$;

REVOKE ALL ON FUNCTION estructura.revertir_carga(bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION catalogo.revertir_carga(bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION organizacion.revertir_carga_personas(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION estructura.revertir_carga(bigint) TO app;
GRANT EXECUTE ON FUNCTION catalogo.revertir_carga(bigint) TO app;
GRANT EXECUTE ON FUNCTION organizacion.revertir_carga_personas(bigint) TO app;

-- ---------------------------------------------------------------------------------------------
-- Esquema de la migración
-- ---------------------------------------------------------------------------------------------
CREATE SCHEMA migracion;
GRANT USAGE ON SCHEMA migracion TO app;

-- El archivo tal como se subió. Mismo sha256 = mismo archivo, aunque cambie el nombre. Vive en la
-- base hasta que llegue el almacenamiento S3 (H4); pesa poco (los tres Excel suman 4.6 MB).
CREATE TABLE migracion.archivo (
  empresa_id bigint      NOT NULL REFERENCES organizacion.empresa,
  sha256     text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  nombre     text        NOT NULL CHECK (length(btrim(nombre)) BETWEEN 1 AND 200),
  tamano     integer     NOT NULL CHECK (tamano BETWEEN 1 AND 20971520),
  contenido  bytea       NOT NULL,
  usuario_id bigint      NOT NULL,
  subido     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (empresa_id, sha256),
  FOREIGN KEY (empresa_id, usuario_id) REFERENCES organizacion.usuario (empresa_id, id)
);

-- Una carga: un tipo (materiales, contratos…) leído de un archivo con una versión de reglas.
CREATE TABLE migracion.carga (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id     bigint      NOT NULL,
  tipo           text        NOT NULL CHECK (tipo ~ '^[A-Z_]{3,30}$'),
  archivo        text        NOT NULL,
  sha256         text        NOT NULL,
  hojas          text        NOT NULL,
  parametros     jsonb       NOT NULL DEFAULT '{}'::jsonb,
  estado         text        NOT NULL DEFAULT 'validada'
                 CHECK (estado IN ('validada', 'confirmada', 'revertida')),
  version_reglas text        NOT NULL CHECK (length(version_reglas) BETWEEN 1 AND 40),
  usuario_id     bigint      NOT NULL,
  creada         timestamptz NOT NULL DEFAULT now(),
  validada       timestamptz NOT NULL DEFAULT now(),
  confirmada     timestamptz,
  confirmada_por bigint,
  revertida      timestamptz,
  revertida_por  bigint,
  resumen        jsonb,
  version        integer     NOT NULL DEFAULT 0,
  CHECK (estado <> 'confirmada' OR (confirmada IS NOT NULL AND confirmada_por IS NOT NULL)),
  CHECK (estado <> 'revertida' OR (revertida IS NOT NULL AND revertida_por IS NOT NULL)),
  UNIQUE (empresa_id, id),
  FOREIGN KEY (empresa_id, sha256) REFERENCES migracion.archivo (empresa_id, sha256),
  FOREIGN KEY (empresa_id, usuario_id) REFERENCES organizacion.usuario (empresa_id, id),
  FOREIGN KEY (empresa_id, confirmada_por) REFERENCES organizacion.usuario (empresa_id, id),
  FOREIGN KEY (empresa_id, revertida_por) REFERENCES organizacion.usuario (empresa_id, id)
);
-- Idempotencia: el mismo archivo, del mismo tipo y con las mismas reglas, no se carga dos veces.
-- Después de revertirla se puede volver a subir.
CREATE UNIQUE INDEX ux_carga_vigente ON migracion.carga (empresa_id, sha256, tipo, version_reglas)
  WHERE estado <> 'revertida';
CREATE INDEX ix_carga_tipo ON migracion.carga (empresa_id, tipo, estado);

-- El renglón tal como vino (crudo, sin tocar) y como quedó después de normalizar. `numero` es el
-- consecutivo dentro de la carga; `fila_excel` es la fila de la hoja (NULL cuando el renglón junta
-- un nombre que aparece en muchas celdas, como un trabajador del destajo).
CREATE TABLE migracion.fila (
  empresa_id bigint  NOT NULL,
  carga_id   bigint  NOT NULL,
  numero     integer NOT NULL CHECK (numero > 0),
  hoja       text    NOT NULL,
  fila_excel integer CHECK (fila_excel > 0),
  crudo      jsonb   NOT NULL,
  normal     jsonb,
  estado     text    NOT NULL CHECK (estado IN ('valida', 'corregida', 'cuarentena', 'descartada')),
  accion     text    NOT NULL CHECK (accion IN ('crear', 'existe', 'omitir')),
  PRIMARY KEY (carga_id, numero),
  UNIQUE (empresa_id, carga_id, numero),
  FOREIGN KEY (empresa_id, carga_id) REFERENCES migracion.carga (empresa_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_fila_estado ON migracion.fila (carga_id, estado);

CREATE TABLE migracion.incidencia (
  empresa_id   bigint  NOT NULL,
  carga_id     bigint  NOT NULL,
  numero       integer NOT NULL,
  regla        text    NOT NULL CHECK (regla ~ '^[A-Z0-9_]{3,40}$'),
  severidad    text    NOT NULL CHECK (severidad IN ('info', 'advertencia', 'cuarentena', 'bloqueo')),
  detalle      text    NOT NULL CHECK (length(detalle) <= 500),
  sugerencia   jsonb,
  resuelta_por bigint,
  resuelta_en  timestamptz,
  resolucion   jsonb,
  PRIMARY KEY (carga_id, numero, regla),
  FOREIGN KEY (empresa_id, carga_id, numero)
    REFERENCES migracion.fila (empresa_id, carga_id, numero) ON DELETE CASCADE
);
CREATE INDEX ix_incidencia_regla ON migracion.incidencia (carga_id, regla);

-- Lo que una persona decidió sobre un renglón (descartar, capturar un precio, elegir el contrato
-- de un lote). Sobrevive a la revalidación: la carga se vuelve a validar con estas decisiones.
CREATE TABLE migracion.decision (
  empresa_id bigint      NOT NULL,
  carga_id   bigint      NOT NULL,
  numero     integer     NOT NULL,
  regla      text        NOT NULL CHECK (regla ~ '^[A-Z0-9_]{3,40}$'),
  resolucion jsonb       NOT NULL,
  usuario_id bigint      NOT NULL,
  fecha      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (carga_id, numero, regla),
  FOREIGN KEY (empresa_id, carga_id) REFERENCES migracion.carga (empresa_id, id) ON DELETE CASCADE,
  FOREIGN KEY (empresa_id, usuario_id) REFERENCES organizacion.usuario (empresa_id, id)
);

-- Cómo se escribía algo en el Excel → qué registro es en el sistema. El texto va normalizado.
-- El destino no lleva FK (puede ser de varias tablas); el servicio lo valida con la API del módulo
-- dueño y la validación ignora alias cuyo destino ya no existe.
CREATE TABLE migracion.alias (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id bigint      NOT NULL,
  entidad    text        NOT NULL CHECK (entidad IN ('frente', 'contrato', 'prototipo',
                                                     'unidad_medida', 'material', 'proveedor',
                                                     'persona')),
  texto      text        NOT NULL CHECK (texto = compartido.normalizar(texto)),
  destino_id bigint      NOT NULL,
  origen     text        NOT NULL CHECK (origen IN ('regla', 'sugerencia_aceptada', 'manual')),
  carga_id   bigint,
  usuario_id bigint,
  creado     timestamptz NOT NULL DEFAULT now(),
  version    integer     NOT NULL DEFAULT 0,
  UNIQUE (empresa_id, id),
  UNIQUE (empresa_id, entidad, texto),
  FOREIGN KEY (empresa_id, carga_id) REFERENCES migracion.carga (empresa_id, id),
  FOREIGN KEY (empresa_id, usuario_id) REFERENCES organizacion.usuario (empresa_id, id)
);
CREATE INDEX ix_alias_destino ON migracion.alias (empresa_id, entidad, destino_id);

-- Qué creó cada carga al confirmarse. Se conserva después de revertir, como historia.
CREATE TABLE migracion.registro (
  empresa_id  bigint NOT NULL,
  carga_id    bigint NOT NULL,
  entidad     text   NOT NULL CHECK (entidad ~ '^[a-z_]{3,30}$'),
  registro_id bigint NOT NULL,
  PRIMARY KEY (carga_id, entidad, registro_id),
  FOREIGN KEY (empresa_id, carga_id) REFERENCES migracion.carga (empresa_id, id)
);

DO $$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['archivo', 'carga', 'fila', 'incidencia', 'decision', 'alias', 'registro']
  LOOP
    EXECUTE format('ALTER TABLE migracion.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE migracion.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY por_empresa ON migracion.%I'
                   ' USING (empresa_id = compartido.empresa_actual())'
                   ' WITH CHECK (empresa_id = compartido.empresa_actual())', t);
  END LOOP;
END
$$;

-- El archivo y el registro de lo creado no se modifican. Filas e incidencias se reescriben en
-- cada validación; decisiones y alias se pueden corregir.
GRANT SELECT, INSERT ON migracion.archivo, migracion.registro TO app;
GRANT SELECT, INSERT, UPDATE ON migracion.carga TO app;
GRANT SELECT, INSERT, DELETE ON migracion.fila, migracion.incidencia TO app;
GRANT SELECT, INSERT, UPDATE, DELETE ON migracion.decision, migracion.alias TO app;

-- Bitácora: quién subió, confirmó o revirtió cada carga, y cada decisión y alias. El archivo no
-- (su contenido es binario y la carga ya registra quién lo subió).
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON migracion.carga
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id', 'resumen');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON migracion.decision
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('carga_id');
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON migracion.alias
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('id');

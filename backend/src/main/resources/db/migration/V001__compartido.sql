-- V001 · Fundamentos compartidos: empresa del contexto, bitácora y folios.
--
-- Corre con el rol `migrador` (dueño de las tablas, con BYPASSRLS). La aplicación se conecta
-- con el rol `app`, que solo recibe los permisos que se otorgan aquí de forma explícita.
-- Los roles se crean al aprovisionar la base: infra/db/init/01-roles.sh.

CREATE SCHEMA compartido;
GRANT USAGE ON SCHEMA compartido TO app;

-- Empresa y usuario de la conexión. La app los fija en cada conexión que toma del pool con
-- set_config('app.empresa_id', …) y set_config('app.usuario_id', …). Sin valor, devuelven NULL
-- y las políticas no dejan ver ninguna fila: si algo falla, falla cerrado.
CREATE FUNCTION compartido.empresa_actual() RETURNS bigint
  LANGUAGE sql STABLE PARALLEL SAFE
  AS $$ SELECT nullif(current_setting('app.empresa_id', true), '')::bigint $$;

CREATE FUNCTION compartido.usuario_actual() RETURNS bigint
  LANGUAGE sql STABLE PARALLEL SAFE
  AS $$ SELECT nullif(current_setting('app.usuario_id', true), '')::bigint $$;

-- ---------------------------------------------------------------------------------------------
-- Bitácora: cada INSERT, UPDATE y DELETE de las tablas que la activan, con el antes y el después.
-- La app puede insertar (lo hace el trigger) y leer; no puede modificar ni borrar.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE compartido.bitacora (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id  bigint      NOT NULL,
  esquema     text        NOT NULL,
  tabla       text        NOT NULL,
  registro_id bigint,
  operacion   text        NOT NULL CHECK (operacion IN ('INSERT', 'UPDATE', 'DELETE')),
  antes       jsonb,
  despues     jsonb,
  usuario_id  bigint,
  fecha       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_bitacora_registro ON compartido.bitacora (empresa_id, esquema, tabla, registro_id);
CREATE INDEX ix_bitacora_fecha ON compartido.bitacora (empresa_id, fecha);

ALTER TABLE compartido.bitacora ENABLE ROW LEVEL SECURITY;
ALTER TABLE compartido.bitacora FORCE ROW LEVEL SECURITY;
CREATE POLICY por_empresa ON compartido.bitacora
  USING (empresa_id = compartido.empresa_actual())
  WITH CHECK (empresa_id = compartido.empresa_actual());

GRANT SELECT, INSERT ON compartido.bitacora TO app;

-- Uso: CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON <tabla>
--        FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('<columna id>', '<ignorar 1>', …);
-- Argumento 0: columna que identifica el registro (por defecto 'id').
-- Argumentos 1…n: columnas que no se guardan (contraseñas, contadores). Un UPDATE que solo
-- cambia columnas ignoradas no deja renglón.
CREATE FUNCTION compartido.fn_bitacora() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  v_antes   jsonb := CASE WHEN TG_OP <> 'INSERT' THEN to_jsonb(OLD) END;
  v_despues jsonb := CASE WHEN TG_OP <> 'DELETE' THEN to_jsonb(NEW) END;
  v_col_id  text  := coalesce(TG_ARGV[0], 'id');
  v_ignorar text[] := coalesce(TG_ARGV[1:], '{}');
  v_fila    jsonb;
BEGIN
  v_fila := coalesce(v_despues, v_antes);
  v_antes := v_antes - v_ignorar;
  v_despues := v_despues - v_ignorar;
  IF TG_OP = 'UPDATE' AND v_antes = v_despues THEN
    RETURN NULL;
  END IF;
  INSERT INTO compartido.bitacora (empresa_id, esquema, tabla, registro_id, operacion, antes, despues, usuario_id)
  VALUES ((v_fila ->> 'empresa_id')::bigint, TG_TABLE_SCHEMA, TG_TABLE_NAME, (v_fila ->> v_col_id)::bigint,
          TG_OP, v_antes, v_despues, compartido.usuario_actual());
  RETURN NULL;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- Folios: consecutivo por empresa, tipo y año, sin huecos. Se pide dentro de la misma transacción
-- que crea el documento; si la transacción se revierte, el número se libera.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE compartido.folio (
  empresa_id  bigint   NOT NULL,
  tipo        text     NOT NULL CHECK (tipo ~ '^[A-Z]{2,5}$'),
  anio        smallint NOT NULL CHECK (anio BETWEEN 2020 AND 2100),
  consecutivo bigint   NOT NULL CHECK (consecutivo > 0),
  PRIMARY KEY (empresa_id, tipo, anio)
);

ALTER TABLE compartido.folio ENABLE ROW LEVEL SECURITY;
ALTER TABLE compartido.folio FORCE ROW LEVEL SECURITY;
CREATE POLICY por_empresa ON compartido.folio
  USING (empresa_id = compartido.empresa_actual())
  WITH CHECK (empresa_id = compartido.empresa_actual());

GRANT SELECT, INSERT, UPDATE ON compartido.folio TO app;

-- La fila queda bloqueada hasta el fin de la transacción: dos vales simultáneos esperan su turno.
CREATE FUNCTION compartido.siguiente_folio(p_tipo text, p_anio smallint) RETURNS bigint
  LANGUAGE sql
  AS $$
  INSERT INTO compartido.folio AS f (empresa_id, tipo, anio, consecutivo)
  VALUES (compartido.empresa_actual(), p_tipo, p_anio, 1)
  ON CONFLICT (empresa_id, tipo, anio) DO UPDATE SET consecutivo = f.consecutivo + 1
  RETURNING f.consecutivo
$$;

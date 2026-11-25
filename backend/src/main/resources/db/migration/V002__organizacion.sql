-- V002 · Organización: empresas, usuarios, roles y sesiones.

CREATE SCHEMA organizacion;
GRANT USAGE ON SCHEMA organizacion TO app;

CREATE TABLE organizacion.empresa (
  id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nombre  text        NOT NULL CHECK (length(trim(nombre)) > 0),
  rfc     text        UNIQUE CHECK (rfc ~ '^[A-ZÑ&]{3,4}[0-9]{6}[A-Z0-9]{3}$'),
  activa  boolean     NOT NULL DEFAULT true,
  creada  timestamptz NOT NULL DEFAULT now()
);

-- Cada empresa solo se ve a sí misma. Dar de alta empresas es tarea de plataforma (migrador).
ALTER TABLE organizacion.empresa ENABLE ROW LEVEL SECURITY;
ALTER TABLE organizacion.empresa FORCE ROW LEVEL SECURITY;
CREATE POLICY por_empresa ON organizacion.empresa
  USING (id = compartido.empresa_actual());
GRANT SELECT ON organizacion.empresa TO app;

-- Catálogo fijo de roles. Debe coincidir con mx.constructora.organizacion.Rol (lo verifica una prueba).
CREATE TABLE organizacion.rol (
  clave  text PRIMARY KEY CHECK (clave ~ '^[a-z]+$'),
  nombre text NOT NULL
);
INSERT INTO organizacion.rol (clave, nombre) VALUES
  ('almacenista', 'Almacenista'),
  ('residente', 'Residente'),
  ('coordinacion', 'Coordinación'),
  ('compras', 'Compras'),
  ('direccion', 'Dirección'),
  ('admin', 'Administración');
GRANT SELECT ON organizacion.rol TO app;

CREATE TABLE organizacion.usuario (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  empresa_id          bigint      NOT NULL REFERENCES organizacion.empresa,
  usuario             text        NOT NULL UNIQUE CHECK (usuario ~ '^[a-z0-9._-]{3,60}$'),
  nombre              text        NOT NULL CHECK (length(trim(nombre)) > 0),
  correo              text        CHECK (correo ~ '^[^@\s]+@[^@\s]+$'),
  hash_contrasena     text        NOT NULL,
  contrasena_cambiada timestamptz NOT NULL DEFAULT now(),
  activo              boolean     NOT NULL DEFAULT true,
  intentos_fallidos   smallint    NOT NULL DEFAULT 0 CHECK (intentos_fallidos >= 0),
  bloqueado_hasta     timestamptz,
  ultimo_acceso       timestamptz,
  creado              timestamptz NOT NULL DEFAULT now(),
  UNIQUE (empresa_id, id)
);

ALTER TABLE organizacion.usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE organizacion.usuario FORCE ROW LEVEL SECURITY;
CREATE POLICY por_empresa ON organizacion.usuario
  USING (empresa_id = compartido.empresa_actual())
  WITH CHECK (empresa_id = compartido.empresa_actual());
-- Sin DELETE: un usuario se desactiva, nunca se borra (la bitácora lo sigue nombrando).
GRANT SELECT, INSERT, UPDATE ON organizacion.usuario TO app;

CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON organizacion.usuario
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora(
    'id', 'hash_contrasena', 'intentos_fallidos', 'bloqueado_hasta', 'ultimo_acceso');

CREATE TABLE organizacion.usuario_rol (
  empresa_id bigint NOT NULL,
  usuario_id bigint NOT NULL,
  rol        text   NOT NULL REFERENCES organizacion.rol,
  PRIMARY KEY (usuario_id, rol),
  -- La FK compuesta impide ligar un rol a un usuario de otra empresa.
  FOREIGN KEY (empresa_id, usuario_id) REFERENCES organizacion.usuario (empresa_id, id)
);

ALTER TABLE organizacion.usuario_rol ENABLE ROW LEVEL SECURITY;
ALTER TABLE organizacion.usuario_rol FORCE ROW LEVEL SECURITY;
CREATE POLICY por_empresa ON organizacion.usuario_rol
  USING (empresa_id = compartido.empresa_actual())
  WITH CHECK (empresa_id = compartido.empresa_actual());
GRANT SELECT, INSERT, DELETE ON organizacion.usuario_rol TO app;

CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON organizacion.usuario_rol
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('usuario_id');

-- ---------------------------------------------------------------------------------------------
-- Inicio de sesión. Se busca al usuario antes de saber su empresa, así que la lectura pasa por
-- funciones SECURITY DEFINER (corren como migrador) que exponen solo lo necesario.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION organizacion.usuario_para_login(p_usuario text)
  RETURNS TABLE (id bigint, empresa_id bigint, empresa text, usuario text, nombre text,
                 hash_contrasena text, activo boolean, bloqueado_hasta timestamptz, roles text[])
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = pg_catalog, pg_temp
  AS $$
  SELECT u.id, u.empresa_id, e.nombre, u.usuario, u.nombre, u.hash_contrasena,
         u.activo AND e.activa, u.bloqueado_hasta,
         coalesce(array_agg(r.rol ORDER BY r.rol) FILTER (WHERE r.rol IS NOT NULL), '{}')
    FROM organizacion.usuario u
    JOIN organizacion.empresa e ON e.id = u.empresa_id
    LEFT JOIN organizacion.usuario_rol r ON r.usuario_id = u.id
   WHERE u.usuario = lower(trim(p_usuario))
   GROUP BY u.id, e.id
$$;

-- 5 intentos fallidos seguidos bloquean la cuenta 15 minutos.
CREATE FUNCTION organizacion.registrar_intento_login(p_usuario text, p_exito boolean) RETURNS void
  LANGUAGE plpgsql SECURITY DEFINER
  SET search_path = pg_catalog, pg_temp
  AS $$
BEGIN
  IF p_exito THEN
    UPDATE organizacion.usuario
       SET intentos_fallidos = 0, bloqueado_hasta = NULL, ultimo_acceso = now()
     WHERE usuario = lower(trim(p_usuario));
  ELSE
    UPDATE organizacion.usuario
       SET intentos_fallidos = CASE WHEN intentos_fallidos + 1 >= 5 THEN 0 ELSE intentos_fallidos + 1 END,
           bloqueado_hasta   = CASE WHEN intentos_fallidos + 1 >= 5 THEN now() + interval '15 minutes'
                                    ELSE bloqueado_hasta END
     WHERE usuario = lower(trim(p_usuario));
  END IF;
END
$$;

-- Arranque de una instalación nueva: crea la primera empresa y su administrador.
-- Solo funciona mientras no exista ningún usuario en toda la base.
CREATE FUNCTION organizacion.inicializar(p_empresa text, p_rfc text, p_usuario text, p_nombre text,
                                         p_hash text) RETURNS boolean
  LANGUAGE plpgsql SECURITY DEFINER
  SET search_path = pg_catalog, pg_temp
  AS $$
DECLARE
  v_empresa bigint;
  v_usuario bigint;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtext('organizacion.inicializar'));
  IF EXISTS (SELECT 1 FROM organizacion.usuario) THEN
    RETURN false;
  END IF;
  INSERT INTO organizacion.empresa (nombre, rfc) VALUES (p_empresa, nullif(p_rfc, ''))
    RETURNING id INTO v_empresa;
  INSERT INTO organizacion.usuario (empresa_id, usuario, nombre, hash_contrasena)
    VALUES (v_empresa, lower(trim(p_usuario)), p_nombre, p_hash)
    RETURNING id INTO v_usuario;
  INSERT INTO organizacion.usuario_rol (empresa_id, usuario_id, rol) VALUES (v_empresa, v_usuario, 'admin');
  RETURN true;
END
$$;

REVOKE ALL ON FUNCTION organizacion.usuario_para_login(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION organizacion.registrar_intento_login(text, boolean) FROM PUBLIC;
REVOKE ALL ON FUNCTION organizacion.inicializar(text, text, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION organizacion.usuario_para_login(text) TO app;
GRANT EXECUTE ON FUNCTION organizacion.registrar_intento_login(text, boolean) TO app;
GRANT EXECUTE ON FUNCTION organizacion.inicializar(text, text, text, text, text) TO app;

-- ---------------------------------------------------------------------------------------------
-- Sesiones de Spring Session JDBC (spring.session.jdbc.table-name=organizacion.sesion).
-- Mismo esquema que schema-postgresql.sql de Spring Session. Sin RLS: la sesión se lee antes
-- de saber quién es el usuario.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE organizacion.sesion (
  primary_id            char(36)     NOT NULL,
  session_id            char(36)     NOT NULL,
  creation_time         bigint       NOT NULL,
  last_access_time      bigint       NOT NULL,
  max_inactive_interval int          NOT NULL,
  expiry_time           bigint       NOT NULL,
  principal_name        varchar(100),
  CONSTRAINT sesion_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX sesion_ix1 ON organizacion.sesion (session_id);
CREATE INDEX sesion_ix2 ON organizacion.sesion (expiry_time);
CREATE INDEX sesion_ix3 ON organizacion.sesion (principal_name);

CREATE TABLE organizacion.sesion_attributes (
  session_primary_id char(36)     NOT NULL,
  attribute_name     varchar(200) NOT NULL,
  attribute_bytes    bytea        NOT NULL,
  CONSTRAINT sesion_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
  CONSTRAINT sesion_attributes_fk FOREIGN KEY (session_primary_id)
    REFERENCES organizacion.sesion (primary_id) ON DELETE CASCADE
);

GRANT SELECT, INSERT, UPDATE, DELETE ON organizacion.sesion, organizacion.sesion_attributes TO app;

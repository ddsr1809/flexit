-- V010 · Segundo factor (TOTP) para administración y dirección (H7, antes de producción).
--
-- Un renglón por usuario con el secreto cifrado por la app (AES-256-GCM con la llave
-- APP_LLAVE_SEGUNDO_FACTOR, que no está en la base: un respaldo robado no da los secretos).
-- confirmado NULL = el alta empezó pero aún no se capturó el primer código.
-- ultimo_paso = la ventana de 30 s del último código aceptado: un código no sirve dos veces.

CREATE TABLE organizacion.segundo_factor (
  empresa_id  bigint      NOT NULL,
  usuario_id  bigint      NOT NULL PRIMARY KEY,
  secreto     bytea       NOT NULL CHECK (octet_length(secreto) BETWEEN 28 AND 128),
  confirmado  timestamptz,
  ultimo_paso bigint      NOT NULL DEFAULT 0 CHECK (ultimo_paso >= 0),
  creado      timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (empresa_id, usuario_id) REFERENCES organizacion.usuario (empresa_id, id)
);

ALTER TABLE organizacion.segundo_factor ENABLE ROW LEVEL SECURITY;
ALTER TABLE organizacion.segundo_factor FORCE ROW LEVEL SECURITY;
CREATE POLICY por_empresa ON organizacion.segundo_factor
  USING (empresa_id = compartido.empresa_actual())
  WITH CHECK (empresa_id = compartido.empresa_actual());
-- DELETE: restablecerlo (administración) borra el renglón y el usuario vuelve a darlo de alta.
GRANT SELECT, INSERT, UPDATE, DELETE ON organizacion.segundo_factor TO app;

-- La bitácora registra alta, confirmación y restablecimiento; nunca el secreto ni cada uso.
CREATE TRIGGER trg_bitacora AFTER INSERT OR UPDATE OR DELETE ON organizacion.segundo_factor
  FOR EACH ROW EXECUTE FUNCTION compartido.fn_bitacora('usuario_id', 'secreto', 'ultimo_paso');

-- Las sesiones abiertas antes de esta versión se cierran: el siguiente inicio de sesión de
-- administración y dirección ya pide el segundo factor.
DELETE FROM organizacion.sesion;

package mx.constructora.organizacion.infraestructura;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import mx.constructora.organizacion.Rol;
import mx.constructora.organizacion.aplicacion.UsuarioSesion;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Acceso a {@code organizacion.usuario} y {@code usuario_rol}. Todas las consultas filtran por la
 * empresa del contexto dos veces: con la condición explícita y con la política de RLS.
 */
@Repository
public class RepositorioUsuarios {

  /** Renglón de la lista de usuarios. */
  public record FilaUsuario(
      long id, String usuario, String nombre, String correo, boolean activo, List<Rol> roles) {}

  /** Usuario sin roles ni datos de acceso, para mostrar su nombre. */
  public record FilaResumen(long id, String usuario, String nombre) {}

  private final JdbcClient jdbc;

  RepositorioUsuarios(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Busca para iniciar sesión, antes de saber la empresa (función SECURITY DEFINER). */
  public Optional<UsuarioSesion> paraLogin(String usuario) {
    return jdbc.sql("SELECT * FROM organizacion.usuario_para_login(:usuario)")
        .param("usuario", usuario)
        .query(RepositorioUsuarios::aSesion)
        .optional();
  }

  public void registrarIntento(String usuario, boolean exito) {
    jdbc.sql("SELECT organizacion.registrar_intento_login(:usuario, :exito)")
        .param("usuario", usuario)
        .param("exito", exito)
        .query((rs, n) -> 1)
        .list();
  }

  public List<FilaUsuario> listar(long empresaId) {
    return jdbc.sql(
            """
            SELECT u.id, u.usuario, u.nombre, u.correo, u.activo,
                   coalesce(array_agg(r.rol ORDER BY r.rol) FILTER (WHERE r.rol IS NOT NULL), '{}') AS roles
              FROM organizacion.usuario u
              LEFT JOIN organizacion.usuario_rol r ON r.usuario_id = u.id
             WHERE u.empresa_id = :empresa
             GROUP BY u.id
             ORDER BY u.nombre
            """)
        .param("empresa", empresaId)
        .query(
            (rs, n) ->
                new FilaUsuario(
                    rs.getLong("id"),
                    rs.getString("usuario"),
                    rs.getString("nombre"),
                    rs.getString("correo"),
                    rs.getBoolean("activo"),
                    roles(rs.getArray("roles"))))
        .list();
  }

  public long insertar(
      long empresaId, String usuario, String nombre, String correo, String hashContrasena) {
    return jdbc.sql(
            """
            INSERT INTO organizacion.usuario (empresa_id, usuario, nombre, correo, hash_contrasena)
            VALUES (:empresa, :usuario, :nombre, :correo, :hash)
            RETURNING id
            """)
        .param("empresa", empresaId)
        .param("usuario", usuario)
        .param("nombre", nombre)
        .param("correo", correo)
        .param("hash", hashContrasena)
        .query(Long.class)
        .single();
  }

  public void asignarRol(long empresaId, long usuarioId, Rol rol) {
    jdbc.sql(
            "INSERT INTO organizacion.usuario_rol (empresa_id, usuario_id, rol)"
                + " VALUES (:empresa, :usuario, :rol)")
        .param("empresa", empresaId)
        .param("usuario", usuarioId)
        .param("rol", rol.clave())
        .update();
  }

  /** Nombre y usuario de los ids pedidos, de la empresa en curso. */
  public List<FilaResumen> resumen(long empresaId, Collection<Long> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT u.id, u.usuario, u.nombre
              FROM organizacion.usuario u
             WHERE u.empresa_id = :empresa AND u.id IN (:ids)
             ORDER BY u.nombre
            """)
        .param("empresa", empresaId)
        .param("ids", ids)
        .query(RepositorioUsuarios::aResumen)
        .list();
  }

  /** Usuarios activos que tienen el rol. */
  public List<FilaResumen> activosConRol(long empresaId, Rol rol) {
    return jdbc.sql(
            """
            SELECT u.id, u.usuario, u.nombre
              FROM organizacion.usuario u
              JOIN organizacion.usuario_rol r ON r.usuario_id = u.id AND r.rol = :rol
             WHERE u.empresa_id = :empresa AND u.activo
             ORDER BY u.nombre
            """)
        .param("empresa", empresaId)
        .param("rol", rol.clave())
        .query(RepositorioUsuarios::aResumen)
        .list();
  }

  /** Primera instalación: crea empresa y administrador solo si la base no tiene usuarios. */
  public boolean inicializar(
      String empresa, String rfc, String usuario, String nombre, String hashContrasena) {
    return jdbc.sql("SELECT organizacion.inicializar(:empresa, :rfc, :usuario, :nombre, :hash)")
        .param("empresa", empresa)
        .param("rfc", rfc)
        .param("usuario", usuario)
        .param("nombre", nombre)
        .param("hash", hashContrasena)
        .query(Boolean.class)
        .single();
  }

  private static UsuarioSesion aSesion(ResultSet rs, int fila) throws SQLException {
    Timestamp bloqueado = rs.getTimestamp("bloqueado_hasta");
    return new UsuarioSesion(
        rs.getLong("id"),
        rs.getLong("empresa_id"),
        rs.getString("empresa"),
        rs.getString("usuario"),
        rs.getString("nombre"),
        rs.getString("hash_contrasena"),
        rs.getBoolean("activo"),
        bloqueado == null ? null : bloqueado.toInstant(),
        roles(rs.getArray("roles")));
  }

  private static FilaResumen aResumen(ResultSet rs, int fila) throws SQLException {
    return new FilaResumen(rs.getLong("id"), rs.getString("usuario"), rs.getString("nombre"));
  }

  private static List<Rol> roles(Array arreglo) throws SQLException {
    if (arreglo == null) {
      return List.of();
    }
    List<Rol> roles = new ArrayList<>();
    Arrays.stream((String[]) arreglo.getArray()).forEach(c -> Rol.deClave(c).ifPresent(roles::add));
    return roles;
  }
}

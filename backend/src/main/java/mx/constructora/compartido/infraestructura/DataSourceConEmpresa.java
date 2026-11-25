package mx.constructora.compartido.infraestructura;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import mx.constructora.compartido.ContextoEmpresa;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Envuelve el pool de conexiones: cada conexión que se entrega lleva fijados {@code app.empresa_id}
 * y {@code app.usuario_id} con los valores de {@link ContextoEmpresa}. Las políticas de RLS y la
 * bitácora los leen.
 *
 * <p>Los valores se escriben siempre, también vacíos, para que una conexión reciclada del pool
 * nunca conserve la empresa de la petición anterior. Sin contexto quedan vacíos y la base no
 * muestra ninguna fila con {@code empresa_id}. Si no se pueden fijar, la conexión se cierra y la
 * operación falla: nunca se entrega una conexión sin contexto conocido.
 */
final class DataSourceConEmpresa extends DelegatingDataSource {

  private static final String FIJAR =
      "SELECT set_config('app.empresa_id', ?, false), set_config('app.usuario_id', ?, false)";

  DataSourceConEmpresa(DataSource destino) {
    super(destino);
  }

  @Override
  public Connection getConnection() throws SQLException {
    return preparar(super.getConnection());
  }

  @Override
  public Connection getConnection(String usuario, String contrasena) throws SQLException {
    return preparar(super.getConnection(usuario, contrasena));
  }

  private static Connection preparar(Connection conexion) throws SQLException {
    var identidad = ContextoEmpresa.actual();
    String empresa = identidad.map(i -> Long.toString(i.empresaId())).orElse("");
    String usuario = identidad.map(i -> Long.toString(i.usuarioId())).orElse("");
    try (PreparedStatement sentencia = conexion.prepareStatement(FIJAR)) {
      sentencia.setString(1, empresa);
      sentencia.setString(2, usuario);
      sentencia.execute();
      return conexion;
    } catch (SQLException | RuntimeException e) {
      try {
        conexion.close();
      } catch (SQLException cierre) {
        e.addSuppressed(cierre);
      }
      throw e;
    }
  }
}

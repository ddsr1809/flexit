package mx.constructora.estructura.infraestructura;

import java.util.List;
import java.util.Optional;
import mx.constructora.estructura.aplicacion.ServicioClientes.Cliente;
import mx.constructora.estructura.aplicacion.ServicioClientes.Frente;
import mx.constructora.estructura.aplicacion.ServicioClientes.Prototipo;
import mx.constructora.estructura.aplicacion.ServicioClientes.TipoUnidad;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Clientes, frentes, prototipos y tipos de unidad. Cada consulta filtra por empresa en el código y
 * otra vez por RLS en la base. Las columnas se nombran como los campos del record (en snake_case)
 * para que JdbcClient los llene solo.
 */
@Repository
public class RepositorioClientes {

  private static final String CLIENTE =
      "SELECT id, nombre, rfc, activo, version FROM estructura.cliente WHERE empresa_id = :empresa";

  private static final String FRENTE =
      """
      SELECT f.id, f.cliente_id, c.nombre AS cliente, f.clave, f.nombre, f.activo, f.version
        FROM estructura.frente f
        JOIN estructura.cliente c ON c.id = f.cliente_id
       WHERE f.empresa_id = :empresa
      """;

  private static final String PROTOTIPO =
      """
      SELECT id, clave, nombre, descripcion, activo, version
        FROM estructura.prototipo WHERE empresa_id = :empresa
      """;

  private final JdbcClient jdbc;

  RepositorioClientes(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------- Clientes

  public List<Cliente> clientes(long empresa) {
    return jdbc.sql(CLIENTE + " ORDER BY activo DESC, nombre")
        .param("empresa", empresa)
        .query(Cliente.class)
        .list();
  }

  public Optional<Cliente> cliente(long empresa, long id) {
    return jdbc.sql(CLIENTE + " AND id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .query(Cliente.class)
        .optional();
  }

  public long insertarCliente(long empresa, String nombre, String rfc) {
    return jdbc.sql(
            """
            INSERT INTO estructura.cliente (empresa_id, nombre, rfc)
            VALUES (:empresa, :nombre, :rfc) RETURNING id
            """)
        .param("empresa", empresa)
        .param("nombre", nombre)
        .param("rfc", rfc)
        .query(Long.class)
        .single();
  }

  public int actualizarCliente(
      long empresa, long id, int version, String nombre, String rfc, boolean activo) {
    return jdbc.sql(
            """
            UPDATE estructura.cliente
               SET nombre = :nombre, rfc = :rfc, activo = :activo, version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("nombre", nombre)
        .param("rfc", rfc)
        .param("activo", activo)
        .update();
  }

  // ------------------------------------------------------------------------------- Frentes

  public List<Frente> frentes(long empresa) {
    return jdbc.sql(FRENTE + " ORDER BY f.activo DESC, f.nombre")
        .param("empresa", empresa)
        .query(Frente.class)
        .list();
  }

  public Optional<Frente> frente(long empresa, long id) {
    return jdbc.sql(FRENTE + " AND f.id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .query(Frente.class)
        .optional();
  }

  public long insertarFrente(long empresa, long cliente, String clave, String nombre) {
    return jdbc.sql(
            """
            INSERT INTO estructura.frente (empresa_id, cliente_id, clave, nombre)
            VALUES (:empresa, :cliente, :clave, :nombre) RETURNING id
            """)
        .param("empresa", empresa)
        .param("cliente", cliente)
        .param("clave", clave)
        .param("nombre", nombre)
        .query(Long.class)
        .single();
  }

  public int actualizarFrente(
      long empresa,
      long id,
      int version,
      long cliente,
      String clave,
      String nombre,
      boolean activo) {
    return jdbc.sql(
            """
            UPDATE estructura.frente
               SET cliente_id = :cliente, clave = :clave, nombre = :nombre, activo = :activo,
                   version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("cliente", cliente)
        .param("clave", clave)
        .param("nombre", nombre)
        .param("activo", activo)
        .update();
  }

  public long contratosDelFrente(long empresa, long frente) {
    return jdbc.sql(
            "SELECT count(*) FROM estructura.contrato"
                + " WHERE empresa_id = :empresa AND frente_id = :frente")
        .param("empresa", empresa)
        .param("frente", frente)
        .query(Long.class)
        .single();
  }

  // ------------------------------------------------------------------------------- Prototipos

  public List<Prototipo> prototipos(long empresa) {
    return jdbc.sql(PROTOTIPO + " ORDER BY activo DESC, clave")
        .param("empresa", empresa)
        .query(Prototipo.class)
        .list();
  }

  public Optional<Prototipo> prototipo(long empresa, long id) {
    return jdbc.sql(PROTOTIPO + " AND id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .query(Prototipo.class)
        .optional();
  }

  public long insertarPrototipo(long empresa, String clave, String nombre, String descripcion) {
    return jdbc.sql(
            """
            INSERT INTO estructura.prototipo (empresa_id, clave, nombre, descripcion)
            VALUES (:empresa, :clave, :nombre, :descripcion) RETURNING id
            """)
        .param("empresa", empresa)
        .param("clave", clave)
        .param("nombre", nombre)
        .param("descripcion", descripcion)
        .query(Long.class)
        .single();
  }

  public int actualizarPrototipo(
      long empresa,
      long id,
      int version,
      String clave,
      String nombre,
      String descripcion,
      boolean activo) {
    return jdbc.sql(
            """
            UPDATE estructura.prototipo
               SET clave = :clave, nombre = :nombre, descripcion = :descripcion, activo = :activo,
                   version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("clave", clave)
        .param("nombre", nombre)
        .param("descripcion", descripcion)
        .param("activo", activo)
        .update();
  }

  // ------------------------------------------------------------------------------- Tipos

  public List<TipoUnidad> tiposUnidad() {
    return jdbc.sql(
            "SELECT id, clave, nombre, usa_manzana_lote FROM estructura.tipo_unidad ORDER BY id")
        .query(TipoUnidad.class)
        .list();
  }
}

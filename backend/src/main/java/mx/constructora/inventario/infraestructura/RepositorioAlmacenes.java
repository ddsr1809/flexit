package mx.constructora.inventario.infraestructura;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Almacenes: uno por frente. */
@Repository
public class RepositorioAlmacenes {

  /** Almacén tal como está en la base. */
  public record FilaAlmacen(
      long id, long frenteId, String clave, String nombre, boolean activo, int version) {}

  private static final String ALMACEN =
      "SELECT id, frente_id, clave, nombre, activo, version FROM inventario.almacen"
          + " WHERE empresa_id = :empresa";

  private final JdbcClient jdbc;

  RepositorioAlmacenes(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<FilaAlmacen> almacenes(long empresa) {
    return jdbc.sql(ALMACEN + " ORDER BY activo DESC, clave")
        .param("empresa", empresa)
        .query(FilaAlmacen.class)
        .list();
  }

  public Optional<FilaAlmacen> almacen(long empresa, long id) {
    return jdbc.sql(ALMACEN + " AND id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .query(FilaAlmacen.class)
        .optional();
  }

  public long insertar(long empresa, long frente, String clave, String nombre) {
    return jdbc.sql(
            """
            INSERT INTO inventario.almacen (empresa_id, frente_id, clave, nombre)
            VALUES (:empresa, :frente, :clave, :nombre) RETURNING id
            """)
        .param("empresa", empresa)
        .param("frente", frente)
        .param("clave", clave)
        .param("nombre", nombre)
        .query(Long.class)
        .single();
  }

  public int actualizar(
      long empresa, long id, int version, String clave, String nombre, boolean activo) {
    return jdbc.sql(
            """
            UPDATE inventario.almacen
               SET clave = :clave, nombre = :nombre, activo = :activo, version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("clave", clave)
        .param("nombre", nombre)
        .param("activo", activo)
        .update();
  }

  /** {@code true} si el almacén tiene un conteo físico abierto. */
  public boolean enConteo(long empresa, long almacen) {
    return jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM inventario.conteo
                            WHERE empresa_id = :empresa AND almacen_id = :almacen
                              AND estado = 'abierto')
            """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .query(Boolean.class)
        .single();
  }
}

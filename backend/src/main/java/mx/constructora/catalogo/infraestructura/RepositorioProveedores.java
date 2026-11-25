package mx.constructora.catalogo.infraestructura;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import mx.constructora.catalogo.aplicacion.ServicioProveedores.Proveedor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Proveedores. */
@Repository
public class RepositorioProveedores {

  private static final String PROVEEDOR =
      """
      SELECT id, nombre, rfc, contacto, telefono, correo, tasa_iva, dias_credito, activo, version
        FROM catalogo.proveedor p
       WHERE empresa_id = :empresa
      """;

  private final JdbcClient jdbc;

  RepositorioProveedores(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<Proveedor> proveedores(long empresa, String texto) {
    return jdbc.sql(
            PROVEEDOR
                + """
                   AND (CAST(:texto AS text) IS NULL
                        OR p.rfc = upper(:texto)
                        OR NOT EXISTS (
                             SELECT 1
                               FROM unnest(string_to_array(compartido.normalizar(:texto), ' ')) AS x(palabra)
                              WHERE strpos(p.nombre_normal, x.palabra) = 0))
                 ORDER BY p.activo DESC, p.nombre_normal
                """)
        .param("empresa", empresa)
        .param("texto", texto)
        .query(Proveedor.class)
        .list();
  }

  public Optional<Proveedor> proveedor(long empresa, long id) {
    return jdbc.sql(PROVEEDOR + " AND id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .query(Proveedor.class)
        .optional();
  }

  public long insertar(
      long empresa,
      String nombre,
      String rfc,
      String contacto,
      String telefono,
      String correo,
      BigDecimal tasaIva,
      Integer diasCredito) {
    return jdbc.sql(
            """
            INSERT INTO catalogo.proveedor
              (empresa_id, nombre, rfc, contacto, telefono, correo, tasa_iva, dias_credito)
            VALUES (:empresa, :nombre, :rfc, :contacto, :telefono, :correo, :tasaIva,
                    CAST(:diasCredito AS smallint))
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("nombre", nombre)
        .param("rfc", rfc)
        .param("contacto", contacto)
        .param("telefono", telefono)
        .param("correo", correo)
        .param("tasaIva", tasaIva)
        .param("diasCredito", diasCredito)
        .query(Long.class)
        .single();
  }

  public int actualizar(
      long empresa,
      long id,
      int version,
      String nombre,
      String rfc,
      String contacto,
      String telefono,
      String correo,
      BigDecimal tasaIva,
      Integer diasCredito,
      boolean activo) {
    return jdbc.sql(
            """
            UPDATE catalogo.proveedor
               SET nombre = :nombre, rfc = :rfc, contacto = :contacto, telefono = :telefono,
                   correo = :correo, tasa_iva = :tasaIva,
                   dias_credito = CAST(:diasCredito AS smallint), activo = :activo,
                   version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("nombre", nombre)
        .param("rfc", rfc)
        .param("contacto", contacto)
        .param("telefono", telefono)
        .param("correo", correo)
        .param("tasaIva", tasaIva)
        .param("diasCredito", diasCredito)
        .param("activo", activo)
        .update();
  }
}

package mx.constructora.catalogo.infraestructura;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import mx.constructora.catalogo.aplicacion.ServicioMateriales.Material;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Materiales. La búsqueda por texto acepta la clave exacta o palabras sueltas: cada palabra,
 * normalizada como {@code nombre_normal}, debe aparecer en el nombre. El orden pone primero la
 * clave exacta y después el nombre más parecido (pg_trgm).
 */
@Repository
public class RepositorioMateriales {

  private static final String COLUMNAS =
      """
      SELECT m.id, m.clave, m.nombre, m.categoria_id, c.nombre AS categoria, m.unidad_control_id,
             u.clave AS unidad_control, m.precio_referencia, m.activo, m.version
        FROM catalogo.material m
        JOIN catalogo.unidad_medida u ON u.id = m.unidad_control_id
        LEFT JOIN catalogo.categoria c ON c.id = m.categoria_id
      """;

  private static final String FILTRO =
      """
       WHERE m.empresa_id = :empresa
         AND (NOT :soloActivos OR m.activo)
         AND (CAST(:categoria AS bigint) IS NULL OR m.categoria_id = :categoria)
         AND (CAST(:texto AS text) IS NULL
              OR m.clave = upper(:texto)
              OR NOT EXISTS (
                   SELECT 1
                     FROM unnest(string_to_array(compartido.normalizar(:texto), ' ')) AS p(palabra)
                    WHERE strpos(m.nombre_normal, p.palabra) = 0))
      """;

  private final JdbcClient jdbc;

  RepositorioMateriales(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<Material> buscar(
      long empresa, String texto, Long categoria, boolean soloActivos, int limite, long desde) {
    return jdbc.sql(
            COLUMNAS
                + FILTRO
                + """
                 ORDER BY (CAST(:texto AS text) IS NOT NULL AND m.clave = upper(:texto)) DESC,
                          CASE WHEN CAST(:texto AS text) IS NULL THEN 0
                               ELSE similarity(m.nombre_normal, compartido.normalizar(:texto)) END DESC,
                          m.nombre_normal
                 LIMIT :limite OFFSET :desde
                """)
        .param("empresa", empresa)
        .param("texto", texto)
        .param("categoria", categoria)
        .param("soloActivos", soloActivos)
        .param("limite", limite)
        .param("desde", desde)
        .query(Material.class)
        .list();
  }

  public long contar(long empresa, String texto, Long categoria, boolean soloActivos) {
    return jdbc.sql("SELECT count(*) FROM catalogo.material m" + FILTRO)
        .param("empresa", empresa)
        .param("texto", texto)
        .param("categoria", categoria)
        .param("soloActivos", soloActivos)
        .query(Long.class)
        .single();
  }

  public Optional<Material> material(long empresa, long id) {
    return jdbc.sql(COLUMNAS + " WHERE m.empresa_id = :empresa AND m.id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .query(Material.class)
        .optional();
  }

  /** Siguiente clave numérica con tres cifras: 001, 002… (300 → 301). */
  public String siguienteClave(long empresa) {
    return jdbc.sql(
            """
            SELECT lpad((coalesce(max(CASE WHEN clave ~ '^[0-9]{1,15}$' THEN clave::bigint END), 0)
                         + 1)::text, 3, '0')
              FROM catalogo.material
             WHERE empresa_id = :empresa
            """)
        .param("empresa", empresa)
        .query(String.class)
        .single();
  }

  public long insertar(
      long empresa,
      String clave,
      String nombre,
      Long categoria,
      long unidadControl,
      BigDecimal precio) {
    return jdbc.sql(
            """
            INSERT INTO catalogo.material
              (empresa_id, clave, nombre, categoria_id, unidad_control_id, precio_referencia)
            VALUES (:empresa, :clave, :nombre, :categoria, :unidad, :precio)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("clave", clave)
        .param("nombre", nombre)
        .param("categoria", categoria)
        .param("unidad", unidadControl)
        .param("precio", precio)
        .query(Long.class)
        .single();
  }

  public int actualizar(
      long empresa,
      long id,
      int version,
      String clave,
      String nombre,
      Long categoria,
      long unidadControl,
      BigDecimal precio,
      boolean activo) {
    return jdbc.sql(
            """
            UPDATE catalogo.material
               SET clave = :clave, nombre = :nombre, categoria_id = :categoria,
                   unidad_control_id = :unidad, precio_referencia = :precio, activo = :activo,
                   version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("clave", clave)
        .param("nombre", nombre)
        .param("categoria", categoria)
        .param("unidad", unidadControl)
        .param("precio", precio)
        .param("activo", activo)
        .update();
  }
}

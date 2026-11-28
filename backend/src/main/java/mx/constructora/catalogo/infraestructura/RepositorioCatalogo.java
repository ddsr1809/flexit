package mx.constructora.catalogo.infraestructura;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import mx.constructora.catalogo.aplicacion.ServicioCatalogo.Categoria;
import mx.constructora.catalogo.aplicacion.ServicioCatalogo.ClasificacionCosto;
import mx.constructora.catalogo.aplicacion.ServicioCatalogo.Conversion;
import mx.constructora.catalogo.aplicacion.ServicioCatalogo.UnidadMedida;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Unidades de medida, conversiones, categorías y clasificaciones. Las unidades y conversiones
 * visibles son las del sistema ({@code empresa_id} NULL) más las de la empresa.
 */
@Repository
public class RepositorioCatalogo {

  private static final String UNIDAD =
      """
      SELECT id, clave, nombre, magnitud, fraccionable, empresa_id IS NULL AS del_sistema, activa,
             version
        FROM catalogo.unidad_medida
       WHERE (empresa_id IS NULL OR empresa_id = :empresa)
      """;

  private static final String CONVERSION =
      """
      SELECT cv.id, cv.material_id, cv.origen_id, o.clave AS origen, cv.destino_id,
             d.clave AS destino, cv.factor, cv.empresa_id IS NULL AS del_sistema, cv.version
        FROM catalogo.conversion_unidad cv
        JOIN catalogo.unidad_medida o ON o.id = cv.origen_id
        JOIN catalogo.unidad_medida d ON d.id = cv.destino_id
       WHERE (cv.empresa_id IS NULL OR cv.empresa_id = :empresa)
      """;

  private static final String CATEGORIA =
      "SELECT id, nombre, activa, version FROM catalogo.categoria WHERE empresa_id = :empresa";

  private final JdbcClient jdbc;

  RepositorioCatalogo(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------- Unidades

  public List<UnidadMedida> unidades(long empresa) {
    return jdbc.sql(UNIDAD + " ORDER BY empresa_id IS NOT NULL, magnitud, clave")
        .param("empresa", empresa)
        .query(UnidadMedida.class)
        .list();
  }

  public Optional<UnidadMedida> unidad(long empresa, long id) {
    return jdbc.sql(UNIDAD + " AND id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .query(UnidadMedida.class)
        .optional();
  }

  public long insertarUnidad(
      long empresa, String clave, String nombre, String magnitud, boolean fraccionable) {
    return jdbc.sql(
            """
            INSERT INTO catalogo.unidad_medida (empresa_id, clave, nombre, magnitud, fraccionable)
            VALUES (:empresa, :clave, :nombre, :magnitud, :fraccionable) RETURNING id
            """)
        .param("empresa", empresa)
        .param("clave", clave)
        .param("nombre", nombre)
        .param("magnitud", magnitud)
        .param("fraccionable", fraccionable)
        .query(Long.class)
        .single();
  }

  public int actualizarUnidad(
      long empresa,
      long id,
      int version,
      String clave,
      String nombre,
      String magnitud,
      boolean fraccionable,
      boolean activa) {
    return jdbc.sql(
            """
            UPDATE catalogo.unidad_medida
               SET clave = :clave, nombre = :nombre, magnitud = :magnitud,
                   fraccionable = :fraccionable, activa = :activa, version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("clave", clave)
        .param("nombre", nombre)
        .param("magnitud", magnitud)
        .param("fraccionable", fraccionable)
        .param("activa", activa)
        .update();
  }

  // ------------------------------------------------------------------------------- Conversiones

  /** Las conversiones generales y, si {@code material} no es nulo, las de ese material. */
  public List<Conversion> conversiones(long empresa, Long material) {
    return jdbc.sql(
            CONVERSION
                + """
                   AND (cv.material_id IS NULL OR cv.material_id = :material)
                 ORDER BY cv.material_id NULLS FIRST, o.clave, d.clave
                """)
        .param("empresa", empresa)
        .param("material", material)
        .query(Conversion.class)
        .list();
  }

  public Optional<Conversion> conversion(long empresa, long id) {
    return jdbc.sql(CONVERSION + " AND cv.id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .query(Conversion.class)
        .optional();
  }

  public long insertarConversion(
      long empresa, Long material, long origen, long destino, BigDecimal factor) {
    return jdbc.sql(
            """
            INSERT INTO catalogo.conversion_unidad (empresa_id, material_id, origen_id, destino_id, factor)
            VALUES (:empresa, :material, :origen, :destino, :factor) RETURNING id
            """)
        .param("empresa", empresa)
        .param("material", material)
        .param("origen", origen)
        .param("destino", destino)
        .param("factor", factor)
        .query(Long.class)
        .single();
  }

  public int actualizarFactor(long empresa, long id, int version, BigDecimal factor) {
    return jdbc.sql(
            """
            UPDATE catalogo.conversion_unidad SET factor = :factor, version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("factor", factor)
        .update();
  }

  // ------------------------------------------------------------------------------- Categorías

  public List<Categoria> categorias(long empresa) {
    return jdbc.sql(CATEGORIA + " ORDER BY activa DESC, nombre")
        .param("empresa", empresa)
        .query(Categoria.class)
        .list();
  }

  public Optional<Categoria> categoria(long empresa, long id) {
    return jdbc.sql(CATEGORIA + " AND id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .query(Categoria.class)
        .optional();
  }

  /** La categoría con ese nombre (normalizado). */
  public Optional<Long> categoriaPorNombre(long empresa, String nombre) {
    return jdbc.sql(
            "SELECT id FROM catalogo.categoria WHERE empresa_id = :empresa"
                + " AND nombre_normal = compartido.normalizar(:nombre)")
        .param("empresa", empresa)
        .param("nombre", nombre)
        .query(Long.class)
        .optional();
  }

  public long insertarCategoria(long empresa, String nombre) {
    return jdbc.sql(
            "INSERT INTO catalogo.categoria (empresa_id, nombre) VALUES (:empresa, :nombre)"
                + " RETURNING id")
        .param("empresa", empresa)
        .param("nombre", nombre)
        .query(Long.class)
        .single();
  }

  public int actualizarCategoria(
      long empresa, long id, int version, String nombre, boolean activa) {
    return jdbc.sql(
            """
            UPDATE catalogo.categoria SET nombre = :nombre, activa = :activa, version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("nombre", nombre)
        .param("activa", activa)
        .update();
  }

  // ------------------------------------------------------------------------------- Clasificaciones

  public List<ClasificacionCosto> clasificaciones() {
    return jdbc.sql(
            "SELECT id, clave, nombre, consume_programa FROM catalogo.clasificacion_costo"
                + " ORDER BY id")
        .query(ClasificacionCosto.class)
        .list();
  }
}

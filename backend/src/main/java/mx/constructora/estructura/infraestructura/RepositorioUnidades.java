package mx.constructora.estructura.infraestructura;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import mx.constructora.estructura.aplicacion.ServicioUnidades.UnidadObra;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Unidades de obra (lotes, edificios, tramos). */
@Repository
public class RepositorioUnidades {

  private static final String UNIDAD =
      """
      SELECT u.id, u.contrato_id, t.clave AS tipo, u.clave, u.manzana, u.lote, u.prototipo_id,
             p.clave AS prototipo, u.presupuesto_material, u.activa, u.version
        FROM estructura.unidad_obra u
        JOIN estructura.tipo_unidad t ON t.id = u.tipo_id
        LEFT JOIN estructura.prototipo p ON p.id = u.prototipo_id
       WHERE u.empresa_id = :empresa
      """;

  /**
   * Orden natural de lotes: manzana y lote como números (M2 antes que M10), luego la letra; las
   * unidades sin manzana al final por clave.
   */
  private static final String ORDEN =
      """
       ORDER BY u.manzana IS NULL,
                substring(u.manzana from '^[0-9]+')::int, u.manzana,
                substring(u.lote from '^[0-9]+')::int, u.lote, u.clave
      """;

  private final JdbcClient jdbc;

  RepositorioUnidades(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<UnidadObra> unidades(long empresa, long contrato) {
    return jdbc.sql(UNIDAD + " AND u.contrato_id = :contrato" + ORDEN)
        .param("empresa", empresa)
        .param("contrato", contrato)
        .query(UnidadObra.class)
        .list();
  }

  public Optional<UnidadObra> unidad(long empresa, long id) {
    return jdbc.sql(UNIDAD + " AND u.id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .query(UnidadObra.class)
        .optional();
  }

  /**
   * De {@code claves}, las que ya existen en el contrato o, si el contrato tiene frente, en
   * cualquier contrato del mismo frente.
   */
  public List<String> clavesExistentes(
      long empresa, long contrato, Long frente, List<String> claves) {
    return jdbc.sql(
            """
            SELECT u.clave FROM estructura.unidad_obra u
             WHERE u.empresa_id = :empresa
               AND u.clave IN (:claves)
               AND (u.contrato_id = :contrato
                    OR (CAST(:frente AS bigint) IS NOT NULL AND u.frente_id = :frente))
             ORDER BY u.clave
            """)
        .param("empresa", empresa)
        .param("contrato", contrato)
        .param("frente", frente)
        .param("claves", claves)
        .query(String.class)
        .list();
  }

  public long insertar(
      long empresa,
      long contrato,
      Long frente,
      int tipo,
      String clave,
      String manzana,
      String lote,
      Long prototipo,
      BigDecimal presupuesto) {
    return jdbc.sql(
            """
            INSERT INTO estructura.unidad_obra
              (empresa_id, contrato_id, frente_id, tipo_id, clave, manzana, lote, prototipo_id,
               presupuesto_material)
            VALUES (:empresa, :contrato, :frente, :tipo, :clave, :manzana, :lote, :prototipo,
                    :presupuesto)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("contrato", contrato)
        .param("frente", frente)
        .param("tipo", (short) tipo)
        .param("clave", clave)
        .param("manzana", manzana)
        .param("lote", lote)
        .param("prototipo", prototipo)
        .param("presupuesto", presupuesto)
        .query(Long.class)
        .single();
  }

  public int actualizar(
      long empresa, long id, int version, Long prototipo, BigDecimal presupuesto, boolean activa) {
    return jdbc.sql(
            """
            UPDATE estructura.unidad_obra
               SET prototipo_id = :prototipo, presupuesto_material = :presupuesto,
                   activa = :activa, version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("prototipo", prototipo)
        .param("presupuesto", presupuesto)
        .param("activa", activa)
        .update();
  }
}

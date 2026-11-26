package mx.constructora.inventario.infraestructura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Conteos físicos y sus líneas. */
@Repository
public class RepositorioConteos {

  /** Conteo tal como está en la base. */
  public record FilaConteo(
      long id,
      String folio,
      long almacenId,
      LocalDate fecha,
      String estado,
      long usuarioCrea,
      Long usuarioCierra,
      OffsetDateTime cerradoEn,
      String observaciones,
      int lineas,
      int contadas,
      int version,
      OffsetDateTime creado) {}

  /** Línea de conteo. */
  public record FilaLineaConteo(
      long materialId,
      BigDecimal contado,
      BigDecimal costoUnitario,
      BigDecimal existenciaSistema,
      Long contadoPor,
      OffsetDateTime contadoEn) {}

  private static final String CONTEO =
      """
      SELECT c.id, c.folio, c.almacen_id, c.fecha, c.estado, c.usuario_crea, c.usuario_cierra,
             c.cerrado_en, c.observaciones,
             (SELECT count(*) FROM inventario.conteo_linea l
               WHERE l.empresa_id = c.empresa_id AND l.conteo_id = c.id)::int AS lineas,
             (SELECT count(*) FROM inventario.conteo_linea l
               WHERE l.empresa_id = c.empresa_id AND l.conteo_id = c.id
                 AND l.contado IS NOT NULL)::int AS contadas,
             c.version, c.creado
        FROM inventario.conteo c
       WHERE c.empresa_id = :empresa
      """;

  private final JdbcClient jdbc;

  RepositorioConteos(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<FilaConteo> conteos(long empresa, Long almacen) {
    return jdbc.sql(
            CONTEO
                + """
                   AND (CAST(:almacen AS bigint) IS NULL OR c.almacen_id = :almacen)
                 ORDER BY c.estado = 'abierto' DESC, c.fecha DESC, c.id DESC
                 LIMIT 200
                """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .query(FilaConteo.class)
        .list();
  }

  public Optional<FilaConteo> conteo(long empresa, long id, boolean bloquear) {
    return jdbc.sql(CONTEO + " AND c.id = :id" + (bloquear ? " FOR UPDATE OF c" : ""))
        .param("empresa", empresa)
        .param("id", id)
        .query(FilaConteo.class)
        .optional();
  }

  public List<FilaLineaConteo> lineas(long empresa, long conteo) {
    return jdbc.sql(
            """
            SELECT material_id, contado, costo_unitario, existencia_sistema, contado_por, contado_en
              FROM inventario.conteo_linea
             WHERE empresa_id = :empresa AND conteo_id = :conteo
             ORDER BY material_id
            """)
        .param("empresa", empresa)
        .param("conteo", conteo)
        .query(FilaLineaConteo.class)
        .list();
  }

  public long insertar(
      long empresa, String folio, long almacen, LocalDate fecha, long usuario, String obs) {
    return jdbc.sql(
            """
            INSERT INTO inventario.conteo (empresa_id, folio, almacen_id, fecha, usuario_crea, observaciones)
            VALUES (:empresa, :folio, :almacen, :fecha, :usuario, :obs) RETURNING id
            """)
        .param("empresa", empresa)
        .param("folio", folio)
        .param("almacen", almacen)
        .param("fecha", fecha)
        .param("usuario", usuario)
        .param("obs", obs)
        .query(Long.class)
        .single();
  }

  /** Agrega los materiales que falten al conteo (sin contar). */
  public void agregarMateriales(long empresa, long conteo, Collection<Long> materiales) {
    for (Long material : materiales) {
      jdbc.sql(
              """
              INSERT INTO inventario.conteo_linea (empresa_id, conteo_id, material_id)
              VALUES (:empresa, :conteo, :material)
              ON CONFLICT DO NOTHING
              """)
          .param("empresa", empresa)
          .param("conteo", conteo)
          .param("material", material)
          .update();
    }
  }

  /** Captura (o corrige) lo contado de un material; lo agrega si no estaba. */
  public void capturar(
      long empresa,
      long conteo,
      long material,
      BigDecimal contado,
      BigDecimal costo,
      long usuario) {
    jdbc.sql(
            """
            INSERT INTO inventario.conteo_linea
              (empresa_id, conteo_id, material_id, contado, costo_unitario, contado_por, contado_en)
            VALUES (:empresa, :conteo, :material, :contado, :costo, :usuario, now())
            ON CONFLICT (conteo_id, material_id) DO UPDATE
               SET contado = EXCLUDED.contado, costo_unitario = EXCLUDED.costo_unitario,
                   contado_por = EXCLUDED.contado_por, contado_en = EXCLUDED.contado_en
            """)
        .param("empresa", empresa)
        .param("conteo", conteo)
        .param("material", material)
        .param("contado", contado)
        .param("costo", costo)
        .param("usuario", usuario)
        .update();
  }

  public void fijarExistenciaSistema(
      long empresa, long conteo, long material, BigDecimal existencia) {
    jdbc.sql(
            """
            UPDATE inventario.conteo_linea SET existencia_sistema = :existencia
             WHERE empresa_id = :empresa AND conteo_id = :conteo AND material_id = :material
            """)
        .param("empresa", empresa)
        .param("conteo", conteo)
        .param("material", material)
        .param("existencia", existencia)
        .update();
  }

  public void cerrar(long empresa, long conteo, long usuario) {
    jdbc.sql(
            """
            UPDATE inventario.conteo
               SET estado = 'cerrado', usuario_cierra = :usuario, cerrado_en = now(),
                   version = version + 1
             WHERE empresa_id = :empresa AND id = :conteo AND estado = 'abierto'
            """)
        .param("empresa", empresa)
        .param("conteo", conteo)
        .param("usuario", usuario)
        .update();
  }

  public void cancelar(long empresa, long conteo) {
    jdbc.sql(
            """
            UPDATE inventario.conteo SET estado = 'cancelado', version = version + 1
             WHERE empresa_id = :empresa AND id = :conteo AND estado = 'abierto'
            """)
        .param("empresa", empresa)
        .param("conteo", conteo)
        .update();
  }
}

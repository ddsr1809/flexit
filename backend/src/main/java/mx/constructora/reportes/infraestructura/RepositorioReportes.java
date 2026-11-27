package mx.constructora.reportes.infraestructura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Consultas de solo lectura sobre las vistas del esquema {@code reportes}: costo de obra (histórico
 * del Excel + cargos del sistema), destajo, presupuesto por lote y existencia valorizada. Las
 * vistas se leen con el RLS de quien consulta ({@code security_invoker}); además se filtra por
 * empresa y, para el residente, por sus contratos.
 */
@Repository
public class RepositorioReportes {

  /** Total de costo de una llave (contrato, lote, paquete…). {@code llave} nula: sin ese dato. */
  public record FilaCosto(
      String llave,
      BigDecimal historico,
      BigDecimal sistema,
      BigDecimal total,
      BigDecimal cantidad,
      boolean estimado,
      int movimientos) {}

  /** Costo de un lote por clasificación y fuente. */
  public record FilaLoteClasificacion(
      Long unidadObraId,
      Integer clasificacionId,
      String fuente,
      BigDecimal importe,
      boolean estimado) {}

  /** Destajo pagado por llave (lote o contrato). */
  public record FilaDestajo(Long llave, BigDecimal monto) {}

  /** Presupuesto de un lote contra lo consumido de Programa. */
  public record FilaPresupuesto(
      long contratoId,
      long unidadObraId,
      String clave,
      Long prototipoId,
      BigDecimal presupuesto,
      BigDecimal consumido) {}

  /** Existencia valorizada de un material en un almacén. */
  public record FilaExistencia(
      long almacenId,
      long materialId,
      BigDecimal existencia,
      BigDecimal valor,
      BigDecimal costoPromedio,
      BigDecimal reservado,
      BigDecimal prestado,
      BigDecimal disponible) {}

  /** Filtros del reporte de costo; los nulos no filtran. */
  public record Filtro(
      Long contratoId,
      Long frenteId,
      Long unidadObraId,
      Long paqueteId,
      Integer clasificacionId,
      Long materialId,
      LocalDate desde,
      LocalDate hasta,
      String fuente) {}

  /** Agrupaciones permitidas → expresión SQL (lista cerrada: nunca texto del usuario). */
  public static final Map<String, String> AGRUPACIONES =
      Map.of(
          "contrato", "contrato_id::text",
          "frente", "frente_id::text",
          "unidad", "unidad_obra_id::text",
          "paquete", "paquete_id::text",
          "clasificacion", "clasificacion_id::text",
          "material", "material_id::text",
          "mes", "to_char(fecha, 'YYYY-MM')");

  private final JdbcClient jdbc;

  RepositorioReportes(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Costo agrupado; {@code contratos} nulo: todos (si no, solo esos y nada sin contrato). */
  public List<FilaCosto> costo(long empresa, String agrupar, Filtro f, Collection<Long> contratos) {
    String expresion = AGRUPACIONES.get(agrupar);
    if (expresion == null) {
      throw new IllegalArgumentException("Agrupación no permitida: " + agrupar);
    }
    return jdbc.sql(
            """
            SELECT %s AS llave,
                   coalesce(sum(importe) FILTER (WHERE fuente = 'historico'), 0) AS historico,
                   coalesce(sum(importe) FILTER (WHERE fuente = 'sistema'), 0) AS sistema,
                   coalesce(sum(importe), 0) AS total,
                   coalesce(sum(cantidad), 0) AS cantidad,
                   coalesce(bool_or(costo_estimado), false) AS estimado,
                   count(*)::int AS movimientos
              FROM reportes.v_costo_unidad
             WHERE empresa_id = :empresa
            """
                    .formatted(expresion)
                + FILTROS
                + " GROUP BY 1 ORDER BY 4 DESC, 1")
        .params(parametros(empresa, f, contratos))
        .query(FilaCosto.class)
        .list();
  }

  private static final String FILTROS =
      """
         AND (CAST(:contrato AS bigint) IS NULL OR contrato_id = :contrato)
         AND (CAST(:frente AS bigint) IS NULL OR frente_id = :frente)
         AND (CAST(:unidad AS bigint) IS NULL OR unidad_obra_id = :unidad)
         AND (CAST(:paquete AS bigint) IS NULL OR paquete_id = :paquete)
         AND (CAST(:clasificacion AS integer) IS NULL OR clasificacion_id = :clasificacion)
         AND (CAST(:material AS bigint) IS NULL OR material_id = :material)
         AND (CAST(:desde AS date) IS NULL OR fecha >= :desde)
         AND (CAST(:hasta AS date) IS NULL OR fecha <= :hasta)
         AND (CAST(:fuente AS text) IS NULL OR fuente = :fuente)
         AND (:todos OR contrato_id IN (:contratos))
      """;

  private static Map<String, Object> parametros(
      long empresa, Filtro f, Collection<Long> contratos) {
    var p = new java.util.HashMap<String, Object>();
    p.put("empresa", empresa);
    p.put("contrato", f.contratoId());
    p.put("frente", f.frenteId());
    p.put("unidad", f.unidadObraId());
    p.put("paquete", f.paqueteId());
    p.put("clasificacion", f.clasificacionId());
    p.put("material", f.materialId());
    p.put("desde", f.desde());
    p.put("hasta", f.hasta());
    p.put("fuente", f.fuente());
    p.put("todos", contratos == null);
    p.put(
        "contratos",
        contratos == null || contratos.isEmpty() ? List.of(-1L) : List.copyOf(contratos));
    return p;
  }

  /** Costo de los lotes de un contrato por clasificación y fuente (lote nulo: sin asignar). */
  public List<FilaLoteClasificacion> costoPorLote(long empresa, long contrato) {
    return jdbc.sql(
            """
            SELECT unidad_obra_id, clasificacion_id, fuente, sum(importe) AS importe,
                   coalesce(bool_or(costo_estimado), false) AS estimado
              FROM reportes.v_costo_unidad
             WHERE empresa_id = :empresa AND contrato_id = :contrato
             GROUP BY unidad_obra_id, clasificacion_id, fuente
            """)
        .param("empresa", empresa)
        .param("contrato", contrato)
        .query(FilaLoteClasificacion.class)
        .list();
  }

  /** Destajo pagado por lote de un contrato. */
  public List<FilaDestajo> destajoPorLote(long empresa, long contrato) {
    return jdbc.sql(
            """
            SELECT unidad_obra_id AS llave, sum(monto) AS monto
              FROM reportes.v_destajo_unidad
             WHERE empresa_id = :empresa AND contrato_id = :contrato
             GROUP BY unidad_obra_id
            """)
        .param("empresa", empresa)
        .param("contrato", contrato)
        .query(FilaDestajo.class)
        .list();
  }

  /** Destajo pagado por contrato. */
  public List<FilaDestajo> destajoPorContrato(long empresa, Collection<Long> contratos) {
    return jdbc.sql(
            """
            SELECT contrato_id AS llave, sum(monto) AS monto
              FROM reportes.v_destajo_unidad
             WHERE empresa_id = :empresa AND contrato_id IS NOT NULL
               AND (:todos OR contrato_id IN (:contratos))
             GROUP BY contrato_id
            """)
        .param("empresa", empresa)
        .param("todos", contratos == null)
        .param(
            "contratos",
            contratos == null || contratos.isEmpty() ? List.of(-1L) : List.copyOf(contratos))
        .query(FilaDestajo.class)
        .list();
  }

  /**
   * Presupuesto contra consumido por lote. {@code contrato} o {@code unidades} acotan; {@code
   * contratos} es el alcance (nulo: todos). Con {@code soloConPresupuesto}, solo los que tienen.
   */
  public List<FilaPresupuesto> presupuesto(
      long empresa,
      Long contrato,
      Collection<Long> unidades,
      Collection<Long> contratos,
      boolean soloConPresupuesto) {
    return jdbc.sql(
            """
            SELECT contrato_id, unidad_obra_id, clave, prototipo_id, presupuesto, consumido
              FROM reportes.v_presupuesto_unidad
             WHERE empresa_id = :empresa
               AND (CAST(:contrato AS bigint) IS NULL OR contrato_id = :contrato)
               AND (:todasUnidades OR unidad_obra_id IN (:unidades))
               AND (:todos OR contrato_id IN (:contratos))
               AND (NOT :soloConPresupuesto OR presupuesto IS NOT NULL)
             ORDER BY contrato_id, clave
            """)
        .param("empresa", empresa)
        .param("contrato", contrato)
        .param("todasUnidades", unidades == null)
        .param(
            "unidades",
            unidades == null || unidades.isEmpty() ? List.of(-1L) : List.copyOf(unidades))
        .param("todos", contratos == null)
        .param(
            "contratos",
            contratos == null || contratos.isEmpty() ? List.of(-1L) : List.copyOf(contratos))
        .param("soloConPresupuesto", soloConPresupuesto)
        .query(FilaPresupuesto.class)
        .list();
  }

  /** Existencia valorizada; {@code almacen} nulo: todos los almacenes. */
  public List<FilaExistencia> existencias(long empresa, Long almacen) {
    return jdbc.sql(
            """
            SELECT almacen_id, material_id, existencia, valor, costo_promedio, reservado, prestado,
                   disponible
              FROM reportes.v_existencia_valorizada
             WHERE empresa_id = :empresa
               AND (CAST(:almacen AS bigint) IS NULL OR almacen_id = :almacen)
             ORDER BY almacen_id, valor DESC, material_id
            """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .query(FilaExistencia.class)
        .list();
  }
}

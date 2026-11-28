package mx.constructora.inventario.infraestructura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Cómo se está usando un almacén (H9): documentos por día, capturas tardías, pendientes viejos,
 * tiempo de autorización de los vales, préstamos y conteos. Los días se cuentan en hora de México.
 *
 * <p>Captura tardía: el documento se capturó más de {@code tolerancia} días después de su fecha. No
 * cuentan los que genera el sistema con la fecha de otro documento: el saldo de apertura y los
 * ajustes de un conteo.
 */
@Repository
public class RepositorioActividad {

  private static final String CAPTURADO = "(m.creado AT TIME ZONE 'America/Mexico_City')::date";
  private static final String GENERADOS = "('apertura', 'ajuste_entrada', 'ajuste_salida')";

  /** Lo que pasó un día, por fecha del documento. */
  public record FilaDia(
      LocalDate fecha, int documentos, int salidas, int entradas, int tardios, int cancelados) {}

  /** Un documento capturado tarde. */
  public record FilaTardio(
      String folio, String tipo, String estado, LocalDate fecha, LocalDate capturado, int dias) {}

  /** Un documento sin aplicar ni cancelar y cuántos días lleva. */
  public record FilaPendienteViejo(
      String folio, String tipo, String estado, LocalDate fecha, int dias) {}

  /** Préstamos y conteos del almacén. */
  public record FilaOtros(
      int prestamosAbiertos,
      int prestamosVencidos,
      int conteosCerrados,
      int ajustesDeConteo,
      BigDecimal medianaHorasVale) {}

  private final JdbcClient jdbc;

  RepositorioActividad(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** La fecha del saldo de apertura aplicado del almacén, si ya abrió. */
  public Optional<LocalDate> apertura(long empresa, long almacen) {
    return jdbc.sql(
            """
            SELECT fecha FROM inventario.apertura
             WHERE empresa_id = :empresa AND almacen_id = :almacen AND estado = 'aplicada'
            """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .query(LocalDate.class)
        .optional();
  }

  /** Un renglón por cada día del periodo, aunque no haya documentos. */
  public List<FilaDia> dias(
      long empresa, long almacen, LocalDate desde, LocalDate hasta, int tolerancia) {
    return jdbc.sql(
            """
            SELECT d.fecha::date AS fecha,
                   count(m.id)::int AS documentos,
                   count(m.id) FILTER (WHERE m.tipo = 'salida' AND m.estado = 'aplicado')::int
                     AS salidas,
                   count(m.id) FILTER (WHERE m.tipo = 'entrada' AND m.estado = 'aplicado')::int
                     AS entradas,
                   count(m.id) FILTER (WHERE m.tipo NOT IN %s
                                         AND %s - m.fecha > :tolerancia)::int AS tardios,
                   count(m.id) FILTER (WHERE m.estado = 'cancelado')::int AS cancelados
              FROM generate_series(CAST(:desde AS date), CAST(:hasta AS date), interval '1 day')
                   AS d(fecha)
              LEFT JOIN inventario.movimiento m
                ON m.empresa_id = :empresa AND m.almacen_id = :almacen AND m.fecha = d.fecha::date
             GROUP BY d.fecha
             ORDER BY d.fecha
            """
                .formatted(GENERADOS, CAPTURADO))
        .param("empresa", empresa)
        .param("almacen", almacen)
        .param("desde", desde)
        .param("hasta", hasta)
        .param("tolerancia", tolerancia)
        .query(FilaDia.class)
        .list();
  }

  /** Los documentos del periodo capturados tarde, del más tardío al menos (hasta 100). */
  public List<FilaTardio> tardios(
      long empresa, long almacen, LocalDate desde, LocalDate hasta, int tolerancia) {
    return jdbc.sql(
            """
            SELECT m.folio, m.tipo, m.estado, m.fecha, %1$s AS capturado,
                   (%1$s - m.fecha)::int AS dias
              FROM inventario.movimiento m
             WHERE m.empresa_id = :empresa AND m.almacen_id = :almacen
               AND m.fecha BETWEEN :desde AND :hasta
               AND m.tipo NOT IN %2$s AND %1$s - m.fecha > :tolerancia
             ORDER BY dias DESC, m.fecha, m.id
             LIMIT 100
            """
                .formatted(CAPTURADO, GENERADOS))
        .param("empresa", empresa)
        .param("almacen", almacen)
        .param("desde", desde)
        .param("hasta", hasta)
        .param("tolerancia", tolerancia)
        .query(FilaTardio.class)
        .list();
  }

  /** Documentos del almacén sin aplicar ni cancelar, del más viejo al más nuevo (hasta 100). */
  public List<FilaPendienteViejo> pendientes(long empresa, long almacen, LocalDate hoy) {
    return jdbc.sql(
            """
            SELECT m.folio, m.tipo, m.estado, m.fecha, (CAST(:hoy AS date) - m.fecha)::int AS dias
              FROM inventario.movimiento m
             WHERE m.empresa_id = :empresa AND m.almacen_id = :almacen
               AND m.estado IN ('borrador', 'pendiente', 'autorizado')
             ORDER BY m.fecha, m.id
             LIMIT 100
            """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .param("hoy", hoy)
        .query(FilaPendienteViejo.class)
        .list();
  }

  /** Cuántos documentos sin aplicar hay en total (la lista se corta en 100). */
  public int totalPendientes(long empresa, long almacen) {
    return jdbc.sql(
            """
            SELECT count(*)::int FROM inventario.movimiento
             WHERE empresa_id = :empresa AND almacen_id = :almacen
               AND estado IN ('borrador', 'pendiente', 'autorizado')
            """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .query(Integer.class)
        .single();
  }

  /**
   * Préstamos abiertos y vencidos a {@code hoy}; conteos cerrados y renglones de ajuste de conteo
   * aplicados en el periodo; mediana de horas de captura a aplicación de los vales del periodo.
   */
  public FilaOtros otros(
      long empresa, long almacen, LocalDate desde, LocalDate hasta, LocalDate hoy) {
    return jdbc.sql(
            """
            SELECT
              (SELECT count(*)::int FROM inventario.prestamo
                WHERE empresa_id = :empresa AND almacen_id = :almacen AND estado = 'abierto')
                AS prestamos_abiertos,
              (SELECT count(*)::int FROM inventario.prestamo
                WHERE empresa_id = :empresa AND almacen_id = :almacen AND estado = 'abierto'
                  AND compromiso < :hoy) AS prestamos_vencidos,
              (SELECT count(*)::int FROM inventario.conteo c
                WHERE c.empresa_id = :empresa AND c.almacen_id = :almacen AND c.estado = 'cerrado'
                  AND (c.cerrado_en AT TIME ZONE 'America/Mexico_City')::date
                      BETWEEN :desde AND :hasta
                  AND NOT EXISTS (SELECT 1 FROM inventario.apertura a
                                   WHERE a.empresa_id = c.empresa_id AND a.conteo_id = c.id))
                AS conteos_cerrados,
              (SELECT count(*)::int FROM inventario.movimiento_linea l
                 JOIN inventario.movimiento m
                   ON m.empresa_id = l.empresa_id AND m.id = l.movimiento_id
                WHERE m.empresa_id = :empresa AND m.almacen_id = :almacen
                  AND m.tipo IN ('ajuste_entrada', 'ajuste_salida') AND m.estado = 'aplicado'
                  AND m.fecha BETWEEN :desde AND :hasta) AS ajustes_de_conteo,
              (SELECT round(CAST(percentile_cont(0.5) WITHIN GROUP (
                        ORDER BY extract(epoch FROM aplicado_en - creado) / 3600) AS numeric), 1)
                 FROM inventario.movimiento
                WHERE empresa_id = :empresa AND almacen_id = :almacen AND tipo = 'salida'
                  AND estado = 'aplicado' AND fecha BETWEEN :desde AND :hasta)
                AS mediana_horas_vale
            """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .param("desde", desde)
        .param("hasta", hasta)
        .param("hoy", hoy)
        .query(FilaOtros.class)
        .single();
  }

  /** El último mes cerrado de la empresa (AAAA-MM), si hay. */
  public Optional<String> ultimoMesCerrado(long empresa) {
    return jdbc.sql(
            """
            SELECT anio || '-' || lpad(mes::text, 2, '0') FROM inventario.periodo
             WHERE empresa_id = :empresa ORDER BY anio DESC, mes DESC LIMIT 1
            """)
        .param("empresa", empresa)
        .query(String.class)
        .optional();
  }
}

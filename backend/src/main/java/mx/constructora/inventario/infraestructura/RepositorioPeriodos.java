package mx.constructora.inventario.infraestructura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Meses cerrados, la foto del inventario al cierre, y lo que hay que revisar antes de cerrar: los
 * documentos, conteos y aperturas sin terminar (bloquean, también en la base con V012) y lo que
 * conviene saber (préstamos vencidos, recepciones sin factura).
 */
@Repository
public class RepositorioPeriodos {

  /** Mes cerrado. */
  public record FilaPeriodo(int anio, int mes, long cerradoPor, OffsetDateTime cerradoEn) {}

  private final JdbcClient jdbc;

  RepositorioPeriodos(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<FilaPeriodo> cerrados(long empresa) {
    return jdbc.sql(
            """
            SELECT anio, mes, cerrado_por, cerrado_en FROM inventario.periodo
             WHERE empresa_id = :empresa ORDER BY anio DESC, mes DESC
            """)
        .param("empresa", empresa)
        .query(FilaPeriodo.class)
        .list();
  }

  public boolean cerrado(long empresa, LocalDate fecha) {
    return jdbc.sql("SELECT inventario.periodo_cerrado(:empresa, :fecha)")
        .param("empresa", empresa)
        .param("fecha", fecha)
        .query(Boolean.class)
        .single();
  }

  /** Documentos sin aplicar ni cancelar con fecha en el mes. */
  public int pendientes(long empresa, LocalDate desde, LocalDate hasta) {
    return jdbc.sql(
            """
            SELECT count(*)::int FROM inventario.movimiento
             WHERE empresa_id = :empresa AND fecha BETWEEN :desde AND :hasta
               AND estado IN ('borrador', 'pendiente', 'autorizado')
            """)
        .param("empresa", empresa)
        .param("desde", desde)
        .param("hasta", hasta)
        .query(Integer.class)
        .single();
  }

  /** El mes más antiguo con movimientos que todavía no está cerrado. */
  public LocalDate primerMovimiento(long empresa) {
    return jdbc
        .sql("SELECT min(fecha) FROM inventario.movimiento WHERE empresa_id = :empresa")
        .param("empresa", empresa)
        .query(LocalDate.class)
        .list()
        .stream()
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /**
   * Cierra el mes y guarda la foto: existencia y valor por almacén y material con fecha de
   * documento hasta {@code hasta}. Devuelve cuántos renglones tiene la foto.
   */
  public int cerrar(long empresa, int anio, int mes, LocalDate hasta, long usuario) {
    jdbc.sql(
            """
            INSERT INTO inventario.periodo (empresa_id, anio, mes, cerrado_por)
            VALUES (:empresa, :anio, :mes, :usuario)
            """)
        .param("empresa", empresa)
        .param("anio", anio)
        .param("mes", mes)
        .param("usuario", usuario)
        .update();
    return jdbc.sql(
            """
            INSERT INTO inventario.cierre_saldo (empresa_id, anio, mes, almacen_id, material_id, existencia, valor)
            SELECT empresa_id, :anio, :mes, almacen_id, material_id, sum(cantidad), sum(importe)
              FROM inventario.kardex
             WHERE empresa_id = :empresa AND fecha <= :hasta
             GROUP BY empresa_id, almacen_id, material_id
            HAVING sum(cantidad) <> 0 OR sum(importe) <> 0
            """)
        .param("empresa", empresa)
        .param("anio", anio)
        .param("mes", mes)
        .param("hasta", hasta)
        .update();
  }

  /** Valor del inventario al cierre del mes, por almacén. */
  public record ValorCierre(long almacenId, BigDecimal valor, int materiales) {}

  public List<ValorCierre> valorAlCierre(long empresa, int anio, int mes) {
    return jdbc.sql(
            """
            SELECT almacen_id, sum(valor) AS valor, count(*)::int AS materiales
              FROM inventario.cierre_saldo
             WHERE empresa_id = :empresa AND anio = :anio AND mes = :mes
             GROUP BY almacen_id ORDER BY almacen_id
            """)
        .param("empresa", empresa)
        .param("anio", anio)
        .param("mes", mes)
        .query(ValorCierre.class)
        .list();
  }

  // ------------------------------------------------------------------------------ Revisión

  /** Un documento, conteo, apertura o préstamo que sale en la revisión del cierre. */
  public record FilaPendiente(
      String folio, String tipo, String estado, LocalDate fecha, long almacenId) {}

  /** Lo que se lista en la revisión: los primeros {@code limite} y cuántos son en total. */
  public record Pendientes(List<FilaPendiente> primeros, int total) {}

  private static final int LIMITE = 50;

  /** Documentos del mes sin aplicar ni cancelar. */
  public Pendientes documentosSinAplicar(long empresa, LocalDate desde, LocalDate hasta) {
    return pendientes(
        """
        SELECT folio, tipo, estado, fecha, almacen_id, count(*) OVER ()::int AS total
          FROM inventario.movimiento
         WHERE empresa_id = :empresa AND fecha BETWEEN :desde AND :hasta
           AND estado IN ('borrador', 'pendiente', 'autorizado')
         ORDER BY fecha, id LIMIT :limite
        """,
        empresa,
        desde,
        hasta);
  }

  /** Aperturas sin aplicar con fecha hasta el fin del mes. */
  public Pendientes aperturasAbiertas(long empresa, LocalDate desde, LocalDate hasta) {
    return pendientes(
        """
        SELECT folio, 'apertura' AS tipo, estado, fecha, almacen_id,
               count(*) OVER ()::int AS total
          FROM inventario.apertura
         WHERE empresa_id = :empresa AND estado IN ('abierta', 'en_firma') AND fecha <= :hasta
           AND CAST(:desde AS date) IS NOT NULL
         ORDER BY fecha, id LIMIT :limite
        """,
        empresa,
        desde,
        hasta);
  }

  /**
   * Conteos abiertos con fecha hasta el fin del mes, sin los de una apertura vigente (esos salen
   * con su apertura).
   */
  public Pendientes conteosAbiertos(long empresa, LocalDate desde, LocalDate hasta) {
    return pendientes(
        """
        SELECT c.folio, 'conteo' AS tipo, c.estado, c.fecha, c.almacen_id,
               count(*) OVER ()::int AS total
          FROM inventario.conteo c
         WHERE c.empresa_id = :empresa AND c.estado = 'abierto' AND c.fecha <= :hasta
           AND CAST(:desde AS date) IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM inventario.apertura a
                            WHERE a.empresa_id = c.empresa_id AND a.conteo_id = c.id
                              AND a.estado IN ('abierta', 'en_firma'))
         ORDER BY c.fecha, c.id LIMIT :limite
        """,
        empresa,
        desde,
        hasta);
  }

  /** Préstamos abiertos que debían regresar a más tardar el fin del mes (fecha: el compromiso). */
  public Pendientes prestamosVencidos(long empresa, LocalDate desde, LocalDate hasta) {
    return pendientes(
        """
        SELECT folio, 'prestamo' AS tipo, estado, compromiso AS fecha, almacen_id,
               count(*) OVER ()::int AS total
          FROM inventario.prestamo
         WHERE empresa_id = :empresa AND estado = 'abierto' AND compromiso <= :hasta
           AND CAST(:desde AS date) IS NOT NULL
         ORDER BY compromiso, id LIMIT :limite
        """,
        empresa,
        desde,
        hasta);
  }

  /**
   * Recepciones aplicadas del mes sin folio de factura: si la factura trae otro precio, su ajuste
   * de costo ya entra con fecha del mes siguiente.
   */
  public Pendientes recepcionesSinFactura(long empresa, LocalDate desde, LocalDate hasta) {
    return pendientes(
        """
        SELECT folio, tipo, estado, fecha, almacen_id, count(*) OVER ()::int AS total
          FROM inventario.movimiento
         WHERE empresa_id = :empresa AND tipo = 'entrada' AND estado = 'aplicado'
           AND folio_factura IS NULL AND fecha BETWEEN :desde AND :hasta
         ORDER BY fecha, id LIMIT :limite
        """,
        empresa,
        desde,
        hasta);
  }

  private record FilaConTotal(
      String folio, String tipo, String estado, LocalDate fecha, long almacenId, int total) {}

  private Pendientes pendientes(String sql, long empresa, LocalDate desde, LocalDate hasta) {
    List<FilaConTotal> filas =
        jdbc.sql(sql)
            .param("empresa", empresa)
            .param("desde", desde)
            .param("hasta", hasta)
            .param("limite", LIMITE)
            .query(FilaConTotal.class)
            .list();
    return new Pendientes(
        filas.stream()
            .map(f -> new FilaPendiente(f.folio(), f.tipo(), f.estado(), f.fecha(), f.almacenId()))
            .toList(),
        filas.isEmpty() ? 0 : filas.getFirst().total());
  }

  // ------------------------------------------------------------------------------ Acta

  /** Un material en la foto del cierre. */
  public record FilaCierre(
      long almacenId, long materialId, BigDecimal existencia, BigDecimal valor) {}

  public List<FilaCierre> fotoAlCierre(long empresa, int anio, int mes) {
    return jdbc.sql(
            """
            SELECT almacen_id, material_id, existencia, valor FROM inventario.cierre_saldo
             WHERE empresa_id = :empresa AND anio = :anio AND mes = :mes
             ORDER BY almacen_id, material_id
            """)
        .param("empresa", empresa)
        .param("anio", anio)
        .param("mes", mes)
        .query(FilaCierre.class)
        .list();
  }

  /** Lo que movió el kardex en el mes, por almacén y tipo de documento. */
  public record FilaMovimientosMes(
      long almacenId, String tipo, int documentos, BigDecimal importe) {}

  public List<FilaMovimientosMes> movimientosDelMes(
      long empresa, LocalDate desde, LocalDate hasta) {
    return jdbc.sql(
            """
            SELECT k.almacen_id, m.tipo, count(DISTINCT k.movimiento_id)::int AS documentos,
                   sum(k.importe) AS importe
              FROM inventario.kardex k
              JOIN inventario.movimiento m
                ON m.empresa_id = k.empresa_id AND m.id = k.movimiento_id
             WHERE k.empresa_id = :empresa AND k.fecha BETWEEN :desde AND :hasta
             GROUP BY k.almacen_id, m.tipo
             ORDER BY k.almacen_id, m.tipo
            """)
        .param("empresa", empresa)
        .param("desde", desde)
        .param("hasta", hasta)
        .query(FilaMovimientosMes.class)
        .list();
  }

  /** Valor del kardex por almacén con fecha antes de {@code antesDe}: el valor inicial del mes. */
  public record ValorInicial(long almacenId, BigDecimal valor) {}

  public List<ValorInicial> valorInicial(long empresa, LocalDate antesDe) {
    return jdbc.sql(
            """
            SELECT almacen_id, sum(importe) AS valor FROM inventario.kardex
             WHERE empresa_id = :empresa AND fecha < :antes
             GROUP BY almacen_id
            """)
        .param("empresa", empresa)
        .param("antes", antesDe)
        .query(ValorInicial.class)
        .list();
  }
}

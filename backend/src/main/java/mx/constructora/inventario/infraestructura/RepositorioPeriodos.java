package mx.constructora.inventario.infraestructura;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Meses cerrados y la foto del inventario al cierre. */
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
  public record ValorCierre(long almacenId, java.math.BigDecimal valor, int materiales) {}

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
}

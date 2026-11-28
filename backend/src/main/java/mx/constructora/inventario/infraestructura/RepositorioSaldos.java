package mx.constructora.inventario.infraestructura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Saldo, kardex y cargos a obra. El saldo se bloquea con {@code SELECT … FOR UPDATE} siempre en el
 * mismo orden (almacén, material) para que dos documentos simultáneos no se esperen en cruz.
 */
@Repository
public class RepositorioSaldos {

  /** Saldo de un material en un almacén. */
  public record FilaSaldo(
      long almacenId,
      long materialId,
      BigDecimal existencia,
      BigDecimal valor,
      BigDecimal costoPromedio,
      BigDecimal reservado,
      BigDecimal prestado,
      OffsetDateTime actualizado) {

    public BigDecimal disponible() {
      return existencia.subtract(reservado).subtract(prestado);
    }
  }

  /** Renglón de kardex con el folio y tipo de su documento. */
  public record FilaKardex(
      long id,
      long almacenId,
      long materialId,
      long movimientoId,
      String folio,
      String tipo,
      LocalDate fecha,
      BigDecimal cantidad,
      BigDecimal costoUnitario,
      BigDecimal importe,
      BigDecimal existenciaDespues,
      BigDecimal valorDespues,
      BigDecimal costoPromedioDespues,
      OffsetDateTime registrado) {}

  /** Un renglón nuevo de kardex. */
  public record NuevoKardex(
      long almacenId,
      long materialId,
      long movimientoId,
      long lineaId,
      LocalDate fecha,
      BigDecimal cantidad,
      BigDecimal costoUnitario,
      BigDecimal importe,
      BigDecimal existenciaDespues,
      BigDecimal valorDespues,
      BigDecimal costoPromedioDespues) {}

  /** Un cargo nuevo a obra. */
  public record NuevoCargo(
      long movimientoId,
      long lineaId,
      long lineaBaseId,
      LocalDate fecha,
      long materialId,
      long contratoId,
      long unidadObraId,
      long paqueteId,
      int clasificacionId,
      BigDecimal cantidad,
      BigDecimal importe) {}

  private static final String SALDO =
      """
      SELECT almacen_id, material_id, existencia, valor, costo_promedio, reservado, prestado,
             actualizado
        FROM inventario.saldo
       WHERE empresa_id = :empresa
      """;

  private final JdbcClient jdbc;

  RepositorioSaldos(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Bloquea el saldo hasta el fin de la transacción; si no existía, lo crea en cero. Quien llama
   * debe pedir los saldos en orden de (almacén, material).
   */
  public FilaSaldo bloquear(long empresa, long almacen, long material) {
    jdbc.sql(
            """
            INSERT INTO inventario.saldo (empresa_id, almacen_id, material_id)
            VALUES (:empresa, :almacen, :material)
            ON CONFLICT DO NOTHING
            """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .param("material", material)
        .update();
    return jdbc.sql(SALDO + " AND almacen_id = :almacen AND material_id = :material FOR UPDATE")
        .param("empresa", empresa)
        .param("almacen", almacen)
        .param("material", material)
        .query(FilaSaldo.class)
        .single();
  }

  public void guardar(
      long empresa,
      long almacen,
      long material,
      BigDecimal existencia,
      BigDecimal valor,
      BigDecimal costoPromedio) {
    jdbc.sql(
            """
            UPDATE inventario.saldo
               SET existencia = :existencia, valor = :valor, costo_promedio = :promedio,
                   actualizado = now()
             WHERE empresa_id = :empresa AND almacen_id = :almacen AND material_id = :material
            """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .param("material", material)
        .param("existencia", existencia)
        .param("valor", valor)
        .param("promedio", costoPromedio)
        .update();
  }

  /** Suma {@code delta} (con signo) a lo prestado. La base impide pasar de la existencia. */
  public void cambiarPrestado(long empresa, long almacen, long material, BigDecimal delta) {
    jdbc.sql(
            """
            UPDATE inventario.saldo SET prestado = prestado + :delta, actualizado = now()
             WHERE empresa_id = :empresa AND almacen_id = :almacen AND material_id = :material
            """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .param("material", material)
        .param("delta", delta)
        .update();
  }

  public void registrarKardex(long empresa, NuevoKardex k) {
    jdbc.sql(
            """
            INSERT INTO inventario.kardex
              (empresa_id, almacen_id, material_id, movimiento_id, linea_id, fecha, cantidad,
               costo_unitario, importe, existencia_despues, valor_despues, costo_promedio_despues)
            VALUES (:empresa, :almacen, :material, :movimiento, :linea, :fecha, :cantidad, :costo,
                    :importe, :existencia, :valor, :promedio)
            """)
        .param("empresa", empresa)
        .param("almacen", k.almacenId())
        .param("material", k.materialId())
        .param("movimiento", k.movimientoId())
        .param("linea", k.lineaId())
        .param("fecha", k.fecha())
        .param("cantidad", k.cantidad())
        .param("costo", k.costoUnitario())
        .param("importe", k.importe())
        .param("existencia", k.existenciaDespues())
        .param("valor", k.valorDespues())
        .param("promedio", k.costoPromedioDespues())
        .update();
  }

  public void registrarCargo(long empresa, NuevoCargo c) {
    jdbc.sql(
            """
            INSERT INTO inventario.cargo
              (empresa_id, movimiento_id, linea_id, linea_base_id, fecha, material_id, contrato_id,
               unidad_obra_id, paquete_id, clasificacion_id, cantidad, importe)
            VALUES (:empresa, :movimiento, :linea, :base, :fecha, :material, :contrato, :unidad,
                    :paquete, :clasificacion, :cantidad, :importe)
            """)
        .param("empresa", empresa)
        .param("movimiento", c.movimientoId())
        .param("linea", c.lineaId())
        .param("base", c.lineaBaseId())
        .param("fecha", c.fecha())
        .param("material", c.materialId())
        .param("contrato", c.contratoId())
        .param("unidad", c.unidadObraId())
        .param("paquete", c.paqueteId())
        .param("clasificacion", c.clasificacionId())
        .param("cantidad", c.cantidad())
        .param("importe", c.importe())
        .update();
  }

  // ------------------------------------------------------------------------------ Consultas

  /** Saldos de un almacén; {@code materiales} nulo: todos. */
  public List<FilaSaldo> saldos(
      long empresa, long almacen, Collection<Long> materiales, boolean soloConExistencia) {
    return jdbc.sql(
            SALDO
                + """
                   AND almacen_id = :almacen
                   AND (:todos OR material_id IN (:materiales))
                   AND (NOT :soloConExistencia OR existencia > 0)
                 ORDER BY material_id
                """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .param("todos", materiales == null)
        .param(
            "materiales",
            materiales == null || materiales.isEmpty() ? List.of(-1L) : List.copyOf(materiales))
        .param("soloConExistencia", soloConExistencia)
        .query(FilaSaldo.class)
        .list();
  }

  /** Kardex de un material, del más reciente al más antiguo. */
  public List<FilaKardex> kardex(
      long empresa, long material, Long almacen, LocalDate desde, LocalDate hasta, int limite) {
    return jdbc.sql(
            """
            SELECT k.id, k.almacen_id, k.material_id, k.movimiento_id, m.folio, m.tipo, k.fecha,
                   k.cantidad, k.costo_unitario, k.importe, k.existencia_despues, k.valor_despues,
                   k.costo_promedio_despues, k.registrado
              FROM inventario.kardex k
              JOIN inventario.movimiento m ON m.empresa_id = k.empresa_id AND m.id = k.movimiento_id
             WHERE k.empresa_id = :empresa AND k.material_id = :material
               AND (CAST(:almacen AS bigint) IS NULL OR k.almacen_id = :almacen)
               AND (CAST(:desde AS date) IS NULL OR k.fecha >= :desde)
               AND (CAST(:hasta AS date) IS NULL OR k.fecha <= :hasta)
             ORDER BY k.id DESC
             LIMIT :limite
            """)
        .param("empresa", empresa)
        .param("material", material)
        .param("almacen", almacen)
        .param("desde", desde)
        .param("hasta", hasta)
        .param("limite", limite)
        .query(FilaKardex.class)
        .list();
  }

  /** Suma con signo de las cantidades del kardex de un material en un almacén. */
  public BigDecimal sumaKardex(long empresa, long almacen, long material) {
    return jdbc.sql(
            """
            SELECT coalesce(sum(cantidad), 0) FROM inventario.kardex
             WHERE empresa_id = :empresa AND almacen_id = :almacen AND material_id = :material
            """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .param("material", material)
        .query(BigDecimal.class)
        .single();
  }

  /** Un cargo a obra con los datos de su documento, para escribirlo como renglón de Salidas. */
  public record CargoDeDocumento(
      long id,
      LocalDate fecha,
      String folio,
      String tipo,
      Long solicitanteId,
      long materialId,
      BigDecimal cantidad,
      long contratoId,
      long unidadObraId,
      long paqueteId,
      int clasificacionId) {}

  /**
   * Los cargos a obra (vales, devoluciones y reasignaciones; los ajustes de costo no mueven
   * cantidad) de los documentos del almacén entre dos fechas, en el orden en que se aplicaron.
   */
  public List<CargoDeDocumento> cargosDeAlmacen(
      long empresa, long almacen, LocalDate desde, LocalDate hasta) {
    return jdbc.sql(
            """
            SELECT c.id, c.fecha, m.folio, m.tipo, m.solicitante_id, c.material_id, c.cantidad,
                   c.contrato_id, c.unidad_obra_id, c.paquete_id, c.clasificacion_id
              FROM inventario.cargo c
              JOIN inventario.movimiento m ON m.empresa_id = c.empresa_id AND m.id = c.movimiento_id
             WHERE c.empresa_id = :empresa AND m.almacen_id = :almacen AND c.cantidad <> 0
               AND c.fecha BETWEEN :desde AND :hasta
             ORDER BY c.fecha, c.id
            """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .param("desde", desde)
        .param("hasta", hasta)
        .query(CargoDeDocumento.class)
        .list();
  }
}

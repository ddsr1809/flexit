package mx.constructora.inventario.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Diferencia de factura sobre una recepción ya aplicada (decisión de H4): la parte que sigue en el
 * almacén ajusta el valor y con él el promedio; la parte que ya salió se carga como ajuste a los
 * mismos lotes y paquetes, repartida entre los vales posteriores a la recepción en proporción a lo
 * que cada uno conserva. El costo congelado de esos vales no cambia (regla 3): el ajuste es un
 * cargo nuevo.
 *
 * <pre>
 *   diferencia  = (costo nuevo − costo anterior) × cantidad recibida
 *   en almacén  = diferencia × min(existencia, cantidad recibida) ÷ cantidad recibida
 *   consumida   = diferencia − en almacén   → prorrateada entre los vales
 * </pre>
 */
public final class AjusteCosto {

  /** Lo que conserva un vale posterior a la recepción (salido menos devuelto y reasignado). */
  public record Consumo(long lineaBaseId, BigDecimal cantidad) {}

  /** Cómo se reparte la diferencia: al valor del almacén y a cada consumo, en orden. */
  public record Resultado(
      BigDecimal diferencia, BigDecimal enAlmacen, List<BigDecimal> porConsumo) {}

  /** No hay a dónde llevar la diferencia: ni existencia ni vales posteriores. */
  public static class SinDestino extends RuntimeException {
    public SinDestino() {
      super("El material ya no está en el almacén ni en vales posteriores a la recepción");
    }
  }

  private AjusteCosto() {}

  /**
   * @param cantidadRecibida cantidad de la línea de recepción, en unidad de control
   * @param consumos solo los que conservan cantidad positiva
   */
  public static Resultado calcular(
      BigDecimal cantidadRecibida,
      BigDecimal costoAnterior,
      BigDecimal costoNuevo,
      BigDecimal existencia,
      BigDecimal valorActual,
      List<Consumo> consumos) {
    BigDecimal diferencia =
        costoNuevo
            .subtract(costoAnterior)
            .multiply(cantidadRecibida)
            .setScale(Valuacion.DECIMALES_IMPORTE, RoundingMode.HALF_EVEN);
    BigDecimal enAlmacen =
        diferencia
            .multiply(existencia.min(cantidadRecibida))
            .divide(cantidadRecibida, Valuacion.DECIMALES_IMPORTE, RoundingMode.HALF_EVEN);
    BigDecimal consumida = diferencia.subtract(enAlmacen);

    // A la baja no se puede quitar más valor del que hay: lo que sobra va a los vales.
    if (enAlmacen.add(valorActual).signum() < 0) {
      consumida = consumida.add(enAlmacen.add(valorActual));
      enAlmacen = valorActual.negate();
    }
    if (consumida.signum() != 0 && consumos.isEmpty()) {
      // Salió por traspaso, merma o faltante: no hay lote al cual cargarlo; se queda en el valor.
      if (existencia.signum() == 0 || enAlmacen.add(consumida).add(valorActual).signum() < 0) {
        throw new SinDestino();
      }
      enAlmacen = enAlmacen.add(consumida);
      consumida = BigDecimal.ZERO;
    }
    List<BigDecimal> porConsumo =
        consumos.isEmpty()
            ? List.of()
            : Reparto.prorratear(
                consumida,
                consumos.stream().map(Consumo::cantidad).toList(),
                Valuacion.DECIMALES_IMPORTE);
    return new Resultado(diferencia, enAlmacen.setScale(Valuacion.DECIMALES_IMPORTE), porConsumo);
  }
}

package mx.constructora.inventario.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Costo de cada renglón de una recepción, en la unidad de control y sin IVA:
 *
 * <pre>
 *   costo = (precio × cantidad capturada − descuento + flete del renglón) ÷ cantidad en unidad de control
 * </pre>
 *
 * El flete de la factura se prorratea por el importe de cada renglón. El IVA no entra al costo (se
 * recupera); se guarda en la línea solo como dato de la factura.
 */
public final class CostoRecepcion {

  /** Un renglón de la factura. */
  public record Renglon(
      BigDecimal cantidadControl,
      BigDecimal cantidadCaptura,
      BigDecimal precioCaptura,
      BigDecimal descuento) {

    /** Importe del renglón antes del flete: precio × cantidad − descuento. */
    public BigDecimal importe() {
      BigDecimal bruto = precioCaptura.multiply(cantidadCaptura);
      return bruto
          .subtract(descuento == null ? BigDecimal.ZERO : descuento)
          .setScale(Valuacion.DECIMALES_IMPORTE, RoundingMode.HALF_EVEN);
    }
  }

  /** Costo unitario e importe con flete de un renglón. */
  public record Costo(BigDecimal costoUnitario, BigDecimal importe) {}

  private CostoRecepcion() {}

  /**
   * @throws IllegalArgumentException si un descuento deja un renglón en negativo
   */
  public static List<Costo> calcular(List<Renglon> renglones, BigDecimal flete) {
    List<BigDecimal> importes = new ArrayList<>();
    for (Renglon renglon : renglones) {
      BigDecimal importe = renglon.importe();
      if (importe.signum() < 0) {
        throw new IllegalArgumentException("El descuento es mayor que el importe del renglón");
      }
      importes.add(importe);
    }
    BigDecimal totalFlete = flete == null ? BigDecimal.ZERO : flete;
    List<BigDecimal> fletes =
        totalFlete.signum() == 0
            ? importes.stream().map(i -> BigDecimal.ZERO).toList()
            : Reparto.prorratear(totalFlete, importes, Valuacion.DECIMALES_IMPORTE);
    List<Costo> costos = new ArrayList<>();
    for (int i = 0; i < renglones.size(); i++) {
      BigDecimal importe = importes.get(i).add(fletes.get(i));
      BigDecimal costo =
          importe.divide(
              renglones.get(i).cantidadControl(),
              Valuacion.DECIMALES_COSTO,
              RoundingMode.HALF_EVEN);
      costos.add(
          new Costo(costo, importe.setScale(Valuacion.DECIMALES_IMPORTE, RoundingMode.HALF_EVEN)));
    }
    return costos;
  }
}

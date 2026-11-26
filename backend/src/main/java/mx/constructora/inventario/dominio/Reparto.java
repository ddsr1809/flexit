package mx.constructora.inventario.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Repartos que tienen que sumar exacto: un bote para 18 lotes, el flete de una factura entre sus
 * renglones, la diferencia de una factura entre los vales que ya consumieron el material. Las
 * partes se redondean hacia abajo y el residuo va a la última.
 */
public final class Reparto {

  private Reparto() {}

  /** {@code total} en {@code partes} iguales; la última se lleva el residuo. */
  public static List<BigDecimal> repartir(BigDecimal total, int partes, int decimales) {
    if (partes < 1) {
      throw new IllegalArgumentException("Se reparte entre al menos una parte");
    }
    BigDecimal base = total.divide(BigDecimal.valueOf(partes), decimales, RoundingMode.DOWN);
    List<BigDecimal> lista = new ArrayList<>(Collections.nCopies(partes, base));
    lista.set(
        partes - 1,
        total
            .subtract(base.multiply(BigDecimal.valueOf(partes - 1)))
            .setScale(decimales, RoundingMode.UNNECESSARY));
    return lista;
  }

  /**
   * {@code total} en proporción a {@code pesos}; la última parte se lleva el residuo. Con todos los
   * pesos en cero se reparte en partes iguales.
   */
  public static List<BigDecimal> prorratear(
      BigDecimal total, List<BigDecimal> pesos, int decimales) {
    if (pesos.isEmpty()) {
      throw new IllegalArgumentException("Se prorratea entre al menos una parte");
    }
    BigDecimal suma = pesos.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    if (suma.signum() == 0) {
      return repartir(total, pesos.size(), decimales);
    }
    if (pesos.stream().anyMatch(p -> p.signum() < 0)) {
      throw new IllegalArgumentException("Los pesos no pueden ser negativos");
    }
    List<BigDecimal> partes = new ArrayList<>(pesos.size());
    BigDecimal acumulado = BigDecimal.ZERO;
    for (int i = 0; i < pesos.size() - 1; i++) {
      BigDecimal parte = total.multiply(pesos.get(i)).divide(suma, decimales, RoundingMode.DOWN);
      partes.add(parte);
      acumulado = acumulado.add(parte);
    }
    partes.add(total.subtract(acumulado).setScale(decimales, RoundingMode.UNNECESSARY));
    return partes;
  }
}

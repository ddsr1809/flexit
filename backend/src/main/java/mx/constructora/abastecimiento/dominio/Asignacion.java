package mx.constructora.abastecimiento.dominio;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Reparte una cantidad entre renglones en el orden dado, llenando cada uno hasta lo que admite
 * antes de pasar al siguiente: lo pedido al proveedor se asigna a las requisiciones más urgentes, y
 * lo recibido llena primero las que se pidieron primero. Lo que sobra no se asigna a nadie.
 */
public final class Asignacion {

  private Asignacion() {}

  /**
   * @param total lo que se reparte (cero o más)
   * @param capacidades cuánto admite cada renglón, en orden (los negativos cuentan como cero)
   * @return lo asignado a cada renglón, en el mismo orden; suma como máximo {@code total}
   */
  public static List<BigDecimal> enOrden(BigDecimal total, List<BigDecimal> capacidades) {
    if (total.signum() < 0) {
      throw new IllegalArgumentException("No se reparte una cantidad negativa");
    }
    List<BigDecimal> asignado = new ArrayList<>(capacidades.size());
    BigDecimal resta = total;
    for (BigDecimal capacidad : capacidades) {
      BigDecimal parte = resta.min(capacidad.max(BigDecimal.ZERO));
      asignado.add(parte);
      resta = resta.subtract(parte);
    }
    return asignado;
  }
}

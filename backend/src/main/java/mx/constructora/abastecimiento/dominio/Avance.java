package mx.constructora.abastecimiento.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Cómo va un renglón de requisición, en unidad de control.
 *
 * <ul>
 *   <li>{@code surtido}: lo que salió en vales aplicados, neto de devoluciones.
 *   <li>{@code enVale}: lo que va en vales que todavía no se aplican.
 *   <li>{@code reservado}: lo apartado en el almacén para este renglón. Un vale abierto se surte
 *       primero de aquí, así que lo cubierto es el mayor de los dos, no la suma.
 *   <li>{@code porRecibir}: lo pedido al proveedor para este renglón que no ha llegado.
 * </ul>
 *
 * <p>Lo que falta comprar es lo pendiente que no está cubierto ni pedido.
 */
public record Avance(
    BigDecimal cantidad,
    BigDecimal surtido,
    BigDecimal enVale,
    BigDecimal reservado,
    BigDecimal porRecibir) {

  public Avance {
    surtido = cero(surtido);
    enVale = cero(enVale);
    reservado = cero(reservado);
    porRecibir = cero(porRecibir);
  }

  /** Lo que falta entregar en obra. */
  public BigDecimal pendiente() {
    return cantidad.subtract(surtido).max(BigDecimal.ZERO);
  }

  /** Lo que el almacén ya tiene para este renglón (apartado o en un vale abierto). */
  public BigDecimal cubierto() {
    return reservado.max(enVale).min(pendiente());
  }

  /** Lo que todavía puede ir en un vale nuevo. */
  public BigDecimal porSurtir() {
    return pendiente().subtract(enVale).max(BigDecimal.ZERO);
  }

  /** Lo que falta comprar: pendiente, sin cubrir y sin pedir. */
  public BigDecimal faltante() {
    return pendiente().subtract(cubierto()).subtract(porRecibir).max(BigDecimal.ZERO);
  }

  public boolean completo() {
    return surtido.compareTo(cantidad) >= 0;
  }

  /** Porcentaje surtido de varios renglones (0 a 100, dos decimales); sin renglones, 0. */
  public static BigDecimal porcentaje(Collection<Avance> avances) {
    BigDecimal pedido = BigDecimal.ZERO;
    BigDecimal entregado = BigDecimal.ZERO;
    for (Avance a : avances) {
      pedido = pedido.add(a.cantidad());
      entregado = entregado.add(a.surtido().min(a.cantidad()).max(BigDecimal.ZERO));
    }
    if (pedido.signum() == 0) {
      return BigDecimal.ZERO.setScale(2);
    }
    return entregado.multiply(BigDecimal.valueOf(100)).divide(pedido, 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal cero(BigDecimal valor) {
    return valor == null ? BigDecimal.ZERO : valor;
  }
}

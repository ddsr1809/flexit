package mx.constructora.inventario.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Existencia y valor de un material en un almacén, con costo promedio ponderado. Inmutable: cada
 * operación devuelve un {@link Paso} con lo que va al kardex y la valuación que queda.
 *
 * <p>Se guarda el valor y no solo el promedio para que no se pierdan centavos: el promedio es
 * {@code valor ÷ existencia} y una salida que se lleva toda la existencia se lleva todo el valor.
 * Con existencia en cero, {@code costoPromedio} conserva el último costo (para valuar un sobrante
 * de conteo o una devolución sin origen).
 *
 * <p>Escalas: cantidad 4 decimales, costo unitario 6, importe 4 (como en la base).
 */
public record Valuacion(BigDecimal existencia, BigDecimal valor, BigDecimal costoPromedio) {

  public static final int DECIMALES_CANTIDAD = 4;
  public static final int DECIMALES_COSTO = 6;
  public static final int DECIMALES_IMPORTE = 4;

  public static final Valuacion VACIA =
      new Valuacion(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

  /** Lo que una operación deja en el kardex: cantidad e importe con signo. */
  public record Paso(
      BigDecimal cantidad, BigDecimal costoUnitario, BigDecimal importe, Valuacion despues) {}

  public Valuacion {
    existencia = existencia.setScale(DECIMALES_CANTIDAD, RoundingMode.UNNECESSARY);
    valor = valor.setScale(DECIMALES_IMPORTE, RoundingMode.HALF_EVEN);
    costoPromedio = costoPromedio.setScale(DECIMALES_COSTO, RoundingMode.HALF_EVEN);
    if (existencia.signum() < 0 || valor.signum() < 0 || costoPromedio.signum() < 0) {
      throw new IllegalArgumentException("Valuación negativa: " + existencia + " / " + valor);
    }
    if (existencia.signum() == 0 && valor.signum() != 0) {
      throw new IllegalArgumentException("Valor sin existencia: " + valor);
    }
  }

  /** Entrada al costo {@code costoUnitario} (recepción, devolución de obra, sobrante). */
  public Paso entrar(BigDecimal cantidad, BigDecimal costoUnitario) {
    exigirPositiva(cantidad);
    BigDecimal costo = costoUnitario.setScale(DECIMALES_COSTO, RoundingMode.HALF_EVEN);
    return entrarConImporte(cantidad, importe(cantidad, costo), costo);
  }

  /**
   * Entrada con el importe ya calculado: el traspaso mete en el destino exactamente el valor que
   * sacó del origen.
   */
  public Paso entrarConImporte(BigDecimal cantidad, BigDecimal importe, BigDecimal costoUnitario) {
    exigirPositiva(cantidad);
    if (importe.signum() < 0) {
      throw new IllegalArgumentException("Importe negativo: " + importe);
    }
    BigDecimal existenciaNueva = existencia.add(cantidad);
    BigDecimal valorNuevo = valor.add(importe);
    Valuacion despues =
        new Valuacion(existenciaNueva, valorNuevo, promedio(valorNuevo, existenciaNueva));
    return new Paso(
        cantidad.setScale(DECIMALES_CANTIDAD, RoundingMode.UNNECESSARY),
        costoUnitario.setScale(DECIMALES_COSTO, RoundingMode.HALF_EVEN),
        importe.setScale(DECIMALES_IMPORTE, RoundingMode.HALF_EVEN),
        despues);
  }

  /**
   * Salida al costo promedio (vale, merma, faltante, traspaso). El costo queda congelado en el
   * paso; si sale toda la existencia, sale todo el valor.
   *
   * @throws ExistenciaInsuficiente si no alcanza
   */
  public Paso salir(BigDecimal cantidad) {
    return salirACosto(cantidad, costoPromedio);
  }

  /**
   * Salida a un costo distinto del promedio (devolución al proveedor, al costo con que entró). El
   * importe nunca pasa del valor que hay, y el promedio de lo que queda se recalcula.
   *
   * @throws ExistenciaInsuficiente si no alcanza
   */
  public Paso salirACosto(BigDecimal cantidad, BigDecimal costoUnitario) {
    exigirPositiva(cantidad);
    if (cantidad.compareTo(existencia) > 0) {
      throw new ExistenciaInsuficiente(existencia, cantidad);
    }
    BigDecimal costo = costoUnitario.setScale(DECIMALES_COSTO, RoundingMode.HALF_EVEN);
    BigDecimal existenciaNueva = existencia.subtract(cantidad);
    BigDecimal importe =
        existenciaNueva.signum() == 0 ? valor : importe(cantidad, costo).min(valor);
    BigDecimal valorNuevo = valor.subtract(importe);
    // Al promedio, el promedio de lo que queda no cambia; a otro costo, se recalcula.
    BigDecimal promedioNuevo =
        existenciaNueva.signum() == 0 || costo.compareTo(costoPromedio) == 0
            ? costoPromedio
            : promedio(valorNuevo, existenciaNueva);
    return new Paso(
        cantidad.setScale(DECIMALES_CANTIDAD, RoundingMode.UNNECESSARY).negate(),
        costo,
        importe.negate(),
        new Valuacion(existenciaNueva, valorNuevo, promedioNuevo));
  }

  /**
   * Cambia el valor sin mover existencia (ajuste de costo de lo que sigue en almacén). Si la
   * corrección es a la baja y pasa del valor que hay, se ajusta solo hasta cero: el resto lo
   * devuelve {@link #excedente} para cargarlo a otro lado.
   */
  public Paso ajustarValor(BigDecimal diferencia) {
    if (existencia.signum() == 0) {
      throw new IllegalStateException("Sin existencia no hay valor que ajustar");
    }
    BigDecimal aplicable =
        diferencia.max(valor.negate()).setScale(DECIMALES_IMPORTE, RoundingMode.HALF_EVEN);
    BigDecimal valorNuevo = valor.add(aplicable);
    Valuacion despues = new Valuacion(existencia, valorNuevo, promedio(valorNuevo, existencia));
    return new Paso(
        BigDecimal.ZERO.setScale(DECIMALES_CANTIDAD), despues.costoPromedio(), aplicable, despues);
  }

  /** Lo que de {@code diferencia} no cupo en {@link #ajustarValor}. */
  public BigDecimal excedente(BigDecimal diferencia) {
    BigDecimal aplicable = diferencia.max(valor.negate());
    return diferencia.subtract(aplicable).setScale(DECIMALES_IMPORTE, RoundingMode.HALF_EVEN);
  }

  /** {@code cantidad × costo} a 4 decimales. */
  public static BigDecimal importe(BigDecimal cantidad, BigDecimal costo) {
    return cantidad.multiply(costo).setScale(DECIMALES_IMPORTE, RoundingMode.HALF_EVEN);
  }

  private static BigDecimal promedio(BigDecimal valor, BigDecimal existencia) {
    return valor.divide(existencia, DECIMALES_COSTO, RoundingMode.HALF_EVEN);
  }

  private static void exigirPositiva(BigDecimal cantidad) {
    if (cantidad.signum() <= 0) {
      throw new IllegalArgumentException("La cantidad debe ser mayor que cero: " + cantidad);
    }
    if (cantidad.stripTrailingZeros().scale() > DECIMALES_CANTIDAD) {
      throw new IllegalArgumentException("Más de 4 decimales: " + cantidad);
    }
  }
}

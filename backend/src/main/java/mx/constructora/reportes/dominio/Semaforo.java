package mx.constructora.reportes.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Semáforo del presupuesto de material de un lote: verde abajo del 80%, amarillo desde el 80% y
 * rojo desde el 100%. Solo lo cargado como Programa consume el presupuesto; garantías, retrabajos y
 * demás se ven aparte. Avisa, no bloquea.
 */
public enum Semaforo {
  SIN_PRESUPUESTO("sin"),
  VERDE("verde"),
  AMARILLO("amarillo"),
  ROJO("rojo");

  /** Porcentaje desde el que se avisa. */
  public static final BigDecimal AVISO = new BigDecimal("80");

  /** Porcentaje desde el que el lote rebasa su presupuesto. */
  public static final BigDecimal LIMITE = new BigDecimal("100");

  private final String clave;

  Semaforo(String clave) {
    this.clave = clave;
  }

  /** {@code sin}, {@code verde}, {@code amarillo} o {@code rojo}. */
  public String clave() {
    return clave;
  }

  /** Porcentaje consumido (dos decimales), o {@code null} si no hay presupuesto o es cero. */
  public static BigDecimal porcentaje(BigDecimal consumido, BigDecimal presupuesto) {
    if (presupuesto == null || presupuesto.signum() <= 0) {
      return null;
    }
    BigDecimal usado = consumido == null ? BigDecimal.ZERO : consumido;
    return usado.multiply(BigDecimal.valueOf(100)).divide(presupuesto, 2, RoundingMode.HALF_UP);
  }

  public static Semaforo de(BigDecimal consumido, BigDecimal presupuesto) {
    if (presupuesto == null) {
      return SIN_PRESUPUESTO;
    }
    BigDecimal usado = consumido == null ? BigDecimal.ZERO : consumido;
    if (presupuesto.signum() <= 0) {
      return usado.signum() > 0 ? ROJO : VERDE;
    }
    BigDecimal p =
        usado.multiply(BigDecimal.valueOf(100)).divide(presupuesto, 6, RoundingMode.HALF_UP);
    if (p.compareTo(LIMITE) >= 0) {
      return ROJO;
    }
    return p.compareTo(AVISO) >= 0 ? AMARILLO : VERDE;
  }
}

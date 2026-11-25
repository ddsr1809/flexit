package mx.constructora.catalogo;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * Factor para pasar una cantidad capturada a la unidad de control del material. Lógica pura, sin
 * base: se prueba sola y la usan {@link CatalogoApi} y, en H4, el vale y la recepción.
 *
 * <p>Orden de búsqueda: la misma unidad (factor 1); la conversión del material; una conversión
 * general en el sentido directo; una general en sentido inverso (1 / factor).
 */
public final class Conversiones {

  /** Decimales de una cantidad en unidad de control (numeric(14,4) en la base). */
  public static final int DECIMALES_CANTIDAD = 4;

  /** Una conversión: 1 {@code origen} = {@code factor} {@code destino}. */
  public record Regla(Long materialId, long origenId, long destinoId, BigDecimal factor) {}

  private Conversiones() {}

  /** Factor tal que {@code cantidad × factor} queda en la unidad de control. */
  public static Optional<BigDecimal> factor(
      Collection<Regla> reglas, long materialId, long unidadCaptura, long unidadControl) {
    if (unidadCaptura == unidadControl) {
      return Optional.of(BigDecimal.ONE);
    }
    Optional<BigDecimal> delMaterial =
        reglas.stream()
            .filter(r -> Objects.equals(r.materialId(), materialId))
            .filter(r -> r.origenId() == unidadCaptura && r.destinoId() == unidadControl)
            .map(Regla::factor)
            .findFirst();
    if (delMaterial.isPresent()) {
      return delMaterial;
    }
    Optional<BigDecimal> directa =
        reglas.stream()
            .filter(r -> r.materialId() == null)
            .filter(r -> r.origenId() == unidadCaptura && r.destinoId() == unidadControl)
            .map(Regla::factor)
            .findFirst();
    if (directa.isPresent()) {
      return directa;
    }
    return reglas.stream()
        .filter(r -> r.materialId() == null)
        .filter(r -> r.origenId() == unidadControl && r.destinoId() == unidadCaptura)
        .map(r -> BigDecimal.ONE.divide(r.factor(), MathContext.DECIMAL64))
        .findFirst();
  }

  /** {@code cantidad × factor} redondeado a 4 decimales (mitad hacia arriba). */
  public static BigDecimal convertir(BigDecimal cantidad, BigDecimal factor) {
    return cantidad.multiply(factor).setScale(DECIMALES_CANTIDAD, RoundingMode.HALF_UP);
  }
}

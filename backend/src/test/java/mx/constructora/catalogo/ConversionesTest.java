package mx.constructora.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import mx.constructora.catalogo.Conversiones.Regla;
import org.junit.jupiter.api.Test;

/** De la unidad capturada a la unidad de control, sin base de datos. */
class ConversionesTest {

  private static final long PZA = 1;
  private static final long CJA = 2;
  private static final long KG = 10;
  private static final long G = 11;
  private static final long LAVAMANOS = 7;
  private static final long OTRO = 8;

  private static final List<Regla> REGLAS =
      List.of(
          new Regla(null, KG, G, new BigDecimal("1000")),
          new Regla(LAVAMANOS, CJA, PZA, new BigDecimal("4")));

  @Test
  void laMismaUnidadNoSeConvierte() {
    assertThat(Conversiones.factor(REGLAS, LAVAMANOS, PZA, PZA)).contains(BigDecimal.ONE);
  }

  @Test
  void laConversionDelMaterialSoloAplicaAEseMaterial() {
    assertThat(Conversiones.factor(REGLAS, LAVAMANOS, CJA, PZA)).contains(new BigDecimal("4"));
    assertThat(Conversiones.factor(REGLAS, OTRO, CJA, PZA)).isEmpty();
  }

  @Test
  void lasGeneralesSirvenEnLosDosSentidos() {
    assertThat(Conversiones.factor(REGLAS, OTRO, KG, G)).contains(new BigDecimal("1000"));
    BigDecimal inversa = Conversiones.factor(REGLAS, OTRO, G, KG).orElseThrow();
    assertThat(Conversiones.convertir(new BigDecimal("250"), inversa))
        .isEqualByComparingTo("0.2500");
  }

  @Test
  void elResultadoQuedaACuatroDecimales() {
    assertThat(Conversiones.convertir(new BigDecimal("3"), new BigDecimal("0.33333333")))
        .isEqualTo(new BigDecimal("1.0000"));
  }
}

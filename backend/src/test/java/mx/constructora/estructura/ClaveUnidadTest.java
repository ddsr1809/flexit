package mx.constructora.estructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Las formas en que los Excel escriben un lote se leen como una sola clave. */
class ClaveUnidadTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Mza 16 / Lote 34",
        "M16L34",
        "M16-L34",
        "mz. 016 lt 034",
        "Manzana 16 Lote 34",
        "mza 16, lote 34",
        "M 16 - L 34"
      })
  void lasFormasDelAlmacenYDelDestajoDanLaMismaClave(String texto) {
    assertThat(ClaveUnidad.leer(texto)).map(ClaveUnidad::clave).contains("M16-L34");
  }

  @Test
  void aceptaLetraEnManzanaYLote() {
    assertThat(ClaveUnidad.leer("Manzana 5a lote 3B")).map(ClaveUnidad::clave).contains("M5A-L3B");
  }

  @ParameterizedTest
  @ValueSource(strings = {"Lote 34", "M16", "16-34", "", "Mza dieciséis lote 3"})
  void loQueNoEsUnLoteNoSeAdivina(String texto) {
    assertThat(ClaveUnidad.leer(texto)).isEmpty();
  }

  @Test
  void expandeRangosYListasSinRepetir() {
    assertThat(ClaveUnidad.expandirLotes("29-31, 35, 40A"))
        .containsExactly("29", "30", "31", "35", "40A");
    assertThat(ClaveUnidad.expandirLotes("29 - 31 35;036 30"))
        .containsExactly("29", "30", "31", "35", "36");
  }

  @Test
  void rechazaRangosAlRevesYDemasiadosLotes() {
    assertThatThrownBy(() -> ClaveUnidad.expandirLotes("31-29"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("al revés");
    assertThatThrownBy(() -> ClaveUnidad.expandirLotes("1-600"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ClaveUnidad.expandirLotes("abc"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void laClaveLibreQuedaEnMayusculasConGuiones() {
    assertThat(ClaveUnidad.claveLibre(" Caseta de acceso ")).isEqualTo("CASETA-DE-ACCESO");
    assertThat(ClaveUnidad.claveLibre("Área común #2")).isEqualTo("AREA-COMUN-2");
  }

  @Test
  void elCodigoDeContratoSeNormalizaConGuiones() {
    assertThat(CodigoContrato.normalizar(" 60-kbu-mek/C001/2026 "))
        .contains("60-KBU-MEK-C001-2026");
    assertThat(CodigoContrato.normalizar("60-KZ7-MF6-C002-2026")).contains("60-KZ7-MF6-C002-2026");
    assertThat(CodigoContrato.normalizar("$$")).isEmpty();
  }
}

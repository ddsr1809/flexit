package mx.constructora.importacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.constructora.importacion.dominio.Json;
import mx.constructora.importacion.dominio.Normal;
import mx.constructora.importacion.dominio.Similitud;
import mx.constructora.importacion.dominio.UnidadesExcel;
import org.junit.jupiter.api.Test;

/** Normalización, parecido de nombres, unidades del Excel y el JSON de las columnas jsonb. */
class TextoYJsonTest {

  @Test
  void normalizaIgualQueLaFuncionDeLaBase() {
    // Los mismos casos que tools/pruebas-sql/estructura-catalogo.sql
    assertThat(Normal.texto("  Cemento   gris ")).isEqualTo(Normal.texto("CEMENTO GRÍS"));
    assertThat(Normal.texto("Ñandú  Áéíóú")).isEqualTo("NANDU AEIOU");
    assertThat(Normal.texto("   ")).isNull();
    assertThat(Normal.texto("Bolsas\u00A0")).isEqualTo("BOLSAS");
    assertThat(Normal.palabras("M.20 / L-3")).isEqualTo("M 20 L 3");
    assertThat(Normal.clave("Quinta Granada", 12)).isEqualTo("QUINTAGRANAD");
  }

  @Test
  void elParecidoNoDependeDelOrdenNiDeUnaLetra() {
    assertThat(Similitud.parecido("ACOSTA GARCIA ANTONIO", "ANTONIO ACOSTA GARCIA"))
        .isEqualTo(1000);
    assertThat(
            Similitud.parecido("ALAPIZCO CORTEZ JESUS GREGORIO", "ALPIZCO CORTEZ JESUS GREGORIO"))
        .isGreaterThanOrEqualTo(Similitud.UMBRAL);
    assertThat(Similitud.parecido("BLADIMIR", "GARCIA CRUZ BLADIMIR LEON"))
        .isLessThan(Similitud.UMBRAL);
    assertThat(Similitud.contenido("BLADIMIR GARCIA", "GARCIA CRUZ BLADIMIR LEON")).isTrue();
    assertThat(Similitud.contenido("BLADIMIR", "GARCIA CRUZ BLADIMIR LEON"))
        .as("una sola palabra no basta")
        .isFalse();
    assertThat(Similitud.distancia("BLADMIR", "BLADIMIR")).isEqualTo(1);
    assertThat(Similitud.comoDecimal(850)).isEqualByComparingTo("0.850");
  }

  @Test
  void lasVeintidosFormasDeEscribirUnidades() {
    assertThat(UnidadesExcel.clave("Piezas")).contains("PZA");
    assertThat(UnidadesExcel.clave("pieza")).contains("PZA");
    assertThat(UnidadesExcel.clave("Bolsas ")).contains("BOL");
    assertThat(UnidadesExcel.clave("kilo")).contains("KG");
    assertThat(UnidadesExcel.clave("Gramos")).contains("G");
    assertThat(UnidadesExcel.clave("Cubetas")).contains("CUB");
    assertThat(UnidadesExcel.clave("Juego")).isEmpty();
  }

  @Test
  void jsonIdaYVueltaSinPerderDecimales() {
    Map<String, Object> mapa = new LinkedHashMap<>();
    mapa.put("texto", "Abrazadera omega de 3/4\" \\ ñ\n");
    mapa.put("monto", new BigDecimal("1072615.5299999984"));
    mapa.put("entero", 12);
    mapa.put("nulo", null);
    mapa.put("lista", List.of(true, "a", Map.of("x", 1)));
    String json = Json.escribir(mapa);
    assertThat(Json.leer(json)).isEqualTo(Json.leer(Json.escribir(Json.leer(json))));
    Map<String, Object> leido = Json.objeto(json);
    assertThat(leido.get("texto")).isEqualTo("Abrazadera omega de 3/4\" \\ ñ\n");
    assertThat((BigDecimal) leido.get("monto")).isEqualByComparingTo("1072615.5299999984");
    assertThat(leido).containsKey("nulo");
    assertThat(Json.textos("{\"a\":1.50,\"b\":\"x\"}")).containsEntry("a", "1.50");
    assertThat(Json.objeto(null)).isEmpty();
    assertThatThrownBy(() -> Json.leer("{\"a\":}")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Json.leer("[1] x")).isInstanceOf(IllegalArgumentException.class);
    assertThat(Json.leer("\"\\u00e1\"")).isEqualTo("á");
  }
}

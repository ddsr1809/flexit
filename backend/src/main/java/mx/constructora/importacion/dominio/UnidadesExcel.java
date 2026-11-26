package mx.constructora.importacion.dominio;

import java.util.Map;
import java.util.Optional;

/**
 * Las formas de escribir una unidad en el Excel de almacén ("Piezas", "pieza", "Bolsas ", "kilo") →
 * clave de la unidad del sistema (V005). Es la regla "22 formas de escribir 12 unidades" del plan.
 */
public final class UnidadesExcel {

  private static final Map<String, String> VARIANTES =
      Map.ofEntries(
          Map.entry("PIEZA", "PZA"),
          Map.entry("PIEZAS", "PZA"),
          Map.entry("PZA", "PZA"),
          Map.entry("PZAS", "PZA"),
          Map.entry("PZ", "PZA"),
          Map.entry("PZS", "PZA"),
          Map.entry("CAJA", "CJA"),
          Map.entry("CAJAS", "CJA"),
          Map.entry("CJA", "CJA"),
          Map.entry("PAQUETE", "PAQ"),
          Map.entry("PAQUETES", "PAQ"),
          Map.entry("PAQ", "PAQ"),
          Map.entry("ROLLO", "ROL"),
          Map.entry("ROLLOS", "ROL"),
          Map.entry("SACO", "SAC"),
          Map.entry("SACOS", "SAC"),
          Map.entry("BULTO", "SAC"),
          Map.entry("BULTOS", "SAC"),
          Map.entry("BOLSA", "BOL"),
          Map.entry("BOLSAS", "BOL"),
          Map.entry("CUBETA", "CUB"),
          Map.entry("CUBETAS", "CUB"),
          Map.entry("TAMBO", "TAM"),
          Map.entry("TAMBOS", "TAM"),
          Map.entry("TRAMO", "TRA"),
          Map.entry("TRAMOS", "TRA"),
          Map.entry("KILO", "KG"),
          Map.entry("KILOS", "KG"),
          Map.entry("KG", "KG"),
          Map.entry("KGS", "KG"),
          Map.entry("KILOGRAMO", "KG"),
          Map.entry("KILOGRAMOS", "KG"),
          Map.entry("GRAMO", "G"),
          Map.entry("GRAMOS", "G"),
          Map.entry("GR", "G"),
          Map.entry("LITRO", "L"),
          Map.entry("LITROS", "L"),
          Map.entry("LT", "L"),
          Map.entry("LTS", "L"),
          Map.entry("METRO", "M"),
          Map.entry("METROS", "M"),
          Map.entry("MT", "M"),
          Map.entry("ML", "M"),
          Map.entry("M2", "M2"),
          Map.entry("METRO CUADRADO", "M2"),
          Map.entry("METROS CUADRADOS", "M2"),
          Map.entry("M3", "M3"),
          Map.entry("METRO CUBICO", "M3"),
          Map.entry("METROS CUBICOS", "M3"));

  private UnidadesExcel() {}

  /** Clave de la unidad del sistema para el texto del Excel, si se conoce. */
  public static Optional<String> clave(String texto) {
    String normal = Normal.texto(texto);
    if (normal == null) {
      return Optional.empty();
    }
    String sinPunto = normal.endsWith(".") ? normal.substring(0, normal.length() - 1) : normal;
    return Optional.ofNullable(VARIANTES.get(sinPunto));
  }
}

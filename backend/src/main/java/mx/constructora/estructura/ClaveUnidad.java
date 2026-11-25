package mx.constructora.estructura;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clave única de un lote: {@code M16-L34}. El almacén escribe {@code Mza 16 / Lote 34} y el destajo
 * {@code M16L34}; las dos formas, y sus variantes con o sin espacios, puntos o ceros a la
 * izquierda, se leen igual. La usa también la migración desde Excel (H3).
 *
 * @param manzana número de manzana, con letra opcional ({@code 16}, {@code 16A})
 * @param lote número de lote, con letra opcional
 */
public record ClaveUnidad(String manzana, String lote) {

  /** Lotes que se pueden crear de una vez. */
  public static final int MAXIMO_LOTES = 500;

  private static final Pattern NUMERO = Pattern.compile("0*([0-9]{1,4})([A-Z]?)");

  private static final Pattern FORMATO =
      Pattern.compile(
          "^M(?:ANZANA|ZA|Z)?\\.?\\s*([0-9]{1,6}[A-Z]?)"
              + "\\s*[-/,]?\\s*"
              + "L(?:OTE|T)?\\.?\\s*([0-9]{1,6}[A-Z]?)$");

  private static final Pattern RANGO = Pattern.compile("^([0-9]{1,4})-([0-9]{1,4})$");

  public ClaveUnidad {
    manzana = numero(manzana);
    lote = numero(lote);
  }

  /** {@code M16-L34}. */
  public String clave() {
    return "M" + manzana + "-L" + lote;
  }

  /** Lee una clave de lote escrita de cualquiera de las formas conocidas. */
  public static Optional<ClaveUnidad> leer(String texto) {
    if (texto == null) {
      return Optional.empty();
    }
    Matcher coincidencia = FORMATO.matcher(sinAcentos(texto).trim());
    if (!coincidencia.matches()) {
      return Optional.empty();
    }
    try {
      return Optional.of(new ClaveUnidad(coincidencia.group(1), coincidencia.group(2)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /**
   * Número de manzana o lote sin ceros a la izquierda: {@code 016} → {@code 16}, {@code 5a} →
   * {@code 5A}.
   *
   * @throws IllegalArgumentException si no es un número de 1 a 4 cifras con letra opcional
   */
  public static String numero(String texto) {
    if (texto == null) {
      throw new IllegalArgumentException("Falta el número");
    }
    Matcher coincidencia = NUMERO.matcher(texto.trim().toUpperCase(Locale.ROOT));
    if (!coincidencia.matches()) {
      throw new IllegalArgumentException(
          "\"" + texto.trim() + "\" no es un número de manzana o lote");
    }
    return coincidencia.group(1).replaceFirst("^0+(?=[0-9])", "") + coincidencia.group(2);
  }

  /**
   * Expande una lista de lotes: {@code "29-31, 35, 40A"} → 29, 30, 31, 35, 40A. Sin repetidos, en
   * el orden escrito.
   *
   * @throws IllegalArgumentException si un elemento no se entiende, un rango va al revés o pasan de
   *     {@link #MAXIMO_LOTES}
   */
  public static List<String> expandirLotes(String expresion) {
    if (expresion == null || expresion.isBlank()) {
      throw new IllegalArgumentException("Escribe al menos un lote");
    }
    Set<String> lotes = new LinkedHashSet<>();
    // "29 - 31" se junta en "29-31" antes de separar por comas, punto y coma o espacios.
    for (String parte : expresion.trim().replaceAll("\\s*-\\s*", "-").split("[,;\\s]+")) {
      String elemento = parte.trim();
      if (elemento.isEmpty()) {
        continue;
      }
      Matcher rango = RANGO.matcher(elemento);
      if (rango.matches()) {
        int desde = Integer.parseInt(rango.group(1));
        int hasta = Integer.parseInt(rango.group(2));
        if (hasta < desde) {
          throw new IllegalArgumentException("El rango " + elemento + " va al revés");
        }
        if (hasta - desde + 1 > MAXIMO_LOTES) {
          throw new IllegalArgumentException("Son más de " + MAXIMO_LOTES + " lotes");
        }
        for (int n = desde; n <= hasta; n++) {
          lotes.add(Integer.toString(n));
        }
      } else {
        lotes.add(numero(elemento));
      }
      if (lotes.size() > MAXIMO_LOTES) {
        throw new IllegalArgumentException("Son más de " + MAXIMO_LOTES + " lotes");
      }
    }
    if (lotes.isEmpty()) {
      throw new IllegalArgumentException("Escribe al menos un lote");
    }
    return new ArrayList<>(lotes);
  }

  /**
   * Clave de una unidad que no es lote (edificio, tramo, área común): mayúsculas sin acentos y
   * guiones en lugar de espacios. {@code "Caseta de acceso"} → {@code CASETA-DE-ACCESO}.
   *
   * @throws IllegalArgumentException si queda vacía o pasa de 30 caracteres
   */
  public static String claveLibre(String texto) {
    String clave =
        texto == null
            ? ""
            : sinAcentos(texto).replaceAll("[^A-Z0-9]+", "-").replaceAll("^-+|-+$", "");
    if (clave.isEmpty()) {
      throw new IllegalArgumentException("Escribe la clave de la unidad");
    }
    if (clave.length() > 30) {
      throw new IllegalArgumentException("La clave de la unidad pasa de 30 caracteres");
    }
    return clave;
  }

  private static String sinAcentos(String texto) {
    return Normalizer.normalize(texto, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toUpperCase(Locale.ROOT);
  }
}

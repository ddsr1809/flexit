package mx.constructora.importacion.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Qué tanto se parecen dos nombres, de 0 a 1000 (milésimas). Combina coincidencia de palabras
 * (Jaccard, contando como iguales dos palabras con una o dos letras de diferencia: "BLADMIR" y
 * "BLADIMIR") y distancia de edición sobre las palabras ordenadas, para que "ACOSTA GARCIA ANTONIO"
 * y "ANTONIO ACOSTA GARCIA" sean el mismo nombre. Por arriba de {@link #UMBRAL} se sugiere; nunca
 * se acepta solo.
 *
 * <p>Todo en enteros: la regla 6 de CLAUDE.md no deja usar {@code double}.
 */
public final class Similitud {

  /** 0.85: a partir de aquí el sistema sugiere el registro parecido. */
  public static final int UMBRAL = 850;

  private Similitud() {}

  /** Parecido de dos textos ya normalizados (ver {@link Normal}), de 0 a 1000. */
  public static int parecido(String a, String b) {
    if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
      return 0;
    }
    if (a.equals(b)) {
      return 1000;
    }
    List<String> palabrasA = palabras(a);
    List<String> palabrasB = palabras(b);
    int porPalabras = jaccard(palabrasA, palabrasB);
    String ordenadaA = String.join(" ", ordenadas(palabrasA));
    String ordenadaB = String.join(" ", ordenadas(palabrasB));
    int porEdicion = proporcion(ordenadaA, ordenadaB);
    return Math.max(porPalabras, porEdicion);
  }

  /** El parecido como decimal de 0.000 a 1.000, para mostrarlo. */
  public static BigDecimal comoDecimal(int milesimas) {
    return BigDecimal.valueOf(milesimas).divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
  }

  /**
   * {@code true} si todas las palabras del nombre corto están en el largo y el corto tiene al menos
   * dos: "BLADIMIR GARCIA" está contenido en "GARCIA CRUZ BLADIMIR LEON".
   */
  public static boolean contenido(String corto, String largo) {
    if (corto == null || largo == null) {
      return false;
    }
    List<String> pocas = palabras(corto);
    List<String> muchas = new ArrayList<>(palabras(largo));
    if (pocas.size() < 2 || pocas.size() > muchas.size()) {
      return false;
    }
    for (String palabra : pocas) {
      int indice = indiceParecida(palabra, muchas);
      if (indice < 0) {
        return false;
      }
      muchas.remove(indice);
    }
    return true;
  }

  /** Distancia de edición (Levenshtein) entre dos textos. */
  public static int distancia(String a, String b) {
    int[] anterior = new int[b.length() + 1];
    int[] actual = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      anterior[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      actual[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int costo = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        actual[j] = Math.min(Math.min(actual[j - 1] + 1, anterior[j] + 1), anterior[j - 1] + costo);
      }
      int[] temporal = anterior;
      anterior = actual;
      actual = temporal;
    }
    return anterior[b.length()];
  }

  /** 1000 − distancia / largo mayor, en milésimas. */
  static int proporcion(String a, String b) {
    int largo = Math.max(a.length(), b.length());
    if (largo == 0) {
      return 1000;
    }
    return 1000 - (distancia(a, b) * 1000 + largo - 1) / largo;
  }

  private static int jaccard(List<String> a, List<String> b) {
    List<String> restantes = new ArrayList<>(b);
    int comunes = 0;
    for (String palabra : a) {
      int indice = indiceParecida(palabra, restantes);
      if (indice >= 0) {
        comunes++;
        restantes.remove(indice);
      }
    }
    int union = a.size() + b.size() - comunes;
    return union == 0 ? 0 : comunes * 1000 / union;
  }

  /** Índice de una palabra igual o con a lo más 1 letra distinta (2 si pasa de 7 letras). */
  private static int indiceParecida(String palabra, List<String> palabras) {
    int exacta = palabras.indexOf(palabra);
    if (exacta >= 0) {
      return exacta;
    }
    int tolerancia = palabra.length() >= 8 ? 2 : palabra.length() >= 5 ? 1 : 0;
    for (int i = 0; i < palabras.size(); i++) {
      String otra = palabras.get(i);
      if (tolerancia > 0
          && Math.abs(otra.length() - palabra.length()) <= tolerancia
          && distancia(palabra, otra) <= tolerancia) {
        return i;
      }
    }
    return -1;
  }

  private static List<String> palabras(String texto) {
    return Arrays.stream(texto.split(" ")).filter(p -> !p.isEmpty()).toList();
  }

  private static List<String> ordenadas(List<String> palabras) {
    return palabras.stream().sorted().toList();
  }
}

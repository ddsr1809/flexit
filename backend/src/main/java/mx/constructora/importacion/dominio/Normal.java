package mx.constructora.importacion.dominio;

import java.util.Locale;

/**
 * Normalización de texto igual a la función {@code compartido.normalizar} de la base: sin acentos,
 * mayúsculas, espacios simples y sin espacios en los extremos. Es la llave con la que se empatan
 * nombres del Excel con registros del sistema y con la tabla de alias.
 *
 * <p>Traduce exactamente las mismas letras que la función SQL (vocales acentuadas, ñ y ç); así un
 * nombre normalizado en Java y en PostgreSQL es el mismo texto.
 */
public final class Normal {

  private static final String CON_ACENTO = "áàäâãéèëêíìïîóòöôõúùüûñçÁÀÄÂÃÉÈËÊÍÌÏÎÓÒÖÔÕÚÙÜÛÑÇ";
  private static final String SIN_ACENTO = "aaaaaeeeeiiiiooooouuuuncAAAAAEEEEIIIIOOOOOUUUUNC";

  private Normal() {}

  /** {@code " Cemento gris "} → {@code CEMENTO GRIS}; {@code null} si queda vacío. */
  public static String texto(String texto) {
    if (texto == null) {
      return null;
    }
    StringBuilder salida = new StringBuilder(texto.length());
    boolean espacio = false;
    for (int i = 0; i < texto.length(); i++) {
      char c = texto.charAt(i);
      int indice = CON_ACENTO.indexOf(c);
      if (indice >= 0) {
        c = SIN_ACENTO.charAt(indice);
      }
      // Los mismos espacios que \s en las expresiones de PostgreSQL, más el espacio duro de Excel.
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B || c == 0xA0) {
        espacio = true;
        continue;
      }
      if (espacio && salida.length() > 0) {
        salida.append(' ');
      }
      espacio = false;
      salida.append(c);
    }
    String resultado = salida.toString().toUpperCase(Locale.ROOT);
    return resultado.isEmpty() ? null : resultado;
  }

  /** Normalizado y además sin signos: solo letras, números y espacios ({@code "M.20 / L-3"}). */
  public static String palabras(String texto) {
    String normal = texto(texto);
    if (normal == null) {
      return null;
    }
    return texto(normal.replaceAll("[^A-Z0-9Ñ ]+", " "));
  }

  /** Clave corta de un nombre: solo letras y números, hasta {@code largo} caracteres. */
  public static String clave(String texto, int largo) {
    String normal = texto(texto);
    if (normal == null) {
      return null;
    }
    String clave = normal.replaceAll("[^A-Z0-9]", "");
    if (clave.isEmpty()) {
      return null;
    }
    return clave.length() <= largo ? clave : clave.substring(0, largo);
  }
}

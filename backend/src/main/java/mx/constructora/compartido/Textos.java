package mx.constructora.compartido;

import java.util.Locale;

/** Limpieza de texto capturado antes de guardarlo. */
public final class Textos {

  private Textos() {}

  /** Sin espacios en los extremos y con espacios simples; {@code null} si queda vacío. */
  public static String limpio(String texto) {
    if (texto == null || texto.isBlank()) {
      return null;
    }
    return texto.trim().replaceAll("\\s+", " ");
  }

  /** Como {@link #limpio} y en mayúsculas: claves, RFC. */
  public static String mayusculas(String texto) {
    String limpio = limpio(texto);
    return limpio == null ? null : limpio.toUpperCase(Locale.ROOT);
  }
}

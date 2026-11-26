package mx.constructora.importacion.dominio;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON mínimo para las columnas {@code jsonb} de la migración (renglón crudo, normalizado,
 * sugerencias y decisiones). Solo objetos, listas, texto, números (siempre {@link BigDecimal}),
 * booleanos y nulos: no depende de la versión de Jackson que traiga Spring Boot y nunca convierte
 * un número a {@code double}.
 */
public final class Json {

  private Json() {}

  /** Texto JSON de {@code valor}: Map, List, CharSequence, Number, Boolean o {@code null}. */
  public static String escribir(Object valor) {
    StringBuilder salida = new StringBuilder();
    escribir(valor, salida);
    return salida.toString();
  }

  private static void escribir(Object valor, StringBuilder salida) {
    switch (valor) {
      case null -> salida.append("null");
      case Map<?, ?> mapa -> {
        salida.append('{');
        boolean primero = true;
        for (Map.Entry<?, ?> entrada : mapa.entrySet()) {
          if (!primero) {
            salida.append(',');
          }
          primero = false;
          texto(String.valueOf(entrada.getKey()), salida);
          salida.append(':');
          escribir(entrada.getValue(), salida);
        }
        salida.append('}');
      }
      case Iterable<?> lista -> {
        salida.append('[');
        boolean primero = true;
        for (Object elemento : lista) {
          if (!primero) {
            salida.append(',');
          }
          primero = false;
          escribir(elemento, salida);
        }
        salida.append(']');
      }
      case BigDecimal numero -> salida.append(numero.toPlainString());
      case Integer numero -> salida.append(numero);
      case Long numero -> salida.append(numero);
      case Short numero -> salida.append(numero);
      case Number numero -> salida.append(new BigDecimal(numero.toString()).toPlainString());
      case Boolean logico -> salida.append(logico);
      default -> texto(valor.toString(), salida);
    }
  }

  private static void texto(String texto, StringBuilder salida) {
    salida.append('"');
    for (int i = 0; i < texto.length(); i++) {
      char c = texto.charAt(i);
      switch (c) {
        case '"' -> salida.append("\\\"");
        case '\\' -> salida.append("\\\\");
        case '\n' -> salida.append("\\n");
        case '\r' -> salida.append("\\r");
        case '\t' -> salida.append("\\t");
        case '\b' -> salida.append("\\b");
        case '\f' -> salida.append("\\f");
        default -> {
          if (c < 0x20) {
            salida.append(String.format("\\u%04x", (int) c));
          } else {
            salida.append(c);
          }
        }
      }
    }
    salida.append('"');
  }

  /**
   * Lee un texto JSON. Objetos como {@link LinkedHashMap}, listas como {@link ArrayList}, números
   * como {@link BigDecimal}.
   *
   * @throws IllegalArgumentException si no es JSON válido
   */
  public static Object leer(String json) {
    if (json == null) {
      return null;
    }
    Lector lector = new Lector(json);
    Object valor = lector.valor();
    lector.espacios();
    if (lector.posicion != json.length()) {
      throw lector.error("Sobra texto después del valor");
    }
    return valor;
  }

  /** Como {@link #leer} pero exige un objeto; {@code null} o vacío dan un mapa vacío. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> objeto(String json) {
    if (json == null || json.isBlank()) {
      return new LinkedHashMap<>();
    }
    Object valor = leer(json);
    if (!(valor instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("Se esperaba un objeto JSON");
    }
    return (Map<String, Object>) valor;
  }

  /** Objeto JSON de valores de texto (como el renglón crudo): los no textos se convierten. */
  public static Map<String, String> textos(String json) {
    Map<String, String> resultado = new LinkedHashMap<>();
    objeto(json)
        .forEach(
            (clave, valor) ->
                resultado.put(
                    clave,
                    valor == null
                        ? null
                        : valor instanceof BigDecimal n ? n.toPlainString() : valor.toString()));
    return resultado;
  }

  private static final class Lector {
    private final String texto;
    private int posicion;

    Lector(String texto) {
      this.texto = texto;
    }

    IllegalArgumentException error(String mensaje) {
      return new IllegalArgumentException(mensaje + " en la posición " + posicion);
    }

    void espacios() {
      while (posicion < texto.length() && Character.isWhitespace(texto.charAt(posicion))) {
        posicion++;
      }
    }

    char siguiente() {
      espacios();
      if (posicion >= texto.length()) {
        throw error("El JSON termina antes de tiempo");
      }
      return texto.charAt(posicion);
    }

    void esperar(char c) {
      if (siguiente() != c) {
        throw error("Se esperaba '" + c + "'");
      }
      posicion++;
    }

    Object valor() {
      char c = siguiente();
      return switch (c) {
        case '{' -> objeto();
        case '[' -> lista();
        case '"' -> cadena();
        case 't' -> palabra("true", Boolean.TRUE);
        case 'f' -> palabra("false", Boolean.FALSE);
        case 'n' -> palabra("null", null);
        default -> {
          if (c == '-' || (c >= '0' && c <= '9')) {
            yield numero();
          }
          throw error("Valor inesperado");
        }
      };
    }

    private Object palabra(String palabra, Object valor) {
      if (!texto.startsWith(palabra, posicion)) {
        throw error("Se esperaba " + palabra);
      }
      posicion += palabra.length();
      return valor;
    }

    private Map<String, Object> objeto() {
      esperar('{');
      Map<String, Object> mapa = new LinkedHashMap<>();
      if (siguiente() == '}') {
        posicion++;
        return mapa;
      }
      while (true) {
        if (siguiente() != '"') {
          throw error("Se esperaba el nombre de un campo");
        }
        String clave = cadena();
        esperar(':');
        mapa.put(clave, valor());
        char c = siguiente();
        posicion++;
        if (c == '}') {
          return mapa;
        }
        if (c != ',') {
          throw error("Se esperaba ',' o '}'");
        }
      }
    }

    private List<Object> lista() {
      esperar('[');
      List<Object> lista = new ArrayList<>();
      if (siguiente() == ']') {
        posicion++;
        return lista;
      }
      while (true) {
        lista.add(valor());
        char c = siguiente();
        posicion++;
        if (c == ']') {
          return lista;
        }
        if (c != ',') {
          throw error("Se esperaba ',' o ']'");
        }
      }
    }

    private String cadena() {
      esperar('"');
      StringBuilder salida = new StringBuilder();
      while (true) {
        if (posicion >= texto.length()) {
          throw error("Texto sin cerrar");
        }
        char c = texto.charAt(posicion++);
        if (c == '"') {
          return salida.toString();
        }
        if (c != '\\') {
          salida.append(c);
          continue;
        }
        if (posicion >= texto.length()) {
          throw error("Escape incompleto");
        }
        char escape = texto.charAt(posicion++);
        switch (escape) {
          case '"', '\\', '/' -> salida.append(escape);
          case 'n' -> salida.append('\n');
          case 'r' -> salida.append('\r');
          case 't' -> salida.append('\t');
          case 'b' -> salida.append('\b');
          case 'f' -> salida.append('\f');
          case 'u' -> {
            if (posicion + 4 > texto.length()) {
              throw error("Escape \\u incompleto");
            }
            try {
              salida.append((char) Integer.parseInt(texto.substring(posicion, posicion + 4), 16));
            } catch (NumberFormatException e) {
              throw error("Escape \\u inválido");
            }
            posicion += 4;
          }
          default -> throw error("Escape desconocido");
        }
      }
    }

    private BigDecimal numero() {
      int inicio = posicion;
      while (posicion < texto.length() && "+-0123456789.eE".indexOf(texto.charAt(posicion)) >= 0) {
        posicion++;
      }
      try {
        return new BigDecimal(texto.substring(inicio, posicion));
      } catch (NumberFormatException e) {
        throw error("Número inválido");
      }
    }
  }
}

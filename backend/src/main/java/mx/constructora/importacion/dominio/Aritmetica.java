package mx.constructora.importacion.dominio;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Fórmulas que en realidad son captura: {@code =1/3} en la cantidad de una salida (un bote para
 * tres lotes), {@code =206.95/30} en un precio, {@code =2500+2500} en un pago de destajo. Solo se
 * evalúan si son aritmética de números escritos (sin referencias a celdas ni funciones); cualquier
 * otra fórmula no se lee (regla 14).
 */
public final class Aritmetica {

  /** Decimales con los que se guarda el resultado de una división. */
  public static final int ESCALA = 8;

  private static final MathContext CONTEXTO = new MathContext(34, RoundingMode.HALF_UP);
  private static final Pattern PERMITIDO = Pattern.compile("^[0-9.+\\-*/()\\s]+$");
  private static final Pattern SUMA =
      Pattern.compile("^\\s*\\+?\\s*[0-9]+(\\.[0-9]+)?(\\s*\\+\\s*[0-9]+(\\.[0-9]+)?)+\\s*$");

  private final String texto;
  private int pos;

  private Aritmetica(String texto) {
    this.texto = texto;
  }

  /**
   * El valor de la fórmula (sin el {@code =}) si es aritmética simple, redondeado a {@link #ESCALA}
   * decimales; vacío si tiene referencias, funciones o no se entiende.
   */
  public static Optional<BigDecimal> evaluar(String formula) {
    if (formula == null) {
      return Optional.empty();
    }
    String limpia = formula.startsWith("=") ? formula.substring(1) : formula;
    if (limpia.isBlank() || !PERMITIDO.matcher(limpia).matches()) {
      return Optional.empty();
    }
    try {
      Aritmetica a = new Aritmetica(limpia);
      BigDecimal valor = a.suma();
      a.espacios();
      if (a.pos != a.texto.length()) {
        return Optional.empty();
      }
      return Optional.of(normal(valor.setScale(ESCALA, RoundingMode.HALF_UP)));
    } catch (ArithmeticException | NumberFormatException | IndexOutOfBoundsException e) {
      return Optional.empty();
    }
  }

  /**
   * Los sumandos de una fórmula que solo suma números ({@code =3100+7232} → 3100, 7232); vacío si
   * hace otra cosa. Cada sumando de un pago de destajo es un pago parcial.
   */
  public static List<BigDecimal> sumandos(String formula) {
    if (formula == null) {
      return List.of();
    }
    String limpia = formula.startsWith("=") ? formula.substring(1) : formula;
    if (!SUMA.matcher(limpia).matches()) {
      return List.of();
    }
    List<BigDecimal> lista = new ArrayList<>();
    for (String parte : limpia.split("\\+")) {
      if (!parte.isBlank()) {
        lista.add(new BigDecimal(parte.trim()));
      }
    }
    return lista;
  }

  /** {@code 2.50000000} → {@code 2.5}; {@code 300.00} → {@code 300}. */
  public static BigDecimal normal(BigDecimal valor) {
    BigDecimal sinCeros = valor.stripTrailingZeros();
    return sinCeros.scale() < 0 ? sinCeros.setScale(0) : sinCeros;
  }

  private BigDecimal suma() {
    BigDecimal valor = producto();
    while (true) {
      espacios();
      if (siguiente('+')) {
        valor = valor.add(producto(), CONTEXTO);
      } else if (siguiente('-')) {
        valor = valor.subtract(producto(), CONTEXTO);
      } else {
        return valor;
      }
    }
  }

  private BigDecimal producto() {
    BigDecimal valor = factor();
    while (true) {
      espacios();
      if (siguiente('*')) {
        valor = valor.multiply(factor(), CONTEXTO);
      } else if (siguiente('/')) {
        valor = valor.divide(factor(), CONTEXTO);
      } else {
        return valor;
      }
    }
  }

  private BigDecimal factor() {
    espacios();
    if (siguiente('-')) {
      return factor().negate();
    }
    if (siguiente('+')) {
      return factor();
    }
    if (siguiente('(')) {
      BigDecimal valor = suma();
      espacios();
      if (!siguiente(')')) {
        throw new NumberFormatException("falta )");
      }
      return valor;
    }
    int inicio = pos;
    while (pos < texto.length()
        && (Character.isDigit(texto.charAt(pos)) || texto.charAt(pos) == '.')) {
      pos++;
    }
    return new BigDecimal(texto.substring(inicio, pos));
  }

  private boolean siguiente(char c) {
    if (pos < texto.length() && texto.charAt(pos) == c) {
      pos++;
      return true;
    }
    return false;
  }

  private void espacios() {
    while (pos < texto.length() && Character.isWhitespace(texto.charAt(pos))) {
      pos++;
    }
  }
}

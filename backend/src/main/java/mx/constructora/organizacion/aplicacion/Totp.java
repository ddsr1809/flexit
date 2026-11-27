package mx.constructora.organizacion.aplicacion;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Códigos de un solo uso por tiempo (RFC 6238), los que muestran Google Authenticator, Microsoft
 * Authenticator o 1Password: HMAC-SHA1, 6 dígitos, ventanas de 30 segundos. Java puro.
 */
final class Totp {

  static final int DIGITOS = 6;
  static final long PERIODO_SEGUNDOS = 30;

  /** Ventanas aceptadas antes y después de la actual: un teléfono con el reloj un poco movido. */
  static final int TOLERANCIA = 1;

  private static final Pattern SEIS_DIGITOS = Pattern.compile("\\d{6}");
  private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

  private Totp() {}

  /** Ventana de 30 s a la que pertenece el instante. */
  static long paso(Instant instante) {
    return Math.floorDiv(instante.getEpochSecond(), PERIODO_SEGUNDOS);
  }

  /** El código de 6 dígitos de una ventana. */
  static String codigo(byte[] secreto, long paso) {
    byte[] mensaje = new byte[8];
    long valor = paso;
    for (int i = 7; i >= 0; i--) {
      mensaje[i] = (byte) (valor & 0xFF);
      valor >>>= 8;
    }
    byte[] hash;
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secreto, "HmacSHA1"));
      hash = mac.doFinal(mensaje);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
    int corte = hash[hash.length - 1] & 0x0F;
    int binario =
        ((hash[corte] & 0x7F) << 24)
            | ((hash[corte + 1] & 0xFF) << 16)
            | ((hash[corte + 2] & 0xFF) << 8)
            | (hash[corte + 3] & 0xFF);
    return String.format("%06d", binario % 1_000_000);
  }

  /**
   * La ventana en la que el código es válido (la actual, la anterior o la siguiente), o vacío. Los
   * espacios se ignoran: "123 456" es "123456". Compara en tiempo constante.
   */
  static OptionalLong verificar(byte[] secreto, String codigo, Instant ahora) {
    if (codigo == null) {
      return OptionalLong.empty();
    }
    String limpio = codigo.replaceAll("\\s", "");
    if (!SEIS_DIGITOS.matcher(limpio).matches()) {
      return OptionalLong.empty();
    }
    byte[] dado = limpio.getBytes(StandardCharsets.US_ASCII);
    long actual = paso(ahora);
    for (long p = actual - TOLERANCIA; p <= actual + TOLERANCIA; p++) {
      if (MessageDigest.isEqual(codigo(secreto, p).getBytes(StandardCharsets.US_ASCII), dado)) {
        return OptionalLong.of(p);
      }
    }
    return OptionalLong.empty();
  }

  /** Base32 (RFC 4648) sin relleno: así se captura el secreto a mano en la app del teléfono. */
  static String base32(byte[] datos) {
    StringBuilder texto = new StringBuilder((datos.length * 8 + 4) / 5);
    int buffer = 0;
    int bits = 0;
    for (byte b : datos) {
      buffer = (buffer << 8) | (b & 0xFF);
      bits += 8;
      while (bits >= 5) {
        texto.append(BASE32[(buffer >>> (bits - 5)) & 0x1F]);
        bits -= 5;
      }
    }
    if (bits > 0) {
      texto.append(BASE32[(buffer << (5 - bits)) & 0x1F]);
    }
    return texto.toString();
  }

  /** La dirección {@code otpauth://} que lee la app del teléfono desde el código QR. */
  static String uri(String emisor, String cuenta, byte[] secreto) {
    return "otpauth://totp/"
        + codificar(emisor)
        + ":"
        + codificar(cuenta)
        + "?secret="
        + base32(secreto)
        + "&issuer="
        + codificar(emisor)
        + "&algorithm=SHA1&digits="
        + DIGITOS
        + "&period="
        + PERIODO_SEGUNDOS;
  }

  private static String codificar(String texto) {
    return URLEncoder.encode(texto, StandardCharsets.UTF_8).replace("+", "%20");
  }
}

package mx.constructora.estructura;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Código de contrato normalizado. El SUA escribe {@code 60-KBU-MEK/C001/2026} y el destajo {@code
 * 60-KZ7-MF6-C002-2026}; se guarda siempre con guiones y en mayúsculas para que el mismo contrato
 * no quede dos veces. Lo usa también la migración desde Excel (H3).
 */
public final class CodigoContrato {

  private static final Pattern VALIDO = Pattern.compile("^[A-Z0-9]+(-[A-Z0-9]+)*$");

  private CodigoContrato() {}

  /** {@code " 60-kbu-mek/C001/2026 "} → {@code 60-KBU-MEK-C001-2026}; vacío si no es un código. */
  public static Optional<String> normalizar(String texto) {
    if (texto == null) {
      return Optional.empty();
    }
    String codigo =
        texto
            .trim()
            .toUpperCase(Locale.ROOT)
            .replaceAll("[\\s/_.]+", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-|-$", "");
    if (codigo.isEmpty() || codigo.length() > 40 || !VALIDO.matcher(codigo).matches()) {
      return Optional.empty();
    }
    return Optional.of(codigo);
  }
}

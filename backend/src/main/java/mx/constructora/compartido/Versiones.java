package mx.constructora.compartido;

import org.springframework.http.HttpStatus;

/**
 * Concurrencia optimista. Cada registro editable lleva {@code version}; la respuesta la expone como
 * {@code ETag} y el cambio la devuelve en {@code If-Match}. Si alguien más guardó antes, el {@code
 * UPDATE … WHERE version = :version} no toca ninguna fila y se responde 412: nadie pisa el cambio
 * de otro sin verlo.
 */
public final class Versiones {

  private Versiones() {}

  /** Valor del encabezado {@code ETag} para una versión: {@code "3"}. */
  public static String etag(int version) {
    return "\"" + version + "\"";
  }

  /**
   * Lee la versión de {@code If-Match}. Acepta {@code 3}, {@code "3"} y {@code W/"3"}.
   *
   * @throws ErrorNegocio 428 si falta el encabezado, 400 si no es una versión
   */
  public static int deEncabezado(String siCoincide) {
    if (siCoincide == null || siCoincide.isBlank()) {
      throw new ErrorNegocio(
          HttpStatus.PRECONDITION_REQUIRED,
          "FALTA_VERSION",
          "Falta el encabezado If-Match con la versión que se está modificando");
    }
    String valor = siCoincide.trim();
    if (valor.startsWith("W/")) {
      valor = valor.substring(2);
    }
    valor = valor.replace("\"", "");
    try {
      int version = Integer.parseInt(valor);
      if (version < 0) {
        throw new NumberFormatException(valor);
      }
      return version;
    } catch (NumberFormatException e) {
      throw ErrorNegocio.invalido("VERSION_INVALIDA", "If-Match no contiene una versión válida");
    }
  }

  /** El registro cambió desde que se leyó. */
  public static ErrorNegocio desactualizada() {
    return new ErrorNegocio(
        HttpStatus.PRECONDITION_FAILED,
        "VERSION_DESACTUALIZADA",
        "Alguien más modificó este registro. Vuelve a cargarlo y repite el cambio.");
  }
}

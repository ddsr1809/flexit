package mx.constructora.compartido;

import org.springframework.http.HttpStatus;

/**
 * Error que el usuario puede entender y corregir. Se responde como Problem Details (RFC 9457) con
 * un {@code codigo} estable que el frontend traduce, por ejemplo {@code EXISTENCIA_INSUFICIENTE}.
 */
public class ErrorNegocio extends RuntimeException {

  private final HttpStatus estado;
  private final String codigo;

  public ErrorNegocio(HttpStatus estado, String codigo, String mensaje) {
    super(mensaje);
    this.estado = estado;
    this.codigo = codigo;
  }

  public static ErrorNegocio invalido(String codigo, String mensaje) {
    return new ErrorNegocio(HttpStatus.BAD_REQUEST, codigo, mensaje);
  }

  public static ErrorNegocio conflicto(String codigo, String mensaje) {
    return new ErrorNegocio(HttpStatus.CONFLICT, codigo, mensaje);
  }

  public HttpStatus estado() {
    return estado;
  }

  public String codigo() {
    return codigo;
  }
}

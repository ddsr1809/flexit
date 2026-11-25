package mx.constructora.compartido.web;

import java.util.LinkedHashMap;
import java.util.Map;
import mx.constructora.compartido.ErrorNegocio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Todos los errores de la API salen como Problem Details (RFC 9457) con un {@code codigo} estable.
 * El frontend traduce el código a un mensaje en un solo archivo.
 */
@RestControllerAdvice
class ManejadorErrores {

  private static final Logger LOG = LoggerFactory.getLogger(ManejadorErrores.class);

  @ExceptionHandler(ErrorNegocio.class)
  ProblemDetail negocio(ErrorNegocio error) {
    return problema(error.estado(), error.codigo(), error.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail validacion(MethodArgumentNotValidException error) {
    Map<String, String> campos = new LinkedHashMap<>();
    for (FieldError campo : error.getBindingResult().getFieldErrors()) {
      campos.putIfAbsent(campo.getField(), campo.getDefaultMessage());
    }
    ProblemDetail problema =
        problema(HttpStatus.BAD_REQUEST, "VALIDACION", "Hay campos con datos inválidos");
    problema.setProperty("campos", campos);
    return problema;
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ProblemDetail cuerpoInvalido(HttpMessageNotReadableException error) {
    return problema(
        HttpStatus.BAD_REQUEST, "CUERPO_INVALIDO", "La petición no tiene el formato esperado");
  }

  /**
   * Una restricción de la base que el servicio no tradujo a un error propio (por ejemplo dos
   * personas dando de alta la misma clave al mismo tiempo). No es un error interno: es un dato
   * que choca con otro, y el usuario puede corregirlo.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  ProblemDetail integridad(DataIntegrityViolationException error) {
    LOG.warn("Restricción de la base: {}", error.getMessage());
    if (error instanceof DuplicateKeyException) {
      return problema(HttpStatus.CONFLICT, "DUPLICADO", "Ya existe un registro con esos datos");
    }
    return problema(
        HttpStatus.CONFLICT, "DATOS_INCONSISTENTES", "Los datos no son consistentes con otros");
  }

  @ExceptionHandler(AccessDeniedException.class)
  ProblemDetail sinPermiso(AccessDeniedException error) {
    return problema(HttpStatus.FORBIDDEN, "SIN_PERMISO", "No tienes permiso para esta operación");
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail inesperado(Exception error) {
    // 404, 405, 415… de Spring ya traen su Problem Details: se respetan.
    if (error instanceof ErrorResponse respuesta) {
      return respuesta.getBody();
    }
    LOG.error("Error no controlado", error);
    return problema(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "ERROR_INTERNO",
        "Ocurrió un error inesperado. Si se repite, avisa con el identificador de la petición.");
  }

  private static ProblemDetail problema(HttpStatus estado, String codigo, String mensaje) {
    ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, mensaje);
    problema.setTitle(codigo);
    problema.setProperty("codigo", codigo);
    return problema;
  }
}

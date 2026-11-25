package mx.constructora.compartido.seguridad;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import mx.constructora.compartido.ContextoEmpresa;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Pone en cada línea de log el identificador de la petición, el usuario y la empresa, y devuelve el
 * identificador en {@code X-Request-Id} para que un error reportado se encuentre en los logs.
 */
final class FiltroContextoLog extends OncePerRequestFilter {

  static final String ENCABEZADO = "X-Request-Id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
      throws ServletException, IOException {
    String solicitud = UUID.randomUUID().toString().substring(0, 8);
    respuesta.setHeader(ENCABEZADO, solicitud);
    MDC.put("solicitud", solicitud);
    ContextoEmpresa.actual()
        .ifPresent(
            identidad -> {
              MDC.put("usuario", Long.toString(identidad.usuarioId()));
              MDC.put("empresa", Long.toString(identidad.empresaId()));
            });
    try {
      cadena.doFilter(peticion, respuesta);
    } finally {
      MDC.remove("solicitud");
      MDC.remove("usuario");
      MDC.remove("empresa");
    }
  }
}

package mx.constructora.organizacion.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.organizacion.aplicacion.ServicioSegundoFactor.Paso;
import mx.constructora.organizacion.aplicacion.UsuarioSesion;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Inicio de sesión en uno o dos pasos. Después de la contraseña, quien necesita segundo factor
 * queda "pendiente" en la sesión HTTP durante 5 minutos, sin estar autenticado: la API le sigue
 * respondiendo 401 hasta que escribe el código (o da de alta su segundo factor).
 */
@Component
class Sesiones {

  /** Atributo de la sesión HTTP con el inicio de sesión a medias. */
  static final String PENDIENTE = "constructora.segundoFactorPendiente";

  static final Duration VIGENCIA = Duration.ofMinutes(5);

  /** Códigos equivocados que se aceptan antes de pedir otra vez la contraseña. */
  static final int FALLAS_MAXIMAS = 5;

  /** Contraseña validada, falta el segundo factor. Se guarda serializado en la sesión. */
  record Pendiente(UsuarioSesion usuario, String paso, Instant vence, int fallas)
      implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
  }

  private final SecurityContextRepository repositorio;
  private final SecurityContextHolderStrategy estrategia =
      SecurityContextHolder.getContextHolderStrategy();

  Sesiones(SecurityContextRepository repositorio) {
    this.repositorio = repositorio;
  }

  /** Sesión iniciada: identificador nuevo (fijación de sesión) y el usuario en el contexto. */
  void iniciar(
      Authentication autenticacion, HttpServletRequest peticion, HttpServletResponse respuesta) {
    HttpSession sesion = peticion.getSession(false);
    if (sesion != null) {
      peticion.changeSessionId();
      peticion.getSession().removeAttribute(PENDIENTE);
    }
    SecurityContext contexto = estrategia.createEmptyContext();
    contexto.setAuthentication(autenticacion);
    estrategia.setContext(contexto);
    repositorio.saveContext(contexto, peticion, respuesta);
  }

  /** Termina el inicio de sesión de quien estaba pendiente. */
  void iniciar(UsuarioSesion usuario, HttpServletRequest peticion, HttpServletResponse respuesta) {
    iniciar(
        UsernamePasswordAuthenticationToken.authenticated(usuario, null, usuario.getAuthorities()),
        peticion,
        respuesta);
  }

  /**
   * Contraseña correcta, falta el segundo factor. Cierra cualquier sesión anterior del navegador.
   */
  void dejarPendiente(
      UsuarioSesion usuario,
      Paso paso,
      HttpServletRequest peticion,
      HttpServletResponse respuesta) {
    SecurityContext vacio = estrategia.createEmptyContext();
    estrategia.setContext(vacio);
    repositorio.saveContext(vacio, peticion, respuesta);
    if (peticion.getSession(false) != null) {
      peticion.changeSessionId();
    }
    peticion
        .getSession(true)
        .setAttribute(
            PENDIENTE, new Pendiente(usuario, paso.clave(), Instant.now().plus(VIGENCIA), 0));
  }

  /**
   * El inicio de sesión pendiente en el paso indicado.
   *
   * @throws ErrorNegocio 401 {@code SEGUNDO_FACTOR_VENCIDO} si no hay, venció o es de otro paso
   */
  Pendiente pendiente(HttpServletRequest peticion, Paso paso) {
    HttpSession sesion = peticion.getSession(false);
    Object guardado = sesion == null ? null : sesion.getAttribute(PENDIENTE);
    if (!(guardado instanceof Pendiente pendiente)
        || !pendiente.paso().equals(paso.clave())
        || pendiente.vence().isBefore(Instant.now())) {
      if (sesion != null) {
        sesion.removeAttribute(PENDIENTE);
      }
      throw new ErrorNegocio(
          HttpStatus.UNAUTHORIZED,
          "SEGUNDO_FACTOR_VENCIDO",
          "Vuelve a escribir tu usuario y contraseña.");
    }
    return pendiente;
  }

  /** Quita el inicio de sesión pendiente. */
  void olvidar(HttpServletRequest peticion) {
    HttpSession sesion = peticion.getSession(false);
    if (sesion != null) {
      sesion.removeAttribute(PENDIENTE);
    }
  }

  /** Un código equivocado más; a la quinta hay que volver a escribir la contraseña. */
  void registrarFalla(HttpServletRequest peticion, Pendiente pendiente) {
    HttpSession sesion = peticion.getSession(false);
    if (sesion == null) {
      return;
    }
    int fallas = pendiente.fallas() + 1;
    if (fallas >= FALLAS_MAXIMAS) {
      sesion.removeAttribute(PENDIENTE);
    } else {
      sesion.setAttribute(
          PENDIENTE,
          new Pendiente(pendiente.usuario(), pendiente.paso(), pendiente.vence(), fallas));
    }
  }
}

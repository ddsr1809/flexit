package mx.constructora.organizacion.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.organizacion.Rol;
import mx.constructora.organizacion.aplicacion.ServicioSegundoFactor;
import mx.constructora.organizacion.aplicacion.ServicioSegundoFactor.Paso;
import mx.constructora.organizacion.aplicacion.ServicioUsuarios;
import mx.constructora.organizacion.aplicacion.UsuarioSesion;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inicio y consulta de sesión. El cierre ({@code POST /api/v1/auth/logout}) lo atiende Spring
 * Security directamente.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Sesión")
class AutenticacionControlador {

  /** Usuario y contraseña. */
  record SolicitudLogin(
      @NotBlank @Size(max = 60) String usuario, @NotBlank @Size(max = 200) String contrasena) {}

  /** Rol con su nombre para mostrar. */
  record RolDto(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clave,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String nombre) {
    static RolDto de(Rol rol) {
      return new RolDto(rol.clave(), rol.nombre());
    }
  }

  /** Quién está en sesión, de qué empresa y qué puede hacer. */
  record SesionDto(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long usuarioId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String usuario,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String nombre,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long empresaId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String empresa,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<RolDto> roles,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> permisos) {

    static SesionDto de(UsuarioSesion sesion) {
      return new SesionDto(
          sesion.usuarioId(),
          sesion.getUsername(),
          sesion.nombre(),
          sesion.empresaId(),
          sesion.empresa(),
          sesion.roles().stream().map(RolDto::de).toList(),
          List.copyOf(sesion.permisos()));
    }
  }

  /**
   * Falta el segundo factor: {@code codigo} (ya lo tiene) o {@code alta} (tiene que darlo de alta).
   */
  record PasoSegundoFactorDto(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "codigo o alta")
          String paso) {}

  /** Token CSRF y el encabezado en que se devuelve. */
  record CsrfDto(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String token,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String encabezado) {}

  private final AuthenticationManager autenticacion;
  private final ServicioUsuarios usuarios;
  private final ServicioSegundoFactor segundoFactor;
  private final Sesiones sesiones;

  AutenticacionControlador(
      AuthenticationManager autenticacion,
      ServicioUsuarios usuarios,
      ServicioSegundoFactor segundoFactor,
      Sesiones sesiones) {
    this.autenticacion = autenticacion;
    this.usuarios = usuarios;
    this.segundoFactor = segundoFactor;
    this.sesiones = sesiones;
  }

  @PostMapping("/login")
  @Operation(
      operationId = "iniciarSesion",
      summary =
          "Iniciar sesión. 200 con la sesión; 202 si falta el segundo factor: con paso=codigo se"
              + " sigue en /auth/segundo-factor y con paso=alta en /auth/segundo-factor/alta")
  @ApiResponse(
      responseCode = "200",
      description = "Sesión iniciada",
      content = @Content(schema = @Schema(implementation = SesionDto.class)))
  @ApiResponse(
      responseCode = "202",
      description = "Contraseña correcta; falta el segundo factor",
      content = @Content(schema = @Schema(implementation = PasoSegundoFactorDto.class)))
  ResponseEntity<?> login(
      @Valid @RequestBody SolicitudLogin solicitud,
      HttpServletRequest peticion,
      HttpServletResponse respuesta) {
    Authentication resultado;
    try {
      resultado =
          autenticacion.authenticate(
              UsernamePasswordAuthenticationToken.unauthenticated(
                  solicitud.usuario(), solicitud.contrasena()));
    } catch (LockedException e) {
      throw new ErrorNegocio(
          HttpStatus.UNAUTHORIZED,
          "CUENTA_BLOQUEADA",
          "La cuenta está bloqueada por intentos fallidos. Intenta de nuevo en 15 minutos.");
    } catch (DisabledException e) {
      throw new ErrorNegocio(
          HttpStatus.UNAUTHORIZED, "CUENTA_INACTIVA", "La cuenta está desactivada.");
    } catch (AuthenticationException e) {
      usuarios.registrarIntento(solicitud.usuario(), false);
      throw new ErrorNegocio(
          HttpStatus.UNAUTHORIZED, "CREDENCIALES_INVALIDAS", "Usuario o contraseña incorrectos.");
    }
    UsuarioSesion usuario = (UsuarioSesion) resultado.getPrincipal();
    Paso paso = segundoFactor.pasoPara(usuario);
    if (paso != Paso.NINGUNO) {
      // Los intentos fallidos no se reinician hasta que el código también sea correcto.
      sesiones.dejarPendiente(usuario, paso, peticion, respuesta);
      return ResponseEntity.accepted().body(new PasoSegundoFactorDto(paso.clave()));
    }
    usuarios.registrarIntento(solicitud.usuario(), true);
    sesiones.iniciar(resultado, peticion, respuesta);
    return ResponseEntity.ok(SesionDto.de(usuario));
  }

  @GetMapping("/yo")
  @Operation(operationId = "sesionActual", summary = "Usuario en sesión")
  SesionDto yo(@AuthenticationPrincipal UsuarioSesion sesion) {
    return SesionDto.de(sesion);
  }

  /** Llamarlo antes del primer POST: deja la cookie {@code XSRF-TOKEN} en el navegador. */
  @GetMapping("/csrf")
  @Operation(operationId = "tokenCsrf", summary = "Token CSRF")
  CsrfDto csrf(CsrfToken token) {
    return new CsrfDto(token.getToken(), token.getHeaderName());
  }
}

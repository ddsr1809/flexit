package mx.constructora.organizacion.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.organizacion.aplicacion.ServicioSegundoFactor;
import mx.constructora.organizacion.aplicacion.ServicioSegundoFactor.Paso;
import mx.constructora.organizacion.aplicacion.ServicioUsuarios;
import mx.constructora.organizacion.web.AutenticacionControlador.SesionDto;
import mx.constructora.organizacion.web.Sesiones.Pendiente;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Segundo paso del inicio de sesión (código de la app del teléfono), alta del segundo factor y su
 * restablecimiento por administración. Ver {@link Sesiones} y docs/runbooks/segundo-factor.md.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Sesión")
class SegundoFactorControlador {

  /** Código de 6 dígitos de la app del teléfono. */
  record SolicitudCodigo(@NotBlank @Size(max = 20) String codigo) {}

  /** Secreto para darlo de alta: en QR ({@code uri}) o capturado a mano ({@code secreto}). */
  record AltaSegundoFactorDto(
      @Schema(requiredMode = REQUIRED, description = "Base32, para capturarlo a mano")
          String secreto,
      @Schema(requiredMode = REQUIRED, description = "otpauth://… para el código QR") String uri) {}

  private final ServicioSegundoFactor segundoFactor;
  private final ServicioUsuarios usuarios;
  private final Sesiones sesiones;

  SegundoFactorControlador(
      ServicioSegundoFactor segundoFactor, ServicioUsuarios usuarios, Sesiones sesiones) {
    this.segundoFactor = segundoFactor;
    this.usuarios = usuarios;
    this.sesiones = sesiones;
  }

  @PostMapping("/auth/segundo-factor")
  @Operation(
      operationId = "verificarSegundoFactor",
      summary = "Código del segundo factor después de la contraseña (login respondió 202 codigo)")
  SesionDto verificar(
      @Valid @RequestBody SolicitudCodigo solicitud,
      HttpServletRequest peticion,
      HttpServletResponse respuesta) {
    Pendiente pendiente = vigente(peticion, Paso.CODIGO);
    if (!segundoFactor.verificar(pendiente.usuario(), solicitud.codigo())) {
      throw codigoInvalido(peticion, pendiente);
    }
    return terminar(pendiente, peticion, respuesta);
  }

  @PostMapping("/auth/segundo-factor/alta")
  @Operation(
      operationId = "iniciarAltaSegundoFactor",
      summary = "Secreto nuevo para dar de alta el segundo factor (login respondió 202 alta)")
  AltaSegundoFactorDto alta(HttpServletRequest peticion) {
    Pendiente pendiente = sesiones.pendiente(peticion, Paso.ALTA);
    var alta = segundoFactor.iniciarAlta(pendiente.usuario());
    return new AltaSegundoFactorDto(alta.secreto(), alta.uri());
  }

  @PostMapping("/auth/segundo-factor/alta/confirmar")
  @Operation(
      operationId = "confirmarAltaSegundoFactor",
      summary = "Primer código: confirma el alta e inicia la sesión")
  SesionDto confirmar(
      @Valid @RequestBody SolicitudCodigo solicitud,
      HttpServletRequest peticion,
      HttpServletResponse respuesta) {
    Pendiente pendiente = vigente(peticion, Paso.ALTA);
    if (!segundoFactor.confirmarAlta(pendiente.usuario(), solicitud.codigo())) {
      throw codigoInvalido(peticion, pendiente);
    }
    return terminar(pendiente, peticion, respuesta);
  }

  @DeleteMapping("/usuarios/{id}/segundo-factor")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      operationId = "restablecerSegundoFactor",
      summary = "Borrar el segundo factor de otro usuario (perdió el teléfono)")
  void restablecer(@PathVariable long id) {
    segundoFactor.restablecer(id);
  }

  /** El inicio de sesión pendiente, si la cuenta no se bloqueó ni se desactivó mientras tanto. */
  private Pendiente vigente(HttpServletRequest peticion, Paso paso) {
    Pendiente pendiente = sesiones.pendiente(peticion, paso);
    if (usuarios.sinAcceso(pendiente.usuario().getUsername())) {
      sesiones.olvidar(peticion);
      throw new ErrorNegocio(
          HttpStatus.UNAUTHORIZED,
          "CUENTA_BLOQUEADA",
          "La cuenta está bloqueada por intentos fallidos. Intenta de nuevo en 15 minutos.");
    }
    return pendiente;
  }

  private SesionDto terminar(
      Pendiente pendiente, HttpServletRequest peticion, HttpServletResponse respuesta) {
    usuarios.registrarIntento(pendiente.usuario().getUsername(), true);
    sesiones.iniciar(pendiente.usuario(), peticion, respuesta);
    return SesionDto.de(pendiente.usuario());
  }

  /** Cuenta la falla en la sesión y en la cuenta: 5 seguidas la bloquean 15 minutos. */
  private ErrorNegocio codigoInvalido(HttpServletRequest peticion, Pendiente pendiente) {
    usuarios.registrarIntento(pendiente.usuario().getUsername(), false);
    sesiones.registrarFalla(peticion, pendiente);
    return new ErrorNegocio(
        HttpStatus.UNAUTHORIZED,
        "CODIGO_INVALIDO",
        "El código no es válido o ya se usó. Escribe el que muestra ahora la app.");
  }
}

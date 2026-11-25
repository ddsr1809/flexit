package mx.constructora.organizacion.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import mx.constructora.organizacion.Rol;
import mx.constructora.organizacion.aplicacion.ServicioUsuarios;
import mx.constructora.organizacion.aplicacion.ServicioUsuarios.NuevoUsuario;
import mx.constructora.organizacion.infraestructura.RepositorioUsuarios.FilaUsuario;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Usuarios y roles de la empresa en sesión. Requiere el permiso {@code usuarios:administrar}. */
@RestController
@RequestMapping("/api/v1/usuarios")
@Tag(name = "Usuarios")
class UsuariosControlador {

  /** Usuario de la empresa. */
  record UsuarioDto(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String usuario,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String nombre,
      String correo,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean activo,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
          List<AutenticacionControlador.RolDto> roles) {

    static UsuarioDto de(FilaUsuario fila) {
      return new UsuarioDto(
          fila.id(),
          fila.usuario(),
          fila.nombre(),
          fila.correo(),
          fila.activo(),
          fila.roles().stream().map(AutenticacionControlador.RolDto::de).toList());
    }
  }

  /** Alta de usuario. {@code roles} usa las claves: almacenista, residente, coordinacion… */
  record SolicitudNuevoUsuario(
      @NotBlank
          @Pattern(
              regexp = "[A-Za-z0-9._-]{3,60}",
              message = "de 3 a 60 letras, números, punto, guion o guion bajo")
          String usuario,
      @NotBlank @Size(max = 120) String nombre,
      @Email @Size(max = 120) String correo,
      @NotBlank @Size(min = ServicioUsuarios.CONTRASENA_MINIMA, max = 200) String contrasena,
      @NotEmpty Set<@NotBlank String> roles) {}

  /** Roles que se pueden asignar. */
  record CatalogoRolDto(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clave,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String nombre,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> permisos) {}

  private final ServicioUsuarios servicio;

  UsuariosControlador(ServicioUsuarios servicio) {
    this.servicio = servicio;
  }

  @GetMapping
  @Operation(operationId = "listarUsuarios", summary = "Usuarios de la empresa")
  List<UsuarioDto> listar() {
    return servicio.listar().stream().map(UsuarioDto::de).toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "crearUsuario", summary = "Dar de alta un usuario")
  UsuarioDto crear(@Valid @RequestBody SolicitudNuevoUsuario solicitud) {
    var creado =
        servicio.crear(
            new NuevoUsuario(
                solicitud.usuario(),
                solicitud.nombre(),
                solicitud.correo(),
                solicitud.contrasena(),
                solicitud.roles()));
    return UsuarioDto.de(creado);
  }

  @GetMapping("/roles")
  @Operation(operationId = "listarRoles", summary = "Roles disponibles y sus permisos")
  List<CatalogoRolDto> roles() {
    return java.util.Arrays.stream(Rol.values())
        .map(
            rol ->
                new CatalogoRolDto(
                    rol.clave(), rol.nombre(), rol.permisos().stream().sorted().toList()))
        .toList();
  }
}

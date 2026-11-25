package mx.constructora.organizacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collection;
import java.util.List;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.organizacion.infraestructura.RepositorioUsuarios;
import mx.constructora.organizacion.infraestructura.RepositorioUsuarios.FilaResumen;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que otros módulos pueden saber de los usuarios: id, usuario y nombre, nunca datos de acceso.
 * Por ejemplo, {@code estructura} lo usa para mostrar y validar los residentes de un contrato.
 */
@Service
public class DirectorioUsuarios {

  /** Usuario para mostrar. */
  public record UsuarioResumen(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String usuario,
      @Schema(requiredMode = REQUIRED) String nombre) {

    static UsuarioResumen de(FilaResumen fila) {
      return new UsuarioResumen(fila.id(), fila.usuario(), fila.nombre());
    }
  }

  private final RepositorioUsuarios repositorio;

  DirectorioUsuarios(RepositorioUsuarios repositorio) {
    this.repositorio = repositorio;
  }

  /** Los usuarios de la empresa en curso con esos ids; los que no existen se omiten. */
  @Transactional(readOnly = true)
  public List<UsuarioResumen> buscar(Collection<Long> ids) {
    return repositorio.resumen(ContextoEmpresa.requerida().empresaId(), ids).stream()
        .map(UsuarioResumen::de)
        .toList();
  }

  /** Usuarios activos de la empresa en curso que tienen el rol. */
  @Transactional(readOnly = true)
  public List<UsuarioResumen> activosConRol(Rol rol) {
    return repositorio.activosConRol(ContextoEmpresa.requerida().empresaId(), rol).stream()
        .map(UsuarioResumen::de)
        .toList();
  }
}

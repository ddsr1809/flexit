package mx.constructora.estructura;

import java.util.Optional;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.estructura.infraestructura.RepositorioUnidades;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que otros módulos (inventario en H4, importación en H3) consultan de la estructura sin tocar
 * sus tablas.
 */
@Service
public class EstructuraApi {

  /** Unidad de obra con su contrato: a dónde se carga un costo. */
  public record UnidadInfo(
      long id, long contratoId, String clave, Long prototipoId, boolean activa) {}

  private final RepositorioUnidades unidades;

  EstructuraApi(RepositorioUnidades unidades) {
    this.unidades = unidades;
  }

  /** La unidad de obra de la empresa en curso, si existe. */
  @Transactional(readOnly = true)
  public Optional<UnidadInfo> unidad(long id) {
    return unidades
        .unidad(ContextoEmpresa.requerida().empresaId(), id)
        .map(
            u -> new UnidadInfo(u.id(), u.contratoId(), u.clave(), u.prototipoId(), u.activa()));
  }
}

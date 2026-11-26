package mx.constructora.organizacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collection;
import java.util.List;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.organizacion.infraestructura.RepositorioPersonas;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registro de personas para otros módulos: la migración (H3) las da de alta y las liga; inventario,
 * personal y destajo las usan como solicitante, trabajador o responsable. Solo id y nombre: NSS,
 * CURP y notas se ven en la pantalla de personas, con permiso.
 */
@Service
public class Personas {

  /** Persona para empatar nombres. */
  public record PersonaDato(long id, String nombre, String nombreNormal) {}

  /** Persona para mostrar o elegir: solo id y nombre. */
  public record PersonaNombre(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String nombre,
      @Schema(requiredMode = REQUIRED) boolean activa) {}

  private final RepositorioPersonas repositorio;

  Personas(RepositorioPersonas repositorio) {
    this.repositorio = repositorio;
  }

  /** Personas activas de la empresa en curso. */
  @Transactional(readOnly = true)
  public List<PersonaDato> activas() {
    return repositorio.activas(ContextoEmpresa.requerida().empresaId());
  }

  /** Nombres de las personas con esos ids (de la empresa en curso). */
  @Transactional(readOnly = true)
  public List<PersonaNombre> nombres(Collection<Long> ids) {
    return ids.isEmpty()
        ? List.of()
        : repositorio.nombres(ContextoEmpresa.requerida().empresaId(), ids);
  }

  /**
   * Alta desde una carga de Excel.
   *
   * @param revisar se dio de alta desde un apodo: falta el nombre completo
   */
  @Transactional
  public long crearDesdeCarga(long carga, String nombre, boolean revisar, String nota) {
    return repositorio.insertar(
        ContextoEmpresa.requerida().empresaId(), carga, nombre, revisar, nota);
  }

  /**
   * Borra las personas que creó la carga.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException si algo ya las usa
   */
  @Transactional
  public int revertirCarga(long carga) {
    return repositorio.revertir(carga);
  }
}

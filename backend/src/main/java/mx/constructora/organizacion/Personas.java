package mx.constructora.organizacion;

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

  private final RepositorioPersonas repositorio;

  Personas(RepositorioPersonas repositorio) {
    this.repositorio = repositorio;
  }

  /** Personas activas de la empresa en curso. */
  @Transactional(readOnly = true)
  public List<PersonaDato> activas() {
    return repositorio.activas(ContextoEmpresa.requerida().empresaId());
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

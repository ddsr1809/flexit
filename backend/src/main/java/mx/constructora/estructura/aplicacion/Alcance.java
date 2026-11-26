package mx.constructora.estructura.aplicacion;

import mx.constructora.compartido.Autorizacion;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.Permisos;

/**
 * Qué contratos ve el usuario en curso. Con {@code estructura:ver-todo} ve todos los de su empresa;
 * sin él (residente), solo los asignados en {@code estructura.contrato_usuario}.
 */
public final class Alcance {

  private Alcance() {}

  /** {@code null} si ve todos los contratos; si no, el id del usuario al que se restringe. */
  public static Long restringidoA() {
    if (Autorizacion.tiene(Permisos.ESTRUCTURA_VER_TODO)) {
      return null;
    }
    return ContextoEmpresa.requerida().usuarioId();
  }
}

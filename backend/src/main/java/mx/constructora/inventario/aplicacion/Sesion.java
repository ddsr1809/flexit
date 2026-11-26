package mx.constructora.inventario.aplicacion;

import java.time.LocalDate;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.Folios;
import mx.constructora.compartido.Permisos;

/** Empresa, usuario y fecha de la operación en curso, y las expresiones de permiso del módulo. */
final class Sesion {

  static final String CAPTURAR = "hasAuthority('" + Permisos.MOVIMIENTO_CAPTURAR + "')";
  static final String ADMINISTRAR = "hasAuthority('" + Permisos.ADMINISTRAR + "')";
  static final String AUTORIZAR =
      "hasAnyAuthority('" + Permisos.VALE_AUTORIZAR + "', '" + Permisos.DOCUMENTO_AUTORIZAR + "')";
  static final String CONSULTAR =
      "hasAnyAuthority('"
          + Permisos.EXISTENCIAS_VER
          + "', '"
          + Permisos.MOVIMIENTO_CAPTURAR
          + "', '"
          + Permisos.VALE_AUTORIZAR
          + "', '"
          + Permisos.DOCUMENTO_AUTORIZAR
          + "')";

  private Sesion() {}

  static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }

  static long usuario() {
    return ContextoEmpresa.requerida().usuarioId();
  }

  /** Hoy en la zona de la empresa (México). */
  static LocalDate hoy() {
    return LocalDate.now(Folios.ZONA);
  }
}

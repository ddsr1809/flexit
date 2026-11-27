package mx.constructora.abastecimiento.aplicacion;

import java.time.LocalDate;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.Folios;
import mx.constructora.compartido.Permisos;

/** Empresa, usuario y fecha en curso, y las expresiones de permiso del módulo. */
final class Acceso {

  static final String CREAR_REQUISICION = "hasAuthority('" + Permisos.REQUISICION_CREAR + "')";
  static final String AUTORIZAR = "hasAuthority('" + Permisos.DOCUMENTO_AUTORIZAR + "')";
  static final String CAPTURAR_ALMACEN = "hasAuthority('" + Permisos.MOVIMIENTO_CAPTURAR + "')";
  static final String CREAR_PEDIDO = "hasAuthority('" + Permisos.PEDIDO_CREAR + "')";

  /** Cerrar o cancelar una requisición: quien la pide o quien la autoriza. */
  static final String TERMINAR_REQUISICION =
      "hasAnyAuthority('"
          + Permisos.REQUISICION_CREAR
          + "', '"
          + Permisos.DOCUMENTO_AUTORIZAR
          + "')";

  /** Volver a apartar existencia: el almacén, compras o quien autoriza. */
  static final String RESERVAR =
      "hasAnyAuthority('"
          + Permisos.MOVIMIENTO_CAPTURAR
          + "', '"
          + Permisos.PEDIDO_CREAR
          + "', '"
          + Permisos.DOCUMENTO_AUTORIZAR
          + "')";

  /** Ver requisiciones: quien pide, autoriza, surte o compra. */
  static final String VER_REQUISICIONES =
      "hasAnyAuthority('"
          + Permisos.REQUISICION_CREAR
          + "', '"
          + Permisos.DOCUMENTO_AUTORIZAR
          + "', '"
          + Permisos.VALE_AUTORIZAR
          + "', '"
          + Permisos.MOVIMIENTO_CAPTURAR
          + "', '"
          + Permisos.PEDIDO_CREAR
          + "')";

  /** Ver pedidos: compras, el almacén que recibe y quien ve costos o autoriza. */
  static final String VER_PEDIDOS =
      "hasAnyAuthority('"
          + Permisos.PEDIDO_CREAR
          + "', '"
          + Permisos.MOVIMIENTO_CAPTURAR
          + "', '"
          + Permisos.DOCUMENTO_AUTORIZAR
          + "', '"
          + Permisos.COSTO_VER_TODO
          + "')";

  private Acceso() {}

  static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }

  static long usuario() {
    return ContextoEmpresa.requerida().usuarioId();
  }

  static LocalDate hoy() {
    return LocalDate.now(Folios.ZONA);
  }
}

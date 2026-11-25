package mx.constructora.compartido;

/**
 * Permisos finos que revisan los servicios con {@code @PreAuthorize("hasAuthority(...)")}. Viven en
 * {@code compartido} para que cualquier módulo los use sin depender de {@code organizacion}, que es
 * donde se decide qué rol tiene cada permiso.
 */
public final class Permisos {

  /** Capturar recepciones, vales, devoluciones, traspasos y mermas. */
  public static final String MOVIMIENTO_CAPTURAR = "movimiento:capturar";

  /** Crear requisiciones de material. */
  public static final String REQUISICION_CREAR = "requisicion:crear";

  /** Autorizar requisiciones, traspasos y reasignaciones de otra persona. */
  public static final String DOCUMENTO_AUTORIZAR = "documento:autorizar";

  /** Crear pedidos a proveedor. */
  public static final String PEDIDO_CREAR = "pedido:crear";

  /** Consultar existencias (cantidades, sin costo). */
  public static final String EXISTENCIAS_VER = "existencias:ver";

  /** Ver precios de compra. */
  public static final String PRECIO_VER = "precio:ver";

  /** Ver el costo solo de los contratos asignados al usuario. */
  public static final String COSTO_VER_PROPIO = "costo:ver-propio";

  /** Ver el costo de todos los contratos. */
  public static final String COSTO_VER_TODO = "costo:ver-todo";

  /** Cerrar mes y administrar la estructura: clientes, frentes, contratos, lotes y paquetes. */
  public static final String ADMINISTRAR = "sistema:administrar";

  /** Dar de alta y cambiar materiales, proveedores, unidades de medida y categorías. */
  public static final String CATALOGO_EDITAR = "catalogo:editar";

  /**
   * Ver todos los contratos y sus lotes. Sin este permiso (residente) solo se ven los contratos
   * asignados al usuario.
   */
  public static final String ESTRUCTURA_VER_TODO = "estructura:ver-todo";

  /** Dar de alta usuarios y asignar roles. */
  public static final String USUARIOS_ADMINISTRAR = "usuarios:administrar";

  private Permisos() {}
}

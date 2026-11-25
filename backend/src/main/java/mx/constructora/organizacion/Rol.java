package mx.constructora.organizacion;

import static mx.constructora.compartido.Permisos.ADMINISTRAR;
import static mx.constructora.compartido.Permisos.COSTO_VER_PROPIO;
import static mx.constructora.compartido.Permisos.COSTO_VER_TODO;
import static mx.constructora.compartido.Permisos.DOCUMENTO_AUTORIZAR;
import static mx.constructora.compartido.Permisos.EXISTENCIAS_VER;
import static mx.constructora.compartido.Permisos.MOVIMIENTO_CAPTURAR;
import static mx.constructora.compartido.Permisos.PEDIDO_CREAR;
import static mx.constructora.compartido.Permisos.PRECIO_VER;
import static mx.constructora.compartido.Permisos.REQUISICION_CREAR;
import static mx.constructora.compartido.Permisos.USUARIOS_ADMINISTRAR;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Roles y sus permisos, según la tabla "Roles y permisos" del plan técnico. Las claves coinciden
 * con {@code organizacion.rol}; {@code RolTest} lo verifica.
 */
public enum Rol {
  ALMACENISTA("almacenista", "Almacenista", Set.of(MOVIMIENTO_CAPTURAR, EXISTENCIAS_VER)),
  RESIDENTE("residente", "Residente", Set.of(REQUISICION_CREAR, EXISTENCIAS_VER, COSTO_VER_PROPIO)),
  COORDINACION(
      "coordinacion",
      "Coordinación",
      Set.of(REQUISICION_CREAR, DOCUMENTO_AUTORIZAR, EXISTENCIAS_VER, COSTO_VER_TODO)),
  COMPRAS("compras", "Compras", Set.of(PEDIDO_CREAR, EXISTENCIAS_VER, PRECIO_VER)),
  DIRECCION("direccion", "Dirección", Set.of(DOCUMENTO_AUTORIZAR, EXISTENCIAS_VER, COSTO_VER_TODO)),
  ADMIN(
      "admin",
      "Administración",
      Set.of(ADMINISTRAR, USUARIOS_ADMINISTRAR, EXISTENCIAS_VER, COSTO_VER_TODO));

  private final String clave;
  private final String nombre;
  private final Set<String> permisos;

  Rol(String clave, String nombre, Set<String> permisos) {
    this.clave = clave;
    this.nombre = nombre;
    this.permisos = permisos;
  }

  public String clave() {
    return clave;
  }

  public String nombre() {
    return nombre;
  }

  public Set<String> permisos() {
    return permisos;
  }

  /** Autoridad de Spring Security: {@code ROLE_ADMIN}, {@code ROLE_ALMACENISTA}… */
  public String autoridad() {
    return "ROLE_" + name();
  }

  public static Optional<Rol> deClave(String clave) {
    return Arrays.stream(values()).filter(rol -> rol.clave.equals(clave)).findFirst();
  }
}

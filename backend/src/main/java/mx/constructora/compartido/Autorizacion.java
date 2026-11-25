package mx.constructora.compartido;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Permisos del usuario en curso, para decisiones que no son "puede o no puede" sino "qué ve": el
 * residente ve solo sus contratos, quien no ve precios recibe el material sin precio. Para negar
 * una operación completa se usa {@code @PreAuthorize} en el servicio.
 */
public final class Autorizacion {

  private Autorizacion() {}

  /** {@code true} si el usuario autenticado tiene el permiso {@code permiso}. */
  public static boolean tiene(String permiso) {
    Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
    if (autenticacion == null || !autenticacion.isAuthenticated()) {
      return false;
    }
    for (GrantedAuthority autoridad : autenticacion.getAuthorities()) {
      if (permiso.equals(autoridad.getAuthority())) {
        return true;
      }
    }
    return false;
  }

  /** {@code true} si tiene al menos uno de los permisos. */
  public static boolean tieneAlguno(String... permisos) {
    for (String permiso : permisos) {
      if (tiene(permiso)) {
        return true;
      }
    }
    return false;
  }
}

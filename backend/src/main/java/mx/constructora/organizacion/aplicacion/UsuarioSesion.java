package mx.constructora.organizacion.aplicacion;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import mx.constructora.compartido.UsuarioAutenticado;
import mx.constructora.organizacion.Rol;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Usuario en sesión. Se guarda serializado en {@code organizacion.sesion}; el hash de la contraseña
 * se borra en cuanto termina la autenticación.
 */
public final class UsuarioSesion
    implements UserDetails, CredentialsContainer, UsuarioAutenticado, Serializable {

  @Serial private static final long serialVersionUID = 1L;

  private final long usuarioId;
  private final long empresaId;
  private final String empresa;
  private final String usuario;
  private final String nombre;
  private final boolean activo;
  private final Instant bloqueadoHasta;
  private final List<Rol> roles;
  private String hashContrasena;

  public UsuarioSesion(
      long usuarioId,
      long empresaId,
      String empresa,
      String usuario,
      String nombre,
      String hashContrasena,
      boolean activo,
      Instant bloqueadoHasta,
      List<Rol> roles) {
    this.usuarioId = usuarioId;
    this.empresaId = empresaId;
    this.empresa = empresa;
    this.usuario = usuario;
    this.nombre = nombre;
    this.hashContrasena = hashContrasena;
    this.activo = activo;
    this.bloqueadoHasta = bloqueadoHasta;
    this.roles = List.copyOf(roles);
  }

  @Override
  public long usuarioId() {
    return usuarioId;
  }

  @Override
  public long empresaId() {
    return empresaId;
  }

  public String empresa() {
    return empresa;
  }

  public String nombre() {
    return nombre;
  }

  public List<Rol> roles() {
    return roles;
  }

  /** Unión de los permisos de todos sus roles, en orden alfabético. */
  public Set<String> permisos() {
    Set<String> permisos = new TreeSet<>();
    roles.forEach(rol -> permisos.addAll(rol.permisos()));
    return permisos;
  }

  /** Roles como {@code ROLE_*} y cada permiso fino como autoridad propia. */
  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    Set<GrantedAuthority> autoridades = new LinkedHashSet<>();
    roles.forEach(rol -> autoridades.add(new SimpleGrantedAuthority(rol.autoridad())));
    permisos().forEach(permiso -> autoridades.add(new SimpleGrantedAuthority(permiso)));
    return autoridades;
  }

  @Override
  public String getPassword() {
    return hashContrasena;
  }

  @Override
  public String getUsername() {
    return usuario;
  }

  @Override
  public boolean isAccountNonExpired() {
    return true;
  }

  @Override
  public boolean isAccountNonLocked() {
    return bloqueadoHasta == null || bloqueadoHasta.isBefore(Instant.now());
  }

  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }

  @Override
  public boolean isEnabled() {
    return activo;
  }

  @Override
  public void eraseCredentials() {
    hashContrasena = null;
  }

  @Override
  public String toString() {
    return "UsuarioSesion[" + usuario + ", empresa " + empresaId + "]";
  }
}

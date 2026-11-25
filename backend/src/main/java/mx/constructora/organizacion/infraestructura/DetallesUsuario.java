package mx.constructora.organizacion.infraestructura;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/** Fuente de usuarios para Spring Security. */
@Component
class DetallesUsuario implements UserDetailsService {

  private final RepositorioUsuarios usuarios;

  DetallesUsuario(RepositorioUsuarios usuarios) {
    this.usuarios = usuarios;
  }

  @Override
  public UserDetails loadUserByUsername(String usuario) {
    return usuarios
        .paraLogin(usuario)
        .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado"));
  }
}

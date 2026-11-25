package mx.constructora.compartido.seguridad;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Piezas de autenticación que no dependen de que haya servidor web (el modo migrar arranca sin él).
 * El {@link UserDetailsService} lo aporta el módulo {@code organizacion}.
 */
@Configuration(proxyBeanMethods = false)
class ConfiguracionAutenticacion {

  /**
   * Argon2id con los parámetros recomendados por Spring Security. Los hashes se guardan con prefijo
   * {@code {argon2}} para poder cambiar de algoritmo más adelante sin invalidar los existentes.
   */
  @Bean
  PasswordEncoder codificadorContrasenas() {
    String id = "argon2";
    return new DelegatingPasswordEncoder(
        id, Map.of(id, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
  }

  @Bean
  AuthenticationManager administradorAutenticacion(
      UserDetailsService usuarios, PasswordEncoder codificador) {
    var proveedor = new DaoAuthenticationProvider(usuarios);
    proveedor.setPasswordEncoder(codificador);
    var administrador = new ProviderManager(proveedor);
    administrador.setEraseCredentialsAfterAuthentication(true);
    return administrador;
  }

  /**
   * Dónde se guarda el usuario autenticado: en la sesión HTTP, que Spring Session lleva a la base.
   */
  @Bean
  SecurityContextRepository repositorioContextoSeguridad() {
    return new HttpSessionSecurityContextRepository();
  }
}

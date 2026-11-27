package mx.constructora.compartido.seguridad;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Reglas HTTP. Sesión en cookie (Spring Session JDBC), CSRF con el patrón para aplicaciones de una
 * sola página: el frontend lee la cookie {@code XSRF-TOKEN} y la devuelve en el encabezado {@code
 * X-XSRF-TOKEN}. La API responde 401 y 403 en JSON, nunca redirige a una página de login.
 *
 * <p>Los permisos finos se revisan en cada servicio con {@code @PreAuthorize}.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ConfiguracionSeguridad {

  @Bean
  SecurityFilterChain cadenaSeguridad(HttpSecurity http, SecurityContextRepository repositorio)
      throws Exception {
    http.csrf(csrf -> csrf.spa())
        .securityContext(contexto -> contexto.securityContextRepository(repositorio))
        .authorizeHttpRequests(
            reglas ->
                reglas
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                    .permitAll()
                    // Segundo paso del login: sin sesión autenticada todavía. El controlador exige
                    // el inicio de sesión pendiente que dejó la contraseña correcta.
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/segundo-factor",
                        "/api/v1/auth/segundo-factor/alta",
                        "/api/v1/auth/segundo-factor/alta/confirmar")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/version")
                    .permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .hasRole("ADMIN")
                    // Solo existe donde springdoc.api-docs.enabled=true (local y pruebas).
                    .requestMatchers("/v3/api-docs", "/v3/api-docs/**")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    // El resto es el frontend (archivos estáticos y rutas de la SPA).
                    .anyRequest()
                    .permitAll())
        .exceptionHandling(
            errores ->
                errores.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        // Una API no guarda la petición rechazada para "volver" después del login: así un 401
        // anónimo no crea una sesión en la base.
        .requestCache(cache -> cache.disable())
        .formLogin(formulario -> formulario.disable())
        .httpBasic(basica -> basica.disable())
        .logout(
            salida ->
                salida
                    .logoutUrl("/api/v1/auth/logout")
                    .logoutSuccessHandler(
                        new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
        .sessionManagement(sesion -> sesion.sessionFixation(fijacion -> fijacion.changeSessionId()))
        .addFilterAfter(new FiltroContextoLog(), AnonymousAuthenticationFilter.class);
    return http.build();
  }
}

package mx.constructora.organizacion.aplicacion;

import mx.constructora.organizacion.infraestructura.RepositorioUsuarios;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * En una base sin usuarios, crea la primera empresa y su administrador con {@code app.inicial.*}
 * (variables {@code APP_INICIAL_*}). En cuanto existe un usuario, no hace nada: después de
 * arrancar, conviene quitar la contraseña inicial del {@code .env} y cambiarla desde el sistema.
 */
@Component
@Profile("!migrar")
class InicializadorEmpresa implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(InicializadorEmpresa.class);

  private final PropiedadesInicial propiedades;
  private final RepositorioUsuarios usuarios;
  private final PasswordEncoder codificador;

  InicializadorEmpresa(
      PropiedadesInicial propiedades, RepositorioUsuarios usuarios, PasswordEncoder codificador) {
    this.propiedades = propiedades;
    this.usuarios = usuarios;
    this.codificador = codificador;
  }

  @Override
  public void run(ApplicationArguments argumentos) {
    if (!StringUtils.hasText(propiedades.usuario())
        || !StringUtils.hasText(propiedades.contrasena())) {
      return;
    }
    if (propiedades.contrasena().length() < ServicioUsuarios.CONTRASENA_MINIMA) {
      throw new IllegalStateException(
          "APP_INICIAL_CONTRASENA debe tener al menos "
              + ServicioUsuarios.CONTRASENA_MINIMA
              + " caracteres");
    }
    boolean creada =
        usuarios.inicializar(
            StringUtils.hasText(propiedades.empresa()) ? propiedades.empresa() : "Mi empresa",
            propiedades.rfc(),
            propiedades.usuario(),
            StringUtils.hasText(propiedades.nombre()) ? propiedades.nombre() : "Administrador",
            codificador.encode(propiedades.contrasena()));
    if (creada) {
      LOG.info(
          "Instalación nueva: se creó la empresa inicial y el usuario {}", propiedades.usuario());
    }
  }
}

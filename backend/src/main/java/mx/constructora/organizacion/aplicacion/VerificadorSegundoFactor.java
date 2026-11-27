package mx.constructora.organizacion.aplicacion;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Si el segundo factor es obligatorio, la app no arranca sin una llave válida: mejor una versión
 * que no pasa la revisión de salud (y {@code desplegar.sh} regresa a la anterior) que una que deja
 * a administración sin poder entrar. No corre en el paso de migraciones.
 */
@Component
@Profile("!migrar")
class VerificadorSegundoFactor implements ApplicationRunner {

  private final PropiedadesSegundoFactor propiedades;

  VerificadorSegundoFactor(PropiedadesSegundoFactor propiedades) {
    this.propiedades = propiedades;
  }

  @Override
  public void run(ApplicationArguments argumentos) {
    if (!propiedades.exigir()) {
      return;
    }
    try {
      CifradoSecreto.desde(propiedades.llave());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          e.getMessage()
              + ". El segundo factor es obligatorio (APP_SEGUNDO_FACTOR_EXIGIR=true): agrega la"
              + " llave al .env del servidor (docs/runbooks/segundo-factor.md).",
          e);
    }
  }
}

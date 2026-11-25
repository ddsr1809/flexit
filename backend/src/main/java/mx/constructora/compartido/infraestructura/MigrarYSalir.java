package mx.constructora.compartido.infraestructura;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Perfil {@code migrar}: la misma imagen de la app aplica las migraciones con el rol migrador y
 * termina. Lo corre {@code desplegar.sh} antes de levantar la versión nueva. Las migraciones ya
 * corrieron en {@link Migraciones}; aquí solo se cierra el proceso con código 0.
 */
@Component
@Profile("migrar")
class MigrarYSalir implements ApplicationRunner {

  private final ApplicationContext contexto;

  MigrarYSalir(ApplicationContext contexto) {
    this.contexto = contexto;
  }

  @Override
  public void run(ApplicationArguments argumentos) {
    System.exit(SpringApplication.exit(contexto, () -> 0));
  }
}

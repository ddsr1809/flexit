package mx.constructora;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Monolito modular. Cada paquete directo bajo {@code mx.constructora} es un módulo con sus
 * dependencias permitidas declaradas en su {@code package-info.java}; {@code ModularidadTest} las
 * verifica en cada corrida de pruebas.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ConstructoraApplication {

  public static void main(String[] args) {
    SpringApplication.run(ConstructoraApplication.class, args);
  }
}

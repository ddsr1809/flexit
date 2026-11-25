package mx.constructora;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Falla si un módulo usa a otro que no tiene en {@code allowedDependencies}, si hay ciclos entre
 * módulos o si alguien usa un tipo interno (subpaquete) de otro módulo. Es la regla 7 de CLAUDE.md.
 */
class ModularidadTest {

  @Test
  void lasFronterasEntreModulosSeRespetan() {
    ApplicationModules.of(ConstructoraApplication.class).verify();
  }
}

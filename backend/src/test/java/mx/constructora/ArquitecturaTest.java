package mx.constructora;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/** Reglas de capas que Spring Modulith no revisa. */
class ArquitecturaTest {

  private static JavaClasses clases;

  @BeforeAll
  static void importar() {
    clases =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("mx.constructora");
  }

  @Test
  void losControladoresNoAbrenTransacciones() {
    noClasses()
        .that()
        .areAnnotatedWith(RestController.class)
        .should()
        .beAnnotatedWith(Transactional.class)
        .because("una transacción es un caso de uso y vive en el servicio")
        .check(clases);
    noMethods()
        .that()
        .areDeclaredInClassesThat()
        .areAnnotatedWith(RestController.class)
        .should()
        .beAnnotatedWith(Transactional.class)
        .because("una transacción es un caso de uso y vive en el servicio")
        .check(clases);
  }

  @Test
  void losControladoresVivenEnWeb() {
    noClasses()
        .that()
        .areAnnotatedWith(RestController.class)
        .should()
        .resideOutsideOfPackage("..web..")
        .check(clases);
  }

  @Test
  void nadieUsaDoubleNiFloatParaDinero() {
    noClasses()
        .that()
        .resideInAPackage("mx.constructora..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.lang.Double")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.lang.Float")
        .because("dinero y cantidades van en BigDecimal (regla 6 de CLAUDE.md)")
        .check(clases);
  }
}

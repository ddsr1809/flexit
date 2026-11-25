package mx.constructora.compartido.infraestructura;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Corre Flyway al arrancar, antes que cualquier otro componente use la base. Ver {@link
 * PropiedadesMigraciones} para los modos.
 */
@Component
class Migraciones implements InitializingBean {

  private static final Logger LOG = LoggerFactory.getLogger(Migraciones.class);

  private final DataSource dataSource;
  private final PropiedadesMigraciones propiedades;
  private final Environment entorno;

  Migraciones(DataSource dataSource, PropiedadesMigraciones propiedades, Environment entorno) {
    this.dataSource = dataSource;
    this.propiedades = propiedades;
    this.entorno = entorno;
  }

  @Override
  public void afterPropertiesSet() {
    switch (propiedades.modo()) {
      case MIGRAR -> migrar();
      case VALIDAR -> validar();
      case NINGUNO -> LOG.warn("Migraciones desactivadas (app.migraciones.modo=ninguno)");
    }
  }

  private void migrar() {
    String url =
        StringUtils.hasText(propiedades.url())
            ? propiedades.url()
            : entorno.getRequiredProperty("spring.datasource.url");
    if (!StringUtils.hasText(propiedades.usuario())) {
      throw new IllegalStateException("app.migraciones.usuario es obligatorio en modo migrar");
    }
    var resultado =
        base().dataSource(url, propiedades.usuario(), propiedades.contrasena()).load().migrate();
    LOG.info(
        "Migraciones aplicadas: {} (esquema en la versión {})",
        resultado.migrationsExecuted,
        resultado.targetSchemaVersion);
  }

  private void validar() {
    ValidateResult resultado = base().dataSource(dataSource).load().validateWithResult();
    if (!resultado.validationSuccessful) {
      throw new IllegalStateException(
          "La base no está al día con las migraciones de esta versión. Corre el paso 'migrar' antes"
              + " de arrancar: "
              + resultado.getAllErrorMessages());
    }
    LOG.info("Esquema validado: todas las migraciones están aplicadas");
  }

  private static FluentConfiguration base() {
    return Flyway.configure()
        .locations("classpath:db/migration")
        .validateMigrationNaming(true)
        .cleanDisabled(true);
  }
}

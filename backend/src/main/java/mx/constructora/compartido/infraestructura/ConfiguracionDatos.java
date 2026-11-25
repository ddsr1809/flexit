package mx.constructora.compartido.infraestructura;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Conecta la seguridad por empresa con el acceso a datos. */
@Configuration(proxyBeanMethods = false)
class ConfiguracionDatos {

  /**
   * Toda {@link DataSource} de la aplicación se envuelve en {@link DataSourceConEmpresa}. Flyway en
   * modo migrar usa su propia conexión como migrador y no pasa por aquí.
   */
  @Bean
  static BeanPostProcessor envolverDataSourceConEmpresa() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String nombre) {
        if (bean instanceof DataSource fuente && !(bean instanceof DataSourceConEmpresa)) {
          return new DataSourceConEmpresa(fuente);
        }
        return bean;
      }
    };
  }
}

package mx.constructora.compartido.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Datos generales del contrato OpenAPI. El frontend genera sus tipos desde {@code /v3/api-docs}; la
 * prueba {@code ContratoApiTest} lo escribe en {@code target/openapi.json} para CI.
 */
@Configuration(proxyBeanMethods = false)
class ConfiguracionOpenApi {

  @Bean
  OpenAPI contrato() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Sistema para constructora")
                .version("v1")
                .description("API de inventario, costo y abastecimiento"));
  }
}

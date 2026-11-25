package mx.constructora.compartido.web;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Sirve el frontend construido desde {@code classpath:/static}. Una ruta que no es archivo ni API
 * (por ejemplo {@code /usuarios}) devuelve {@code index.html} para que la resuelva el enrutador de
 * React. Requiere {@code spring.web.resources.add-mappings=false}.
 */
@Configuration(proxyBeanMethods = false)
class ConfiguracionSpa implements WebMvcConfigurer {

  private static final Resource INDICE = new ClassPathResource("/static/index.html");

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registro) {
    registro
        .addResourceHandler("/**")
        .addResourceLocations("classpath:/static/")
        .resourceChain(true)
        .addResolver(
            new PathResourceResolver() {
              @Override
              protected Resource getResource(String ruta, Resource ubicacion) throws IOException {
                Resource archivo = ubicacion.createRelative(ruta);
                if (archivo.exists() && archivo.isReadable()) {
                  return archivo;
                }
                if (esReservada(ruta) || ruta.contains(".")) {
                  return null;
                }
                return INDICE.exists() ? INDICE : null;
              }
            });
  }

  private static boolean esReservada(String ruta) {
    return ruta.startsWith("api/") || ruta.startsWith("actuator") || ruta.startsWith("v3/");
  }
}

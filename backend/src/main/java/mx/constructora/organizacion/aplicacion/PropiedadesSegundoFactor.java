package mx.constructora.organizacion.aplicacion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuración del segundo factor, desde variables de entorno:
 *
 * <ul>
 *   <li>{@code APP_LLAVE_SEGUNDO_FACTOR}: 32 bytes en Base64 ({@code openssl rand -base64 32}) que
 *       cifran los secretos. Si se pierde, cada usuario vuelve a dar de alta su segundo factor.
 *   <li>{@code APP_SEGUNDO_FACTOR_EXIGIR}: {@code true} (producción y preproducción) obliga a
 *       administración y dirección a usarlo. Las pruebas y el entorno local lo apagan.
 *   <li>{@code APP_SEGUNDO_FACTOR_EMISOR}: el nombre que aparece en la app del teléfono.
 * </ul>
 */
@Component
public class PropiedadesSegundoFactor {

  private final boolean exigir;
  private final String llave;
  private final String emisor;

  PropiedadesSegundoFactor(
      @Value("${app.segundo-factor.exigir:${APP_SEGUNDO_FACTOR_EXIGIR:true}}") boolean exigir,
      @Value("${app.segundo-factor.llave:${APP_LLAVE_SEGUNDO_FACTOR:}}") String llave,
      @Value("${app.segundo-factor.emisor:${APP_SEGUNDO_FACTOR_EMISOR:Constructora}}")
          String emisor) {
    this.exigir = exigir;
    this.llave = llave;
    this.emisor = emisor;
  }

  public boolean exigir() {
    return exigir;
  }

  String llave() {
    return llave;
  }

  String emisor() {
    return emisor;
  }
}

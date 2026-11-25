package mx.constructora.compartido.infraestructura;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cómo trata la app el esquema al arrancar.
 *
 * <ul>
 *   <li>{@code validar} (producción y preproducción): revisa que la base tenga todas las
 *       migraciones; si falta alguna, no arranca. Usa la conexión de la app, que no tiene DDL.
 *   <li>{@code migrar}: aplica las migraciones pendientes con el rol migrador ({@code url}, {@code
 *       usuario}, {@code contrasena}). Lo usan el paso previo al despliegue, el entorno local y las
 *       pruebas.
 *   <li>{@code ninguno}: no toca Flyway.
 * </ul>
 */
@ConfigurationProperties("app.migraciones")
public record PropiedadesMigraciones(Modo modo, String url, String usuario, String contrasena) {

  public enum Modo {
    MIGRAR,
    VALIDAR,
    NINGUNO
  }

  public PropiedadesMigraciones {
    if (modo == null) {
      modo = Modo.VALIDAR;
    }
  }
}

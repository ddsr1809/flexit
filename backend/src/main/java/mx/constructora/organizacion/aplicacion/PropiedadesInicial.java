package mx.constructora.organizacion.aplicacion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Primera empresa y su administrador ({@code APP_INICIAL_*}). Ver {@link InicializadorEmpresa}. */
@ConfigurationProperties("app.inicial")
public record PropiedadesInicial(
    String empresa, String rfc, String usuario, String nombre, String contrasena) {}

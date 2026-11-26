package mx.constructora;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Navegador mínimo para las pruebas: guarda cookies (sesión y {@code XSRF-TOKEN}) y manda el
 * encabezado {@code X-XSRF-TOKEN} en cada petición que modifica, igual que el frontend.
 */
public final class ClienteHttp {

  /** Respuesta con el cuerpo como texto y lectura por JsonPath. */
  public record Respuesta(int estado, String cuerpo) {
    public <T> T json(String ruta) {
      return JsonPath.read(cuerpo, ruta);
    }
  }

  private final HttpClient http = HttpClient.newHttpClient();
  private final String base;
  private final Map<String, String> cookies = new LinkedHashMap<>();
  private boolean enviarCsrf = true;

  ClienteHttp(String base) {
    this.base = base;
  }

  /** Para probar que el servidor rechaza peticiones sin token. */
  public ClienteHttp sinCsrf() {
    enviarCsrf = false;
    return this;
  }

  public Respuesta get(String ruta) {
    return enviar(peticion(ruta).GET(), false);
  }

  public Respuesta post(String ruta, String json) {
    return enviar(
        peticion(ruta)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json)),
        true);
  }

  public Respuesta put(String ruta, String json) {
    return enviar(
        peticion(ruta)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json)),
        true);
  }

  public Respuesta delete(String ruta) {
    return enviar(peticion(ruta).DELETE(), true);
  }

  /** POST multipart/form-data con campos de texto y un archivo en el campo {@code archivo}. */
  public Respuesta subir(String ruta, Map<String, String> campos, String nombre, byte[] archivo) {
    String limite = "----limite" + System.nanoTime();
    java.io.ByteArrayOutputStream cuerpo = new java.io.ByteArrayOutputStream();
    java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
    for (Map.Entry<String, String> campo : campos.entrySet()) {
      cuerpo.writeBytes(
          ("--"
                  + limite
                  + "\r\nContent-Disposition: form-data; name=\""
                  + campo.getKey()
                  + "\"\r\n\r\n"
                  + campo.getValue()
                  + "\r\n")
              .getBytes(utf8));
    }
    cuerpo.writeBytes(
        ("--"
                + limite
                + "\r\nContent-Disposition: form-data; name=\"archivo\"; filename=\""
                + nombre
                + "\"\r\nContent-Type: application/vnd.openxmlformats-officedocument"
                + ".spreadsheetml.sheet\r\n\r\n")
            .getBytes(utf8));
    cuerpo.writeBytes(archivo);
    cuerpo.writeBytes(("\r\n--" + limite + "--\r\n").getBytes(utf8));
    return enviar(
        peticion(ruta)
            .header("Content-Type", "multipart/form-data; boundary=" + limite)
            .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray())),
        true);
  }

  /** PATCH con la versión leída en {@code If-Match}; {@code null} lo manda sin encabezado. */
  public Respuesta patch(String ruta, String json, Integer version) {
    HttpRequest.Builder constructor =
        peticion(ruta)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(json));
    if (version != null) {
      constructor.header("If-Match", "\"" + version + "\"");
    }
    return enviar(constructor, true);
  }

  /** Pide el token CSRF e inicia sesión. */
  public Respuesta iniciarSesion(String usuario, String contrasena) {
    get("/api/v1/auth/csrf");
    return post(
        "/api/v1/auth/login",
        "{\"usuario\":\"%s\",\"contrasena\":\"%s\"}".formatted(usuario, contrasena));
  }

  public boolean tieneCookie(String nombre) {
    return cookies.containsKey(nombre);
  }

  private HttpRequest.Builder peticion(String ruta) {
    HttpRequest.Builder constructor =
        HttpRequest.newBuilder(URI.create(base + ruta)).header("Accept", "application/json");
    if (!cookies.isEmpty()) {
      constructor.header(
          "Cookie",
          cookies.entrySet().stream()
              .map(c -> c.getKey() + "=" + c.getValue())
              .collect(Collectors.joining("; ")));
    }
    return constructor;
  }

  private Respuesta enviar(HttpRequest.Builder constructor, boolean modifica) {
    if (modifica && enviarCsrf && cookies.containsKey("XSRF-TOKEN")) {
      constructor.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
    }
    try {
      HttpResponse<String> respuesta =
          http.send(constructor.build(), HttpResponse.BodyHandlers.ofString());
      respuesta.headers().allValues("set-cookie").forEach(this::guardarCookie);
      return new Respuesta(respuesta.statusCode(), respuesta.body());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private void guardarCookie(String encabezado) {
    String[] partes = encabezado.split(";");
    String[] nombreValor = partes[0].split("=", 2);
    String nombre = nombreValor[0].trim();
    String valor = nombreValor.length > 1 ? nombreValor[1].trim() : "";
    boolean borrada = valor.isEmpty();
    for (int i = 1; i < partes.length; i++) {
      if (partes[i].trim().equalsIgnoreCase("Max-Age=0")) {
        borrada = true;
      }
    }
    if (borrada) {
      cookies.remove(nombre);
    } else {
      cookies.put(nombre, valor);
    }
  }
}

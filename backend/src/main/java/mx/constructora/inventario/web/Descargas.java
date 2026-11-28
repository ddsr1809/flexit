package mx.constructora.inventario.web;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import mx.constructora.compartido.Folios;
import mx.constructora.compartido.LibroExcel;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Respuesta de descarga de un .xlsx, sin caché. */
final class Descargas {

  private Descargas() {}

  static ResponseEntity<byte[]> xlsx(byte[] contenido, String nombre) {
    String limpio =
        nombre.replaceAll("[^A-Za-z0-9._-]+", "-") + "-" + LocalDate.now(Folios.ZONA) + ".xlsx";
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(LibroExcel.TIPO))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(limpio, StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(contenido);
  }
}

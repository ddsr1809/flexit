package mx.constructora.compartido.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import mx.constructora.compartido.Almacenamiento;
import mx.constructora.compartido.Almacenamiento.Archivo;
import mx.constructora.compartido.Almacenamiento.Contenido;
import mx.constructora.compartido.ErrorNegocio;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Subir y ver evidencia (fotos de merma). Cualquier usuario con sesión, dentro de su empresa. */
@RestController
@RequestMapping("/api/v1/archivos")
@Tag(name = "Archivos")
class ArchivosControlador {

  private final Almacenamiento almacenamiento;

  ArchivosControlador(Almacenamiento almacenamiento) {
    this.almacenamiento = almacenamiento;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      operationId = "subirArchivo",
      summary = "Subir una foto (JPEG, PNG, WebP) o PDF de hasta 10 MB")
  Archivo subir(@RequestParam("archivo") MultipartFile archivo) throws IOException {
    return almacenamiento.guardar(archivo.getOriginalFilename(), archivo.getBytes());
  }

  @GetMapping("/{id}")
  @Operation(operationId = "verArchivo", summary = "Contenido de un archivo")
  @ApiResponse(
      responseCode = "200",
      description = "El archivo con su tipo: JPEG, PNG, WebP o PDF",
      content = @Content(mediaType = "*/*", schema = @Schema(type = "string", format = "binary")))
  ResponseEntity<byte[]> ver(@PathVariable long id) {
    Contenido contenido =
        almacenamiento
            .leer(id)
            .orElseThrow(
                () -> ErrorNegocio.noEncontrado("ARCHIVO_NO_ENCONTRADO", "No existe el archivo"));
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(contenido.tipo()))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.inline()
                .filename(contenido.nombre(), java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("X-Content-Type-Options", "nosniff")
        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
        .body(contenido.bytes());
  }
}

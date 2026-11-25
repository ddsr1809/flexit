package mx.constructora.compartido.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Qué versión está corriendo. Sirve para comprobar que un cambio fusionado llegó al entorno. */
@RestController
@RequestMapping("/api/v1/version")
@Tag(name = "Sistema")
class VersionControlador {

  /** Versión desplegada (SHA del commit) y entorno. */
  record VersionDto(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String version,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String entorno) {}

  private final VersionDto version;

  VersionControlador(
      @Value("${app.version:local}") String version,
      @Value("${app.entorno:local}") String entorno) {
    this.version = new VersionDto(version, entorno);
  }

  @GetMapping
  @Operation(operationId = "version", summary = "Versión y entorno")
  VersionDto version() {
    return version;
  }
}

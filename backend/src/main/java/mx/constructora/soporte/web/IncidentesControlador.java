package mx.constructora.soporte.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import mx.constructora.compartido.Versiones;
import mx.constructora.soporte.aplicacion.ServicioIncidentes;
import mx.constructora.soporte.aplicacion.ServicioIncidentes.ClasificacionIncidente;
import mx.constructora.soporte.aplicacion.ServicioIncidentes.DatosIncidente;
import mx.constructora.soporte.aplicacion.ServicioIncidentes.Incidente;
import mx.constructora.soporte.aplicacion.ServicioIncidentes.IncidenteResumen;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Incidentes del piloto: cualquiera reporta; administración atiende. */
@RestController
@RequestMapping("/api/v1/incidentes")
@Tag(name = "Soporte")
class IncidentesControlador {

  /** Qué se hizo, por qué se descarta, por qué se reabre o lo que se sabe. */
  record TextoIncidente(@NotBlank @Size(max = 1000) String texto) {}

  private final ServicioIncidentes servicio;

  IncidentesControlador(ServicioIncidentes servicio) {
    this.servicio = servicio;
  }

  @GetMapping
  @Operation(
      operationId = "listarIncidentes",
      summary = "Incidentes (quien atiende ve todos; los demás, los que reportaron)")
  List<IncidenteResumen> incidentes(
      @RequestParam(required = false) String estado,
      @RequestParam(required = false) String severidad,
      @RequestParam(required = false) Long almacenId) {
    return servicio.incidentes(estado, severidad, almacenId);
  }

  @GetMapping("/{id}")
  @Operation(operationId = "obtenerIncidente", summary = "Un incidente con su seguimiento (ETag)")
  ResponseEntity<Incidente> incidente(@PathVariable long id) {
    Incidente incidente = servicio.incidente(id);
    return ResponseEntity.ok().eTag(Versiones.etag(incidente.version())).body(incidente);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      operationId = "reportarIncidente",
      summary = "Reportar un problema (cualquier usuario)")
  Incidente reportar(@Valid @RequestBody DatosIncidente datos) {
    return servicio.reportar(datos);
  }

  @PatchMapping("/{id}")
  @Operation(
      operationId = "clasificarIncidente",
      summary = "Cambiar severidad e issue de GitHub (quien atiende; If-Match)")
  Incidente clasificar(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody ClasificacionIncidente datos) {
    return servicio.clasificar(id, Versiones.deEncabezado(siCoincide), datos);
  }

  @PostMapping("/{id}/resolver")
  @Operation(operationId = "resolverIncidente", summary = "Resuelto: qué se hizo (quien atiende)")
  Incidente resolver(@PathVariable long id, @Valid @RequestBody TextoIncidente texto) {
    return servicio.resolver(id, texto.texto());
  }

  @PostMapping("/{id}/descartar")
  @Operation(
      operationId = "descartarIncidente",
      summary = "No era un error o está repetido: por qué (quien atiende)")
  Incidente descartar(@PathVariable long id, @Valid @RequestBody TextoIncidente texto) {
    return servicio.descartar(id, texto.texto());
  }

  @PostMapping("/{id}/reabrir")
  @Operation(operationId = "reabrirIncidente", summary = "Sigue pasando: por qué se reabre")
  Incidente reabrir(@PathVariable long id, @Valid @RequestBody TextoIncidente texto) {
    return servicio.reabrir(id, texto.texto());
  }

  @PostMapping("/{id}/notas")
  @Operation(operationId = "anotarIncidente", summary = "Agregar una nota al seguimiento")
  Incidente anotar(@PathVariable long id, @Valid @RequestBody TextoIncidente texto) {
    return servicio.anotar(id, texto.texto());
  }
}

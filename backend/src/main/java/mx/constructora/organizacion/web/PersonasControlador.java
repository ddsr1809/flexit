package mx.constructora.organizacion.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import mx.constructora.compartido.Versiones;
import mx.constructora.organizacion.Personas.PersonaNombre;
import mx.constructora.organizacion.aplicacion.ServicioPersonas;
import mx.constructora.organizacion.aplicacion.ServicioPersonas.DatosPersona;
import mx.constructora.organizacion.aplicacion.ServicioPersonas.Persona;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Personas (datos personales): requiere {@code personas:administrar}, salvo la búsqueda de nombres
 * para capturar en el almacén.
 */
@RestController
@RequestMapping("/api/v1/personas")
@Tag(name = "Personas")
class PersonasControlador {

  private final ServicioPersonas servicio;

  PersonasControlador(ServicioPersonas servicio) {
    this.servicio = servicio;
  }

  @GetMapping
  @Operation(
      operationId = "buscarPersonas",
      summary = "Personas por nombre, NSS o CURP (200 como máximo)")
  List<Persona> personas(
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "false") boolean soloRevisar) {
    return servicio.buscar(q, soloRevisar);
  }

  @GetMapping("/nombres")
  @Operation(
      operationId = "buscarNombresPersonas",
      summary = "Nombres de personas activas para elegir solicitante o responsable (20)")
  List<PersonaNombre> nombres(@RequestParam String q) {
    return servicio.nombres(q);
  }

  @GetMapping("/{id}")
  @Operation(operationId = "obtenerPersona", summary = "Una persona (ETag con su versión)")
  ResponseEntity<Persona> persona(@PathVariable long id) {
    Persona persona = servicio.persona(id);
    return ResponseEntity.ok().eTag(Versiones.etag(persona.version())).body(persona);
  }

  @PatchMapping("/{id}")
  @Operation(operationId = "cambiarPersona", summary = "Corregir nombre, NSS, CURP o notas")
  Persona cambiar(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody DatosPersona datos) {
    return servicio.cambiar(id, Versiones.deEncabezado(siCoincide), datos);
  }
}

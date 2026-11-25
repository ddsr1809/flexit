package mx.constructora.estructura.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import mx.constructora.compartido.Versiones;
import mx.constructora.estructura.aplicacion.ServicioPaquetes;
import mx.constructora.estructura.aplicacion.ServicioPaquetes.DatosPaquete;
import mx.constructora.estructura.aplicacion.ServicioPaquetes.Equivalencia;
import mx.constructora.estructura.aplicacion.ServicioPaquetes.Equivalencias;
import mx.constructora.estructura.aplicacion.ServicioPaquetes.Paquete;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Catálogos de paquetes (del cliente y propio) y su equivalencia. */
@RestController
@RequestMapping("/api/v1/paquetes")
@Tag(name = "Paquetes")
class PaquetesControlador {

  private final ServicioPaquetes servicio;

  PaquetesControlador(ServicioPaquetes servicio) {
    this.servicio = servicio;
  }

  @GetMapping
  @Operation(
      operationId = "listarPaquetes",
      summary = "Paquetes en orden de árbol; filtra por cliente o solo los propios")
  List<Paquete> paquetes(
      @RequestParam(required = false) Long clienteId,
      @RequestParam(defaultValue = "false") boolean propios) {
    return servicio.paquetes(clienteId, propios);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "crearPaquete", summary = "Dar de alta un paquete")
  Paquete crear(@Valid @RequestBody DatosPaquete datos) {
    return servicio.crear(datos);
  }

  @PatchMapping("/{id}")
  @Operation(operationId = "cambiarPaquete", summary = "Cambiar un paquete")
  Paquete cambiar(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody DatosPaquete datos) {
    return servicio.cambiar(id, Versiones.deEncabezado(siCoincide), datos);
  }

  @GetMapping("/equivalencias")
  @Operation(operationId = "listarEquivalencias", summary = "Equivalencias cliente ↔ propio")
  List<Equivalencia> equivalencias(@RequestParam(required = false) Long clienteId) {
    return servicio.equivalencias(clienteId);
  }

  @PutMapping("/{id}/equivalencias")
  @Operation(
      operationId = "fijarEquivalencias",
      summary = "Reemplazar los paquetes propios equivalentes a un paquete del cliente")
  List<Equivalencia> fijarEquivalencias(
      @PathVariable long id, @Valid @RequestBody Equivalencias datos) {
    return servicio.fijarEquivalencias(id, datos);
  }
}

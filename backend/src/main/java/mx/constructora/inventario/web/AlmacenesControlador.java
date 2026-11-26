package mx.constructora.inventario.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import mx.constructora.compartido.Versiones;
import mx.constructora.inventario.aplicacion.ServicioAlmacenes;
import mx.constructora.inventario.aplicacion.ServicioAlmacenes.Almacen;
import mx.constructora.inventario.aplicacion.ServicioAlmacenes.DatosAlmacen;
import mx.constructora.inventario.aplicacion.ServicioExistencias;
import mx.constructora.inventario.aplicacion.ServicioExistencias.Existencia;
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

/** Almacenes y sus existencias. */
@RestController
@RequestMapping("/api/v1/almacenes")
@Tag(name = "Inventario")
class AlmacenesControlador {

  private final ServicioAlmacenes almacenes;
  private final ServicioExistencias existencias;

  AlmacenesControlador(ServicioAlmacenes almacenes, ServicioExistencias existencias) {
    this.almacenes = almacenes;
    this.existencias = existencias;
  }

  @GetMapping
  @Operation(operationId = "listarAlmacenes", summary = "Almacenes (uno por frente)")
  List<Almacen> almacenes() {
    return almacenes.almacenes();
  }

  @GetMapping("/{id}")
  @Operation(operationId = "obtenerAlmacen", summary = "Un almacén (ETag con su versión)")
  ResponseEntity<Almacen> almacen(@PathVariable long id) {
    Almacen almacen = almacenes.almacen(id);
    return ResponseEntity.ok().eTag(Versiones.etag(almacen.version())).body(almacen);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "crearAlmacen", summary = "Dar de alta el almacén de un frente")
  Almacen crear(@Valid @RequestBody DatosAlmacen datos) {
    return almacenes.crear(datos);
  }

  @PatchMapping("/{id}")
  @Operation(operationId = "cambiarAlmacen", summary = "Cambiar clave, nombre o si está activo")
  Almacen cambiar(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody DatosAlmacen datos) {
    return almacenes.cambiar(id, Versiones.deEncabezado(siCoincide), datos);
  }

  @GetMapping("/{id}/existencias")
  @Operation(
      operationId = "existenciasDeAlmacen",
      summary = "Existencia, apartado y disponible por material (costo solo con permiso)")
  List<Existencia> existencias(
      @PathVariable long id,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "true") boolean soloConExistencia) {
    return existencias.existencias(id, q, soloConExistencia);
  }
}

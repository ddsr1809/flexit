package mx.constructora.inventario.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import mx.constructora.inventario.aplicacion.ServicioConteos;
import mx.constructora.inventario.aplicacion.ServicioConteos.CapturaConteo;
import mx.constructora.inventario.aplicacion.ServicioConteos.Conteo;
import mx.constructora.inventario.aplicacion.ServicioConteos.DatosConteo;
import mx.constructora.inventario.aplicacion.ServicioConteos.DetalleConteo;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Conteo físico: abrir, capturar, cerrar (genera los ajustes) o cancelar. */
@RestController
@RequestMapping("/api/v1/conteos")
@Tag(name = "Inventario")
class ConteosControlador {

  private final ServicioConteos servicio;

  ConteosControlador(ServicioConteos servicio) {
    this.servicio = servicio;
  }

  @GetMapping
  @Operation(operationId = "listarConteos", summary = "Conteos físicos, abiertos primero")
  List<Conteo> conteos(@RequestParam(required = false) Long almacenId) {
    return servicio.conteos(almacenId);
  }

  @GetMapping("/{id}")
  @Operation(operationId = "obtenerConteo", summary = "Un conteo con sus líneas")
  DetalleConteo conteo(@PathVariable long id) {
    return servicio.conteo(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      operationId = "abrirConteo",
      summary = "Abrir un conteo (el almacén deja de aplicar movimientos)")
  DetalleConteo abrir(@Valid @RequestBody DatosConteo datos) {
    return servicio.abrir(datos);
  }

  @PutMapping("/{id}/lineas")
  @Operation(
      operationId = "capturarConteo",
      summary = "Capturar lo contado (agrega materiales si faltan)")
  DetalleConteo capturar(@PathVariable long id, @Valid @RequestBody CapturaConteo captura) {
    return servicio.capturar(id, captura);
  }

  @PostMapping("/{id}/cerrar")
  @Operation(
      operationId = "cerrarConteo",
      summary = "Cerrar y aplicar los ajustes (administración)")
  DetalleConteo cerrar(@PathVariable long id) {
    return servicio.cerrar(id);
  }

  @PostMapping("/{id}/cancelar")
  @Operation(operationId = "cancelarConteo", summary = "Cancelar sin ajustes")
  DetalleConteo cancelar(@PathVariable long id) {
    return servicio.cancelar(id);
  }
}

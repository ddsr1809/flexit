package mx.constructora.inventario.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import mx.constructora.compartido.LibroExcel;
import mx.constructora.compartido.Versiones;
import mx.constructora.inventario.aplicacion.ServicioAperturas;
import mx.constructora.inventario.aplicacion.ServicioAperturas.Apertura;
import mx.constructora.inventario.aplicacion.ServicioAperturas.Cancelacion;
import mx.constructora.inventario.aplicacion.ServicioAperturas.CapturaCostos;
import mx.constructora.inventario.aplicacion.ServicioAperturas.DatosApertura;
import mx.constructora.inventario.aplicacion.ServicioAperturas.DatosPrestamoApertura;
import mx.constructora.inventario.aplicacion.ServicioAperturas.DetalleApertura;
import mx.constructora.inventario.aplicacion.ServicioAperturas.Firma;
import mx.constructora.inventario.aplicacion.ServicioAperturas.Herramienta;
import mx.constructora.inventario.aplicacion.ServicioAperturas.NuevoPrestamoApertura;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Apertura de un almacén en el corte (H8): conteo, préstamos abiertos del Excel, costos y las dos
 * firmas que aplican el saldo de apertura.
 */
@RestController
@RequestMapping("/api/v1/aperturas")
@Tag(name = "Inventario")
class AperturasControlador {

  private final ServicioAperturas servicio;

  AperturasControlador(ServicioAperturas servicio) {
    this.servicio = servicio;
  }

  @GetMapping
  @Operation(operationId = "listarAperturas", summary = "Aperturas de almacén, en curso primero")
  List<Apertura> aperturas() {
    return servicio.aperturas();
  }

  @GetMapping("/{id}")
  @Operation(
      operationId = "obtenerApertura",
      summary = "Una apertura: materiales, préstamos, lo que falta y la huella para firmar")
  DetalleApertura apertura(@PathVariable long id) {
    return servicio.apertura(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      operationId = "abrirApertura",
      summary =
          "Abrir la apertura de un almacén sin movimientos: conteo a ciegas y préstamos abiertos"
              + " del Excel")
  DetalleApertura abrir(@Valid @RequestBody DatosApertura datos) {
    return servicio.abrir(datos);
  }

  @PostMapping("/{id}/sin-contar-en-cero")
  @Operation(
      operationId = "aperturaSinContarEnCero",
      summary = "Marcar en 0 lo que no se encontró al contar (almacén)")
  DetalleApertura sinContarEnCero(@PathVariable long id) {
    return servicio.sinContarEnCero(id);
  }

  @PostMapping("/{id}/prestamos/traer")
  @Operation(
      operationId = "traerPrestamosApertura",
      summary = "Volver a traer los préstamos abiertos del Excel (después de la carga final)")
  DetalleApertura traerPrestamos(@PathVariable long id) {
    return servicio.traerPrestamos(id);
  }

  @PostMapping("/{id}/prestamos")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      operationId = "agregarPrestamoApertura",
      summary = "Agregar un préstamo abierto que no estaba en el Excel")
  DetalleApertura agregarPrestamo(
      @PathVariable long id, @Valid @RequestBody NuevoPrestamoApertura datos) {
    return servicio.agregarPrestamo(id, datos);
  }

  @PatchMapping("/{id}/prestamos/{prestamoId}")
  @Operation(
      operationId = "cambiarPrestamoApertura",
      summary = "Resolver un préstamo: listo (material y responsable), pendiente o descartado")
  DetalleApertura cambiarPrestamo(
      @PathVariable long id,
      @PathVariable long prestamoId,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody DatosPrestamoApertura datos) {
    return servicio.cambiarPrestamo(id, prestamoId, Versiones.deEncabezado(siCoincide), datos);
  }

  @PostMapping("/{id}/prestamos/{prestamoId}/herramienta")
  @Operation(
      operationId = "altaHerramientaApertura",
      summary = "Dar de alta la herramienta del préstamo como material (categoría Herramienta)")
  DetalleApertura herramienta(
      @PathVariable long id, @PathVariable long prestamoId, @Valid @RequestBody Herramienta datos) {
    return servicio.darDeAltaHerramienta(id, prestamoId, datos);
  }

  @PutMapping("/{id}/costos")
  @Operation(
      operationId = "capturarCostosApertura",
      summary = "Capturar o quitar costos sin IVA (administración)")
  DetalleApertura costos(@PathVariable long id, @Valid @RequestBody CapturaCostos captura) {
    return servicio.capturarCostos(id, captura);
  }

  @PostMapping("/{id}/firmar")
  @Operation(
      operationId = "firmarApertura",
      summary =
          "Firmar como coordinación o administración; la segunda firma aplica el saldo de"
              + " apertura")
  DetalleApertura firmar(@PathVariable long id, @Valid @RequestBody Firma firma) {
    return servicio.firmar(id, firma);
  }

  @PostMapping("/{id}/retirar-firmas")
  @Operation(
      operationId = "retirarFirmasApertura",
      summary = "Retirar las firmas para corregir (antes de la segunda firma)")
  DetalleApertura retirarFirmas(@PathVariable long id) {
    return servicio.retirarFirmas(id);
  }

  @PostMapping("/{id}/cancelar")
  @Operation(operationId = "cancelarApertura", summary = "Cancelar la apertura y su conteo")
  DetalleApertura cancelar(@PathVariable long id, @Valid @RequestBody Cancelacion cancelacion) {
    return servicio.cancelar(id, cancelacion);
  }

  @GetMapping("/{id}/acta.xlsx")
  @Operation(
      operationId = "actaApertura",
      summary = "Acta de apertura en Excel: firmas, materiales valuados y préstamos")
  @ApiResponse(responseCode = "200", content = @Content(mediaType = LibroExcel.TIPO))
  ResponseEntity<byte[]> acta(@PathVariable long id) {
    DetalleApertura d = servicio.apertura(id);
    return Descargas.xlsx(servicio.acta(id), "acta-" + d.apertura().folio());
  }
}

package mx.constructora.inventario.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import mx.constructora.compartido.LibroExcel;
import mx.constructora.inventario.aplicacion.ServicioPeriodos;
import mx.constructora.inventario.aplicacion.ServicioPeriodos.Periodos;
import mx.constructora.inventario.aplicacion.ServicioPeriodos.RevisionCierre;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cierre de mes. */
@RestController
@RequestMapping("/api/v1/periodos")
@Tag(name = "Inventario")
class PeriodosControlador {

  private final ServicioPeriodos servicio;

  PeriodosControlador(ServicioPeriodos servicio) {
    this.servicio = servicio;
  }

  @GetMapping
  @Operation(operationId = "listarPeriodos", summary = "Meses cerrados y el siguiente por cerrar")
  Periodos periodos() {
    return servicio.periodos();
  }

  @GetMapping("/{anio}/{mes}/revision")
  @Operation(
      operationId = "revisarCierre",
      summary = "Antes de cerrar: lo que impide cerrar el mes y los avisos que no lo impiden")
  RevisionCierre revision(@PathVariable int anio, @PathVariable int mes) {
    return servicio.revision(anio, mes);
  }

  @GetMapping("/{anio}/{mes}/acta.xlsx")
  @Operation(
      operationId = "actaDeCierre",
      summary = "Acta del cierre en Excel: valor por almacén, inventario y movimientos del mes")
  @ApiResponse(responseCode = "200", content = @Content(mediaType = LibroExcel.TIPO))
  ResponseEntity<byte[]> acta(@PathVariable int anio, @PathVariable int mes) {
    return Descargas.xlsx(servicio.acta(anio, mes), "acta-cierre-%d-%02d".formatted(anio, mes));
  }

  @PostMapping("/{anio}/{mes}/cerrar")
  @Operation(operationId = "cerrarPeriodo", summary = "Cerrar el mes (administración)")
  Periodos cerrar(@PathVariable int anio, @PathVariable int mes) {
    return servicio.cerrar(anio, mes);
  }
}

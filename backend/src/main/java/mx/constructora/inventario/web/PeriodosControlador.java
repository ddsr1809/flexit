package mx.constructora.inventario.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import mx.constructora.inventario.aplicacion.ServicioPeriodos;
import mx.constructora.inventario.aplicacion.ServicioPeriodos.Periodos;
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

  @PostMapping("/{anio}/{mes}/cerrar")
  @Operation(operationId = "cerrarPeriodo", summary = "Cerrar el mes (administración)")
  Periodos cerrar(@PathVariable int anio, @PathVariable int mes) {
    return servicio.cerrar(anio, mes);
  }
}

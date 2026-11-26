package mx.constructora.inventario.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import mx.constructora.inventario.aplicacion.ServicioPrestamos;
import mx.constructora.inventario.aplicacion.ServicioPrestamos.DatosPrestamo;
import mx.constructora.inventario.aplicacion.ServicioPrestamos.Prestamo;
import mx.constructora.inventario.aplicacion.ServicioPrestamos.PrestamoPerdido;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Préstamos de herramienta. */
@RestController
@RequestMapping("/api/v1/prestamos")
@Tag(name = "Inventario")
class PrestamosControlador {

  /** Fecha en que regresó; vacía: hoy. */
  record Devolucion(LocalDate fecha) {}

  /** Qué pasó con la herramienta. */
  record Perdida(@NotBlank @Size(max = 400) String motivo) {}

  private final ServicioPrestamos servicio;

  PrestamosControlador(ServicioPrestamos servicio) {
    this.servicio = servicio;
  }

  @GetMapping
  @Operation(
      operationId = "listarPrestamos",
      summary = "Préstamos; vencidos=true: abiertos con fecha de regreso pasada")
  List<Prestamo> prestamos(
      @RequestParam(required = false) Long almacenId,
      @RequestParam(required = false) String estado,
      @RequestParam(defaultValue = "false") boolean vencidos) {
    return servicio.prestamos(almacenId, estado, vencidos);
  }

  @GetMapping("/{id}")
  @Operation(operationId = "obtenerPrestamo", summary = "Un préstamo")
  Prestamo prestamo(@PathVariable long id) {
    return servicio.prestamo(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "crearPrestamo", summary = "Prestar: aparta la existencia")
  Prestamo prestar(@Valid @RequestBody DatosPrestamo datos) {
    return servicio.prestar(datos);
  }

  @PostMapping("/{id}/devolver")
  @Operation(operationId = "devolverPrestamo", summary = "Regresó: libera lo apartado")
  Prestamo devolver(@PathVariable long id, @RequestBody(required = false) Devolucion devolucion) {
    return servicio.devolver(id, devolucion == null ? null : devolucion.fecha());
  }

  @PostMapping("/{id}/perdido")
  @Operation(operationId = "prestamoPerdido", summary = "Se perdió: crea la merma por autorizar")
  PrestamoPerdido perdido(@PathVariable long id, @Valid @RequestBody Perdida perdida) {
    return servicio.perdido(id, perdida.motivo());
  }
}

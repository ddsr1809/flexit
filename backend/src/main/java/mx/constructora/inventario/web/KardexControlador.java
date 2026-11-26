package mx.constructora.inventario.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import mx.constructora.inventario.aplicacion.ServicioExistencias;
import mx.constructora.inventario.aplicacion.ServicioExistencias.RenglonKardex;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Kardex de un material: cada entrada y salida con la existencia que dejó. */
@RestController
@Tag(name = "Inventario")
class KardexControlador {

  private final ServicioExistencias existencias;

  KardexControlador(ServicioExistencias existencias) {
    this.existencias = existencias;
  }

  @GetMapping("/api/v1/materiales/{id}/kardex")
  @Operation(
      operationId = "kardexDeMaterial",
      summary = "Kardex del material, del más reciente al más antiguo (1000 renglones)")
  List<RenglonKardex> kardex(
      @PathVariable long id,
      @RequestParam(required = false) Long almacenId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate desde,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate hasta) {
    return existencias.kardex(id, almacenId, desde, hasta);
  }
}

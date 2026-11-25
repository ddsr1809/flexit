package mx.constructora.catalogo.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import mx.constructora.catalogo.aplicacion.ServicioProveedores;
import mx.constructora.catalogo.aplicacion.ServicioProveedores.DatosProveedor;
import mx.constructora.catalogo.aplicacion.ServicioProveedores.Proveedor;
import mx.constructora.compartido.Versiones;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

/** Proveedores de material. */
@RestController
@RequestMapping("/api/v1/proveedores")
@Tag(name = "Catálogo")
class ProveedoresControlador {

  private final ServicioProveedores servicio;

  ProveedoresControlador(ServicioProveedores servicio) {
    this.servicio = servicio;
  }

  @GetMapping
  @Operation(operationId = "listarProveedores", summary = "Proveedores, con búsqueda opcional")
  List<Proveedor> proveedores(@RequestParam(required = false) String q) {
    return servicio.proveedores(q);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "crearProveedor", summary = "Dar de alta un proveedor")
  Proveedor crear(@Valid @RequestBody DatosProveedor datos) {
    return servicio.crear(datos);
  }

  @PatchMapping("/{id}")
  @Operation(operationId = "cambiarProveedor", summary = "Cambiar un proveedor")
  Proveedor cambiar(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody DatosProveedor datos) {
    return servicio.cambiar(id, Versiones.deEncabezado(siCoincide), datos);
  }
}

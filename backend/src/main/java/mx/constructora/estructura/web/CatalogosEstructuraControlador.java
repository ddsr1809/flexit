package mx.constructora.estructura.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import mx.constructora.compartido.Versiones;
import mx.constructora.estructura.aplicacion.ServicioClientes;
import mx.constructora.estructura.aplicacion.ServicioClientes.Cliente;
import mx.constructora.estructura.aplicacion.ServicioClientes.DatosCliente;
import mx.constructora.estructura.aplicacion.ServicioClientes.DatosFrente;
import mx.constructora.estructura.aplicacion.ServicioClientes.DatosPrototipo;
import mx.constructora.estructura.aplicacion.ServicioClientes.Frente;
import mx.constructora.estructura.aplicacion.ServicioClientes.Prototipo;
import mx.constructora.estructura.aplicacion.ServicioClientes.TipoUnidad;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Clientes, frentes, prototipos y tipos de unidad. Leer: cualquier usuario. Escribir: {@code
 * sistema:administrar}. Los cambios ({@code PATCH}) llevan la versión leída en {@code If-Match}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Estructura")
class CatalogosEstructuraControlador {

  private final ServicioClientes servicio;

  CatalogosEstructuraControlador(ServicioClientes servicio) {
    this.servicio = servicio;
  }

  @GetMapping("/clientes")
  @Operation(operationId = "listarClientes", summary = "Clientes")
  List<Cliente> clientes() {
    return servicio.clientes();
  }

  @PostMapping("/clientes")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "crearCliente", summary = "Dar de alta un cliente")
  Cliente crearCliente(@Valid @RequestBody DatosCliente datos) {
    return servicio.crearCliente(datos);
  }

  @PatchMapping("/clientes/{id}")
  @Operation(operationId = "cambiarCliente", summary = "Cambiar un cliente")
  Cliente cambiarCliente(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody DatosCliente datos) {
    return servicio.cambiarCliente(id, Versiones.deEncabezado(siCoincide), datos);
  }

  @GetMapping("/frentes")
  @Operation(operationId = "listarFrentes", summary = "Frentes")
  List<Frente> frentes() {
    return servicio.frentes();
  }

  @PostMapping("/frentes")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "crearFrente", summary = "Dar de alta un frente")
  Frente crearFrente(@Valid @RequestBody DatosFrente datos) {
    return servicio.crearFrente(datos);
  }

  @PatchMapping("/frentes/{id}")
  @Operation(operationId = "cambiarFrente", summary = "Cambiar un frente")
  Frente cambiarFrente(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody DatosFrente datos) {
    return servicio.cambiarFrente(id, Versiones.deEncabezado(siCoincide), datos);
  }

  @GetMapping("/prototipos")
  @Operation(operationId = "listarPrototipos", summary = "Prototipos de vivienda")
  List<Prototipo> prototipos() {
    return servicio.prototipos();
  }

  @PostMapping("/prototipos")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "crearPrototipo", summary = "Dar de alta un prototipo")
  Prototipo crearPrototipo(@Valid @RequestBody DatosPrototipo datos) {
    return servicio.crearPrototipo(datos);
  }

  @PatchMapping("/prototipos/{id}")
  @Operation(operationId = "cambiarPrototipo", summary = "Cambiar un prototipo")
  Prototipo cambiarPrototipo(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody DatosPrototipo datos) {
    return servicio.cambiarPrototipo(id, Versiones.deEncabezado(siCoincide), datos);
  }

  @GetMapping("/tipos-unidad")
  @Operation(operationId = "listarTiposUnidad", summary = "Tipos de unidad de obra")
  List<TipoUnidad> tiposUnidad() {
    return servicio.tiposUnidad();
  }
}

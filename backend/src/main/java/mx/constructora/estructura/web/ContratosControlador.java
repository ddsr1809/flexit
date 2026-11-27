package mx.constructora.estructura.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import mx.constructora.compartido.Versiones;
import mx.constructora.estructura.aplicacion.ServicioContratos;
import mx.constructora.estructura.aplicacion.ServicioContratos.Contrato;
import mx.constructora.estructura.aplicacion.ServicioContratos.DatosContrato;
import mx.constructora.estructura.aplicacion.ServicioContratos.DatosPrecio;
import mx.constructora.estructura.aplicacion.ServicioContratos.PrecioPaquete;
import mx.constructora.estructura.aplicacion.ServicioContratos.Residentes;
import mx.constructora.estructura.aplicacion.ServicioUnidades;
import mx.constructora.estructura.aplicacion.ServicioUnidades.DatosPresupuesto;
import mx.constructora.estructura.aplicacion.ServicioUnidades.DatosUnidad;
import mx.constructora.estructura.aplicacion.ServicioUnidades.NuevasUnidades;
import mx.constructora.estructura.aplicacion.ServicioUnidades.UnidadObra;
import mx.constructora.organizacion.DirectorioUsuarios.UsuarioResumen;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contratos, sus unidades de obra, residentes y precios por paquete. Quien no tiene {@code
 * estructura:ver-todo} solo ve sus contratos; un contrato ajeno responde 404.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Contratos")
class ContratosControlador {

  private final ServicioContratos contratos;
  private final ServicioUnidades unidades;

  ContratosControlador(ServicioContratos contratos, ServicioUnidades unidades) {
    this.contratos = contratos;
    this.unidades = unidades;
  }

  @GetMapping("/contratos")
  @Operation(operationId = "listarContratos", summary = "Contratos al alcance del usuario")
  List<Contrato> contratos(
      @RequestParam(required = false) String estado,
      @RequestParam(required = false) Long frenteId) {
    return contratos.contratos(estado, frenteId);
  }

  @GetMapping("/contratos/{id}")
  @Operation(operationId = "obtenerContrato", summary = "Un contrato (ETag con su versión)")
  ResponseEntity<Contrato> contrato(@PathVariable long id) {
    Contrato contrato = contratos.contrato(id);
    return ResponseEntity.ok().eTag(Versiones.etag(contrato.version())).body(contrato);
  }

  @PostMapping("/contratos")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "crearContrato", summary = "Dar de alta un contrato")
  Contrato crear(@Valid @RequestBody DatosContrato datos) {
    return contratos.crearContrato(datos);
  }

  @PatchMapping("/contratos/{id}")
  @Operation(operationId = "cambiarContrato", summary = "Cambiar un contrato")
  Contrato cambiar(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody DatosContrato datos) {
    return contratos.cambiarContrato(id, Versiones.deEncabezado(siCoincide), datos);
  }

  @GetMapping("/contratos/{id}/unidades")
  @Operation(operationId = "listarUnidades", summary = "Unidades de obra del contrato")
  List<UnidadObra> unidades(@PathVariable long id) {
    return unidades.unidades(id);
  }

  @PostMapping("/contratos/{id}/unidades")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      operationId = "crearUnidades",
      summary = "Dar de alta lotes (manzana y lista de lotes) u otra unidad (clave)")
  List<UnidadObra> crearUnidades(@PathVariable long id, @Valid @RequestBody NuevasUnidades datos) {
    return unidades.crearUnidades(id, datos);
  }

  @PatchMapping("/unidades/{id}")
  @Operation(operationId = "cambiarUnidad", summary = "Cambiar prototipo, presupuesto o estado")
  UnidadObra cambiarUnidad(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody DatosUnidad datos) {
    return unidades.cambiarUnidad(id, Versiones.deEncabezado(siCoincide), datos);
  }

  @PutMapping("/contratos/{id}/presupuesto")
  @Operation(
      operationId = "asignarPresupuesto",
      summary = "Presupuesto de material para los lotes de un prototipo o una lista de lotes")
  List<UnidadObra> asignarPresupuesto(
      @PathVariable long id, @Valid @RequestBody DatosPresupuesto datos) {
    return unidades.asignarPresupuesto(id, datos);
  }

  @GetMapping("/contratos/{id}/residentes")
  @Operation(operationId = "listarResidentes", summary = "Usuarios asignados al contrato")
  List<UsuarioResumen> residentes(@PathVariable long id) {
    return contratos.residentes(id);
  }

  @PutMapping("/contratos/{id}/residentes")
  @Operation(operationId = "asignarResidentes", summary = "Reemplazar los usuarios asignados")
  List<UsuarioResumen> asignarResidentes(
      @PathVariable long id, @Valid @RequestBody Residentes datos) {
    return contratos.asignarResidentes(id, datos);
  }

  @GetMapping("/residentes")
  @Operation(operationId = "candidatosResidente", summary = "Usuarios con rol residente")
  List<UsuarioResumen> candidatos() {
    return contratos.candidatosResidente();
  }

  @GetMapping("/contratos/{id}/precios")
  @Operation(operationId = "listarPrecios", summary = "Precio de cobro y destajo por paquete")
  List<PrecioPaquete> precios(@PathVariable long id) {
    return contratos.precios(id);
  }

  @PutMapping("/contratos/{id}/precios")
  @Operation(operationId = "fijarPrecio", summary = "Fijar el precio de un paquete")
  PrecioPaquete fijarPrecio(@PathVariable long id, @Valid @RequestBody DatosPrecio datos) {
    return contratos.fijarPrecio(id, datos);
  }
}

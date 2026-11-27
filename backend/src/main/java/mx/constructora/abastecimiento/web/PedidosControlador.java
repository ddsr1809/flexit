package mx.constructora.abastecimiento.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import mx.constructora.abastecimiento.aplicacion.ServicioPedidos;
import mx.constructora.abastecimiento.aplicacion.ServicioPedidos.DatosPedido;
import mx.constructora.abastecimiento.aplicacion.ServicioPedidos.DatosRecepcion;
import mx.constructora.abastecimiento.aplicacion.ServicioPedidos.GrupoFaltantes;
import mx.constructora.abastecimiento.aplicacion.ServicioPedidos.Pedido;
import mx.constructora.abastecimiento.aplicacion.ServicioPedidos.ResultadoRecepcion;
import mx.constructora.abastecimiento.aplicacion.ServicioPedidos.ResumenPedido;
import mx.constructora.compartido.Versiones;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Faltantes por proveedor, pedidos y recepción contra pedido. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Abastecimiento")
class PedidosControlador {

  /** Por qué se cierra o se cancela. */
  record Motivo(@NotBlank @Size(max = 500) String motivo) {}

  private final ServicioPedidos servicio;

  PedidosControlador(ServicioPedidos servicio) {
    this.servicio = servicio;
  }

  @GetMapping("/faltantes")
  @Operation(
      operationId = "faltantesPorProveedor",
      summary =
          "Lo que falta comprar de las requisiciones autorizadas, por proveedor sugerido (el"
              + " último que surtió cada material)")
  List<GrupoFaltantes> faltantes(
      @RequestParam(required = false) Long proveedorId,
      @RequestParam(required = false) Long almacenId) {
    return servicio.faltantes(proveedorId, almacenId);
  }

  @GetMapping("/pedidos")
  @Operation(operationId = "listarPedidos", summary = "Pedidos filtrados, más recientes primero")
  List<ResumenPedido> pedidos(
      @Schema(description = "Separados por coma: borrador,enviado,recibido…")
          @RequestParam(required = false)
          String estados,
      @RequestParam(required = false) Long proveedorId,
      @RequestParam(required = false) Long almacenId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate desde,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate hasta,
      @RequestParam(required = false) String folio,
      @RequestParam(defaultValue = "0") int pagina,
      @RequestParam(defaultValue = "50") int tamano) {
    return servicio.buscar(estados, proveedorId, almacenId, desde, hasta, folio, pagina, tamano);
  }

  @GetMapping("/pedidos/{id}")
  @Operation(
      operationId = "obtenerPedido",
      summary = "Un pedido con lo recibido y las requisiciones que cubre (ETag)")
  ResponseEntity<Pedido> pedido(@PathVariable long id) {
    Pedido p = servicio.pedido(id);
    return ResponseEntity.ok().eTag(Versiones.etag(p.version())).body(p);
  }

  @PostMapping("/pedidos")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      operationId = "crearPedido",
      summary = "Capturar un pedido; lo pedido se asigna a las requisiciones más urgentes")
  Pedido crear(@Valid @RequestBody DatosPedido datos) {
    return servicio.crear(datos);
  }

  @PutMapping("/pedidos/{id}")
  @Operation(operationId = "reemplazarPedido", summary = "Corregir un borrador (If-Match)")
  Pedido reemplazar(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody DatosPedido datos) {
    return servicio.reemplazar(id, Versiones.deEncabezado(siCoincide), datos);
  }

  @PostMapping("/pedidos/{id}/enviar")
  @Operation(operationId = "enviarPedido", summary = "Marcar como enviado al proveedor")
  Pedido enviar(@PathVariable long id) {
    return servicio.enviar(id);
  }

  @PostMapping("/pedidos/{id}/recibir")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      operationId = "recibirPedido",
      summary =
          "Recibir contra el pedido: crea y aplica la recepción y aparta lo recibido para las"
              + " requisiciones que cubre")
  ResultadoRecepcion recibir(@PathVariable long id, @Valid @RequestBody DatosRecepcion datos) {
    return servicio.recibir(id, datos);
  }

  @PostMapping("/pedidos/{id}/cerrar")
  @Operation(
      operationId = "cerrarPedido",
      summary = "Dar por terminado un pedido enviado; lo que no llegó vuelve a ser faltante")
  Pedido cerrar(@PathVariable long id, @Valid @RequestBody Motivo motivo) {
    return servicio.cerrar(id, motivo.motivo());
  }

  @PostMapping("/pedidos/{id}/cancelar")
  @Operation(
      operationId = "cancelarPedido",
      summary = "Cancelar un borrador o un pedido enviado sin nada recibido")
  Pedido cancelar(@PathVariable long id, @Valid @RequestBody Motivo motivo) {
    return servicio.cancelar(id, motivo.motivo());
  }
}

package mx.constructora.inventario.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Versiones;
import mx.constructora.inventario.aplicacion.ServicioAplicacion;
import mx.constructora.inventario.aplicacion.ServicioMovimientos;
import mx.constructora.inventario.aplicacion.ServicioMovimientos.DatosMovimiento;
import mx.constructora.inventario.aplicacion.ServicioMovimientos.LineaRestante;
import mx.constructora.inventario.aplicacion.ServicioMovimientos.Movimiento;
import mx.constructora.inventario.aplicacion.ServicioMovimientos.ResumenMovimiento;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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

/**
 * Documentos del almacén: recepción, vale, devolución, traspaso, reasignación, merma y ajuste de
 * costo. Se crean en borrador, se envían, se autorizan y se aplican; aplicar es lo único que mueve
 * existencia.
 */
@RestController
@RequestMapping("/api/v1/movimientos")
@Tag(name = "Inventario")
class MovimientosControlador {

  /** Por qué se rechaza: regresa a quien capturó. */
  record Nota(@NotBlank @Size(max = 500) String nota) {}

  /** Por qué se cancela. */
  record Motivo(@NotBlank @Size(max = 500) String motivo) {}

  /** Varios documentos a la vez (autorizar o aplicar en grupo). */
  record Ids(@NotEmpty @Size(max = 200) List<Long> ids) {}

  /** Resultado de un documento dentro de una operación en grupo. */
  record ResultadoLote(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) boolean ok,
      String folio,
      String codigo,
      String mensaje) {}

  private final ServicioMovimientos movimientos;
  private final ServicioAplicacion aplicacion;

  MovimientosControlador(ServicioMovimientos movimientos, ServicioAplicacion aplicacion) {
    this.movimientos = movimientos;
    this.aplicacion = aplicacion;
  }

  @GetMapping
  @Operation(
      operationId = "listarMovimientos",
      summary = "Documentos filtrados, más recientes primero")
  List<ResumenMovimiento> movimientos(
      @RequestParam(required = false) String tipo,
      @Schema(description = "Separados por coma: borrador,pendiente…")
          @RequestParam(required = false)
          String estados,
      @RequestParam(required = false) Long almacenId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate desde,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate hasta,
      @RequestParam(required = false) String folio,
      @RequestParam(defaultValue = "0") int pagina,
      @RequestParam(defaultValue = "50") int tamano) {
    return movimientos.buscar(tipo, estados, almacenId, desde, hasta, folio, pagina, tamano);
  }

  @GetMapping("/por-autorizar")
  @Operation(
      operationId = "movimientosPorAutorizar",
      summary = "Bandeja: lo pendiente que el usuario puede autorizar")
  List<ResumenMovimiento> porAutorizar() {
    return movimientos.porAutorizar();
  }

  @GetMapping("/{id}")
  @Operation(operationId = "obtenerMovimiento", summary = "Un documento con sus líneas (ETag)")
  ResponseEntity<Movimiento> movimiento(@PathVariable long id) {
    Movimiento movimiento = movimientos.movimiento(id);
    return ResponseEntity.ok().eTag(Versiones.etag(movimiento.version())).body(movimiento);
  }

  @GetMapping("/{id}/restantes")
  @Operation(
      operationId = "restantesDeMovimiento",
      summary = "Lo que queda en obra de cada línea (para devolver o reasignar)")
  List<LineaRestante> restantes(@PathVariable long id) {
    return movimientos.restantes(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      operationId = "crearMovimiento",
      summary =
          "Capturar un documento en borrador (Idempotency-Key evita duplicarlo al reintentar)")
  Movimiento crear(
      @RequestHeader(name = "Idempotency-Key", required = false) String clave,
      @Valid @RequestBody DatosMovimiento datos) {
    return movimientos.crear(datos, idempotencia(clave));
  }

  @PutMapping("/{id}")
  @Operation(operationId = "reemplazarMovimiento", summary = "Corregir un borrador (If-Match)")
  Movimiento reemplazar(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody DatosMovimiento datos) {
    return movimientos.reemplazar(id, Versiones.deEncabezado(siCoincide), datos);
  }

  @PostMapping("/{id}/enviar")
  @Operation(operationId = "enviarMovimiento", summary = "Enviar a autorizar")
  Movimiento enviar(@PathVariable long id) {
    return movimientos.enviar(id);
  }

  @PostMapping("/{id}/autorizar")
  @Operation(operationId = "autorizarMovimiento", summary = "Autorizar (nunca lo propio)")
  Movimiento autorizar(@PathVariable long id) {
    return movimientos.autorizar(id);
  }

  @PostMapping("/{id}/rechazar")
  @Operation(operationId = "rechazarMovimiento", summary = "Regresar a borrador con una nota")
  Movimiento rechazar(@PathVariable long id, @Valid @RequestBody Nota nota) {
    return movimientos.rechazar(id, nota.nota());
  }

  @PostMapping("/{id}/aplicar")
  @Operation(operationId = "aplicarMovimiento", summary = "Aplicar: mueve existencia y costo")
  Movimiento aplicar(@PathVariable long id) {
    return aplicacion.aplicar(id);
  }

  @PostMapping("/{id}/cancelar")
  @Operation(
      operationId = "cancelarMovimiento",
      summary = "Cancelar; un vale o recepción aplicados se cancelan con su movimiento inverso")
  Movimiento cancelar(@PathVariable long id, @Valid @RequestBody Motivo motivo) {
    return aplicacion.cancelar(id, motivo.motivo());
  }

  @PostMapping("/autorizar")
  @Operation(
      operationId = "autorizarMovimientos",
      summary = "Autorizar varios; cada uno en su propia transacción")
  List<ResultadoLote> autorizarVarios(@Valid @RequestBody Ids ids) {
    List<ResultadoLote> resultado = new ArrayList<>();
    for (Long id : ids.ids()) {
      resultado.add(uno(id, () -> movimientos.autorizar(id)));
    }
    return resultado;
  }

  @PostMapping("/aplicar")
  @Operation(
      operationId = "aplicarMovimientos",
      summary = "Aplicar varios; cada uno en su propia transacción")
  List<ResultadoLote> aplicarVarios(@Valid @RequestBody Ids ids) {
    List<ResultadoLote> resultado = new ArrayList<>();
    for (Long id : ids.ids()) {
      resultado.add(uno(id, () -> aplicacion.aplicar(id)));
    }
    return resultado;
  }

  private static ResultadoLote uno(long id, java.util.function.Supplier<Movimiento> accion) {
    try {
      Movimiento m = accion.get();
      return new ResultadoLote(id, true, m.folio(), null, null);
    } catch (ErrorNegocio e) {
      return new ResultadoLote(id, false, null, e.codigo(), e.getMessage());
    } catch (AccessDeniedException e) {
      return new ResultadoLote(
          id, false, null, "SIN_PERMISO", "No tienes permiso para esta operación");
    }
  }

  private static UUID idempotencia(String clave) {
    if (clave == null || clave.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(clave.trim());
    } catch (IllegalArgumentException e) {
      throw ErrorNegocio.invalido("IDEMPOTENCIA_INVALIDA", "Idempotency-Key debe ser un UUID");
    }
  }
}

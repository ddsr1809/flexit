package mx.constructora.abastecimiento.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import mx.constructora.abastecimiento.aplicacion.ServicioRequisiciones;
import mx.constructora.abastecimiento.aplicacion.ServicioRequisiciones.DatosRequisicion;
import mx.constructora.abastecimiento.aplicacion.ServicioRequisiciones.DatosSurtido;
import mx.constructora.abastecimiento.aplicacion.ServicioRequisiciones.Requisicion;
import mx.constructora.abastecimiento.aplicacion.ServicioRequisiciones.ResumenRequisicion;
import mx.constructora.abastecimiento.aplicacion.ServicioRequisiciones.ValeCreado;
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

/**
 * Requisiciones de material: el residente pide para sus lotes, coordinación autoriza (y se aparta
 * lo que hay), el almacén surte con un vale ligado.
 */
@RestController
@RequestMapping("/api/v1/requisiciones")
@Tag(name = "Abastecimiento")
class RequisicionesControlador {

  /** Por qué se rechaza: regresa a quien capturó. */
  record Nota(@NotBlank @Size(max = 500) String nota) {}

  /** Por qué se cierra o se cancela. */
  record Motivo(@NotBlank @Size(max = 500) String motivo) {}

  private final ServicioRequisiciones servicio;

  RequisicionesControlador(ServicioRequisiciones servicio) {
    this.servicio = servicio;
  }

  @GetMapping
  @Operation(
      operationId = "listarRequisiciones",
      summary = "Requisiciones filtradas, más recientes primero (el residente, solo sus contratos)")
  List<ResumenRequisicion> requisiciones(
      @Schema(description = "Separados por coma: borrador,pendiente,autorizada…")
          @RequestParam(required = false)
          String estados,
      @RequestParam(required = false) Long contratoId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate desde,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate hasta,
      @RequestParam(required = false) String folio,
      @RequestParam(defaultValue = "0") int pagina,
      @RequestParam(defaultValue = "50") int tamano) {
    return servicio.buscar(estados, contratoId, desde, hasta, folio, pagina, tamano);
  }

  @GetMapping("/por-autorizar")
  @Operation(
      operationId = "requisicionesPorAutorizar",
      summary = "Bandeja: requisiciones pendientes que el usuario puede autorizar")
  List<ResumenRequisicion> porAutorizar() {
    return servicio.porAutorizar();
  }

  @GetMapping("/{id}")
  @Operation(
      operationId = "obtenerRequisicion",
      summary = "Una requisición con el avance de cada renglón (ETag)")
  ResponseEntity<Requisicion> requisicion(@PathVariable long id) {
    Requisicion r = servicio.requisicion(id);
    return ResponseEntity.ok().eTag(Versiones.etag(r.version())).body(r);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "crearRequisicion", summary = "Capturar una requisición en borrador")
  Requisicion crear(@Valid @RequestBody DatosRequisicion datos) {
    return servicio.crear(datos);
  }

  @PutMapping("/{id}")
  @Operation(operationId = "reemplazarRequisicion", summary = "Corregir un borrador (If-Match)")
  Requisicion reemplazar(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody DatosRequisicion datos) {
    return servicio.reemplazar(id, Versiones.deEncabezado(siCoincide), datos);
  }

  @PostMapping("/{id}/enviar")
  @Operation(operationId = "enviarRequisicion", summary = "Enviar a autorizar")
  Requisicion enviar(@PathVariable long id) {
    return servicio.enviar(id);
  }

  @PostMapping("/{id}/autorizar")
  @Operation(
      operationId = "autorizarRequisicion",
      summary = "Autorizar (nunca lo propio) y apartar lo que haya en el almacén")
  Requisicion autorizar(@PathVariable long id) {
    return servicio.autorizar(id);
  }

  @PostMapping("/{id}/rechazar")
  @Operation(operationId = "rechazarRequisicion", summary = "Regresar a borrador con una nota")
  Requisicion rechazar(@PathVariable long id, @Valid @RequestBody Nota nota) {
    return servicio.rechazar(id, nota.nota());
  }

  @PostMapping("/{id}/reservar")
  @Operation(
      operationId = "reservarRequisicion",
      summary = "Volver a apartar lo que haya en el almacén para lo pendiente")
  Requisicion reservar(@PathVariable long id) {
    return servicio.reservar(id);
  }

  @PostMapping("/{id}/surtir")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      operationId = "surtirRequisicion",
      summary = "Crear el vale que surte la requisición; queda enviado a autorizar")
  ValeCreado surtir(@PathVariable long id, @Valid @RequestBody DatosSurtido datos) {
    return servicio.surtir(id, datos);
  }

  @PostMapping("/{id}/cerrar")
  @Operation(
      operationId = "cerrarRequisicion",
      summary = "Dar por terminada una requisición autorizada; libera lo apartado")
  Requisicion cerrar(@PathVariable long id, @Valid @RequestBody Motivo motivo) {
    return servicio.cerrar(id, motivo.motivo());
  }

  @PostMapping("/{id}/cancelar")
  @Operation(
      operationId = "cancelarRequisicion",
      summary = "Cancelar (autorizada, solo sin vales); libera lo apartado")
  Requisicion cancelar(@PathVariable long id, @Valid @RequestBody Motivo motivo) {
    return servicio.cancelar(id, motivo.motivo());
  }
}

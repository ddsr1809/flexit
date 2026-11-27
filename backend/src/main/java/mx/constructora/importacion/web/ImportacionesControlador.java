package mx.constructora.importacion.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import mx.constructora.compartido.Versiones;
import mx.constructora.importacion.aplicacion.ServicioImportacion;
import mx.constructora.importacion.aplicacion.ServicioImportacion.AliasCarga;
import mx.constructora.importacion.aplicacion.ServicioImportacion.CambioAlias;
import mx.constructora.importacion.aplicacion.ServicioImportacion.ConciliacionCarga;
import mx.constructora.importacion.aplicacion.ServicioImportacion.DecisionesCarga;
import mx.constructora.importacion.aplicacion.ServicioImportacion.DestinoPosible;
import mx.constructora.importacion.aplicacion.ServicioImportacion.DetalleCarga;
import mx.constructora.importacion.aplicacion.ServicioImportacion.PaginaFilas;
import mx.constructora.importacion.aplicacion.ServicioImportacion.ResumenCarga;
import mx.constructora.importacion.aplicacion.ServicioImportacion.TipoCargaInfo;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Centro de migración: cargas desde Excel, cuarentena, confirmación, reversa y alias. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Migración")
class ImportacionesControlador {

  private final ServicioImportacion servicio;

  ImportacionesControlador(ServicioImportacion servicio) {
    this.servicio = servicio;
  }

  @GetMapping("/importaciones/tipos")
  @Operation(
      operationId = "ordenDeCarga",
      summary = "Tipos de carga en su orden, con lo que ya está confirmado")
  List<TipoCargaInfo> orden() {
    return servicio.orden();
  }

  @GetMapping("/importaciones")
  @Operation(operationId = "listarCargas", summary = "Bitácora de cargas, la más reciente primero")
  List<ResumenCarga> cargas() {
    return servicio.cargas();
  }

  @PostMapping(value = "/importaciones", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(
      operationId = "subirCarga",
      summary =
          "Subir un Excel y validarlo. 201 si es una carga nueva; 200 con repetida=true si el mismo"
              + " archivo ya estaba cargado")
  ResponseEntity<ResumenCarga> subir(
      @RequestParam String tipo,
      @RequestParam(required = false) Long clienteId,
      @RequestParam(required = false) String mes,
      @RequestParam(required = false) Integer anio,
      @RequestPart("archivo") MultipartFile archivo) {
    byte[] contenido;
    try {
      contenido = archivo.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    ResumenCarga carga =
        servicio.subir(tipo, archivo.getOriginalFilename(), contenido, clienteId, mes, anio);
    return ResponseEntity.status(carga.repetida() ? HttpStatus.OK : HttpStatus.CREATED).body(carga);
  }

  @GetMapping("/importaciones/{id}")
  @Operation(operationId = "obtenerCarga", summary = "Una carga con su conciliación")
  DetalleCarga carga(@PathVariable long id) {
    return servicio.carga(id);
  }

  @GetMapping("/importaciones/{id}/filas")
  @Operation(
      operationId = "filasDeCarga",
      summary = "Renglones de la carga con sus incidencias, filtrados y paginados")
  PaginaFilas filas(
      @PathVariable long id,
      @RequestParam(required = false) String estado,
      @RequestParam(required = false) String regla,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "false") boolean pendientes,
      @RequestParam(defaultValue = "0") int pagina,
      @RequestParam(defaultValue = "50") int tamano) {
    return servicio.filas(id, estado, regla, q, pendientes, pagina, tamano);
  }

  @GetMapping("/importaciones/{id}/conciliacion")
  @Operation(
      operationId = "conciliacionDeCarga",
      summary =
          "Totales del Excel contra lo que se carga (solo histórico). Sin todas=true, solo las"
              + " líneas con diferencia, cuarentena o descartes")
  ConciliacionCarga conciliacion(
      @PathVariable long id, @RequestParam(defaultValue = "false") boolean todas) {
    return servicio.conciliacion(id, todas);
  }

  @PostMapping("/importaciones/{id}/decisiones")
  @Operation(
      operationId = "decidirCarga",
      summary = "Resolver incidencias (una o en grupo) y volver a validar")
  DetalleCarga decidir(@PathVariable long id, @Valid @RequestBody DecisionesCarga datos) {
    return servicio.decidir(id, datos);
  }

  @DeleteMapping("/importaciones/{id}/decisiones/{numero}/{regla}")
  @Operation(operationId = "quitarDecision", summary = "Quitar una decisión y volver a validar")
  DetalleCarga quitarDecision(
      @PathVariable long id, @PathVariable int numero, @PathVariable String regla) {
    return servicio.quitarDecision(id, numero, regla);
  }

  @PostMapping("/importaciones/{id}/revalidar")
  @Operation(
      operationId = "revalidarCarga",
      summary = "Volver a validar con los catálogos y alias de ahora")
  DetalleCarga revalidar(@PathVariable long id) {
    return servicio.revalidar(id);
  }

  @PostMapping("/importaciones/{id}/confirmar")
  @Operation(
      operationId = "confirmarCarga",
      summary = "Confirmar: escribe todo en una transacción si no queda nada en cuarentena")
  DetalleCarga confirmar(@PathVariable long id) {
    return servicio.confirmar(id);
  }

  @PostMapping("/importaciones/{id}/revertir")
  @Operation(
      operationId = "revertirCarga",
      summary = "Revertir: borra lo que creó la carga si nada lo usa")
  DetalleCarga revertir(@PathVariable long id) {
    return servicio.revertir(id);
  }

  @GetMapping("/importaciones/destinos")
  @Operation(
      operationId = "destinosPosibles",
      summary = "Registros a los que se puede ligar un nombre del Excel (50 como máximo)")
  List<DestinoPosible> destinos(
      @RequestParam String entidad,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Long frenteId) {
    return servicio.destinos(entidad, q, frenteId);
  }

  @GetMapping("/importaciones/destinos/{entidad}/{id}")
  @Operation(
      operationId = "destinoPorId",
      summary =
          "Cómo se muestra un registro, con el mismo texto que destinosPosibles (el ensayo general"
              + " lleva así las decisiones de un entorno a otro)")
  DestinoPosible destino(@PathVariable String entidad, @PathVariable long id) {
    return servicio.destino(entidad, id);
  }

  @GetMapping("/alias")
  @Operation(operationId = "listarAlias", summary = "Cómo se tradujo cada nombre del Excel")
  List<AliasCarga> alias(
      @RequestParam(required = false) String entidad, @RequestParam(required = false) String q) {
    return servicio.alias(entidad, q);
  }

  @PatchMapping("/alias/{id}")
  @Operation(operationId = "cambiarAlias", summary = "Ligar el nombre a otro registro")
  AliasCarga cambiarAlias(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody CambioAlias datos) {
    return servicio.cambiarAlias(id, Versiones.deEncabezado(siCoincide), datos);
  }

  @DeleteMapping("/alias/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      operationId = "borrarAlias",
      summary = "Olvidar un alias: la siguiente carga vuelve a preguntar")
  void borrarAlias(@PathVariable long id) {
    servicio.borrarAlias(id);
  }
}

package mx.constructora.importacion.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.LibroExcel;
import mx.constructora.compartido.LibroExcel.Columna;
import mx.constructora.compartido.LibroExcel.Formato;
import mx.constructora.importacion.aplicacion.ServicioConciliacionContrato;
import mx.constructora.importacion.aplicacion.ServicioConciliacionContrato.ConciliacionContrato;
import mx.constructora.importacion.aplicacion.ServicioConciliacionContrato.Efecto;
import mx.constructora.importacion.aplicacion.ServicioConciliacionContrato.LoteConciliado;
import mx.constructora.importacion.aplicacion.ServicioConciliacionContrato.RenglonDiferencia;
import mx.constructora.importacion.aplicacion.ServicioImportacion;
import mx.constructora.importacion.aplicacion.ServicioImportacion.ConceptoConciliado;
import mx.constructora.importacion.aplicacion.ServicioImportacion.ConciliacionCarga;
import mx.constructora.importacion.aplicacion.ServicioImportacion.TotalConciliado;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conciliaciones contra el Excel: la de un contrato contra la hoja de su frente (issue 38) y la de
 * totales de una carga, en Excel (issue 52).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Migración")
class ConciliacionControlador {

  private final ServicioConciliacionContrato contratos;
  private final ServicioImportacion importacion;

  ConciliacionControlador(ServicioConciliacionContrato contratos, ServicioImportacion importacion) {
    this.contratos = contratos;
    this.importacion = importacion;
  }

  @GetMapping("/conciliacion/contratos/{id}")
  @Operation(
      operationId = "conciliarContrato",
      summary =
          "El costo de Programa de cada lote contra la hoja del frente recalculada, con cada"
              + " diferencia explicada")
  @ApiResponse(
      responseCode = "200",
      content = {
        @Content(
            mediaType = "application/json",
            schema = @Schema(implementation = ConciliacionContrato.class)),
        @Content(mediaType = LibroExcel.TIPO)
      })
  ResponseEntity<?> contrato(
      @PathVariable long id,
      @Schema(allowableValues = {"json", "xlsx"}) @RequestParam(defaultValue = "json")
          String formato) {
    boolean excel = esExcel(formato);
    ConciliacionContrato c = contratos.conciliar(id, excel);
    return excel ? archivo(excel(c), "conciliacion-" + c.contrato()) : ResponseEntity.ok(c);
  }

  @GetMapping("/importaciones/{id}/conciliacion.xlsx")
  @Operation(
      operationId = "conciliacionDeCargaExcel",
      summary = "La conciliación de totales de una carga del histórico, completa, en Excel")
  @ApiResponse(responseCode = "200", content = @Content(mediaType = LibroExcel.TIPO))
  ResponseEntity<byte[]> carga(@PathVariable long id) {
    ConciliacionCarga c = importacion.conciliacionCompleta(id);
    return archivo(excel(c), "conciliacion-carga-" + id);
  }

  static byte[] excel(ConciliacionContrato c) {
    LibroExcel libro = new LibroExcel();
    var lotes =
        libro.hoja(
            "Por lote",
            List.of(
                Columna.texto("Lote", 12),
                Columna.texto("Columna en la hoja", 10),
                Columna.moneda("Sistema"),
                Columna.texto("Efectos", 60),
                Columna.moneda("Suma de efectos"),
                Columna.moneda("Hoja " + c.hoja()),
                Columna.moneda("Redondeo")));
    for (LoteConciliado l : c.lotes()) {
      lotes.fila(
          l.clave(),
          l.columnaEnHoja(),
          l.sistema(),
          String.join(
              "; ",
              l.efectos().stream()
                  .map(e -> e.motivo() + " " + e.importe().toPlainString())
                  .toList()),
          l.efectos().stream()
              .map(Efecto::importe)
              .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add),
          l.hoja(),
          l.redondeo());
    }
    lotes.totales(
        "Total", null, c.sistema(), null, c.hojaTotal().subtract(c.sistema()), c.hojaTotal());
    var efectos =
        libro.hoja(
            "Por motivo",
            List.of(
                Columna.texto("Motivo", 16),
                Columna.texto("Qué es", 70),
                Columna.moneda("Importe")));
    efectos.fila(
        "SISTEMA", "Programa del histórico en el sistema (sin IVA, ponderado)", c.sistema());
    for (Efecto e : c.efectos()) {
      efectos.fila(e.motivo(), e.descripcion(), e.importe());
    }
    efectos.totales("HOJA", "Lo que diría la hoja " + c.hoja() + " recalculada", c.hojaTotal());
    var renglones =
        libro.hoja(
            "Renglones",
            List.of(
                new Columna("Fila de Salidas", Formato.ENTERO, 10),
                Columna.texto("Lote", 12),
                Columna.texto("Material", 40),
                Columna.texto("Motivo", 16),
                Columna.cantidad("Cantidad"),
                Columna.moneda("Importe"),
                Columna.texto("Nota", 50)));
    for (RenglonDiferencia r : c.renglones()) {
      renglones.fila(
          r.filaExcel(), r.lote(), r.material(), r.motivo(), r.cantidad(), r.importe(), r.nota());
    }
    if (!c.avisos().isEmpty()) {
      var avisos = libro.hoja("Avisos", List.of(Columna.texto("Aviso", 120)));
      c.avisos().forEach(avisos::fila);
    }
    return libro.bytes();
  }

  static byte[] excel(ConciliacionCarga c) {
    LibroExcel libro = new LibroExcel();
    var conceptos =
        libro.hoja(
            "Conceptos",
            List.of(
                Columna.texto("Concepto", 40),
                Columna.cantidad("Excel"),
                Columna.cantidad("Cargado"),
                Columna.cantidad("Cuarentena"),
                Columna.cantidad("Descartado"),
                Columna.cantidad("Diferencia"),
                new Columna("Líneas", Formato.ENTERO, 9),
                new Columna("Con diferencia", Formato.ENTERO, 12)));
    for (ConceptoConciliado k : c.conceptos()) {
      conceptos.fila(
          k.concepto(),
          k.excel(),
          k.cargado(),
          k.pendiente(),
          k.descartado(),
          k.diferencia(),
          k.lineas(),
          k.conDiferencia());
    }
    var lineas =
        libro.hoja(
            "Líneas",
            List.of(
                Columna.texto("Concepto", 30),
                Columna.texto("Llave", 50),
                Columna.cantidad("Excel"),
                Columna.cantidad("Cargado"),
                Columna.cantidad("Cuarentena"),
                Columna.cantidad("Descartado"),
                Columna.cantidad("Diferencia")));
    for (TotalConciliado t : c.lineas()) {
      lineas.fila(
          t.concepto(),
          t.llave(),
          t.excel(),
          t.cargado(),
          t.pendiente(),
          t.descartado(),
          t.diferencia());
    }
    return libro.bytes();
  }

  private static boolean esExcel(String formato) {
    if (formato == null || formato.equalsIgnoreCase("json")) {
      return false;
    }
    if (formato.equalsIgnoreCase("xlsx")) {
      return true;
    }
    throw ErrorNegocio.invalido("FORMATO_INVALIDO", "El formato es json o xlsx");
  }

  private static ResponseEntity<byte[]> archivo(byte[] contenido, String nombre) {
    String limpio = nombre.replaceAll("[^A-Za-z0-9._-]+", "-") + "-" + LocalDate.now() + ".xlsx";
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(LibroExcel.TIPO))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(limpio, StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(contenido);
  }
}

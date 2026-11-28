package mx.constructora.reportes.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import mx.constructora.abastecimiento.AbastecimientoApi.SurtidoRequisicion;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.LibroExcel;
import mx.constructora.reportes.aplicacion.Exportar;
import mx.constructora.reportes.aplicacion.ServicioEstabilizacion;
import mx.constructora.reportes.aplicacion.ServicioEstabilizacion.Estabilizacion;
import mx.constructora.reportes.aplicacion.ServicioReportes;
import mx.constructora.reportes.aplicacion.ServicioReportes.CostoContrato;
import mx.constructora.reportes.aplicacion.ServicioReportes.LotePresupuesto;
import mx.constructora.reportes.aplicacion.ServicioReportes.ReporteCosto;
import mx.constructora.reportes.aplicacion.ServicioReportes.ResumenContrato;
import mx.constructora.reportes.aplicacion.ServicioReportes.ValorInventario;
import mx.constructora.reportes.infraestructura.RepositorioReportes.Filtro;
import org.springframework.format.annotation.DateTimeFormat;
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
 * Reportes de solo lectura. Todos aceptan {@code ?formato=xlsx} y responden el mismo reporte como
 * archivo de Excel.
 */
@RestController
@RequestMapping("/api/v1/reportes")
@Tag(name = "Reportes")
class ReportesControlador {

  private static final String XLSX = LibroExcel.TIPO;

  private final ServicioReportes servicio;
  private final ServicioEstabilizacion estabilizacion;

  ReportesControlador(ServicioReportes servicio, ServicioEstabilizacion estabilizacion) {
    this.servicio = servicio;
    this.estabilizacion = estabilizacion;
  }

  @GetMapping("/contratos")
  @Operation(
      operationId = "reporteContratos",
      summary = "Costo por contrato: material (histórico + sistema), destajo y presupuesto")
  @ApiResponse(
      responseCode = "200",
      content = {
        @Content(
            mediaType = "application/json",
            array = @ArraySchema(schema = @Schema(implementation = ResumenContrato.class))),
        @Content(mediaType = XLSX)
      })
  ResponseEntity<?> contratos(
      @Schema(allowableValues = {"json", "xlsx"}) @RequestParam(defaultValue = "json")
          String formato) {
    return responder(servicio.contratos(), Exportar::contratos, "costo-por-contrato", formato);
  }

  @GetMapping("/contratos/{id}/lotes")
  @Operation(
      operationId = "reporteCostoPorLote",
      summary =
          "Costo de cada lote por clasificación, destajo y presupuesto (reemplaza la hoja del"
              + " frente)")
  @ApiResponse(
      responseCode = "200",
      content = {
        @Content(
            mediaType = "application/json",
            schema = @Schema(implementation = CostoContrato.class)),
        @Content(mediaType = XLSX)
      })
  ResponseEntity<?> costoPorLote(
      @PathVariable long id,
      @Schema(allowableValues = {"json", "xlsx"}) @RequestParam(defaultValue = "json")
          String formato) {
    CostoContrato c = servicio.costoPorLote(id);
    return responder(c, Exportar::costoPorLote, "costo-por-lote-" + c.contrato(), formato);
  }

  @GetMapping("/costo")
  @Operation(
      operationId = "reporteCosto",
      summary =
          "Costo de obra agrupado por contrato, frente, unidad, paquete, clasificación, material o"
              + " mes")
  @ApiResponse(
      responseCode = "200",
      content = {
        @Content(
            mediaType = "application/json",
            schema = @Schema(implementation = ReporteCosto.class)),
        @Content(mediaType = XLSX)
      })
  ResponseEntity<?> costo(
      @Schema(
              allowableValues = {
                "contrato",
                "frente",
                "unidad",
                "paquete",
                "clasificacion",
                "material",
                "mes"
              })
          @RequestParam(defaultValue = "contrato")
          String agrupar,
      @RequestParam(required = false) Long contratoId,
      @RequestParam(required = false) Long frenteId,
      @RequestParam(required = false) Long unidadObraId,
      @RequestParam(required = false) Long paqueteId,
      @RequestParam(required = false) Integer clasificacionId,
      @RequestParam(required = false) Long materialId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate desde,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate hasta,
      @Schema(allowableValues = {"historico", "sistema"}) @RequestParam(required = false)
          String fuente,
      @Schema(allowableValues = {"json", "xlsx"}) @RequestParam(defaultValue = "json")
          String formato) {
    ReporteCosto r =
        servicio.costo(
            agrupar,
            new Filtro(
                contratoId,
                frenteId,
                unidadObraId,
                paqueteId,
                clasificacionId,
                materialId,
                desde,
                hasta,
                fuente));
    return responder(r, Exportar::costo, "costo-por-" + r.agrupar(), formato);
  }

  @GetMapping("/presupuesto")
  @Operation(
      operationId = "reportePresupuesto",
      summary =
          "Presupuesto de material por lote con semáforo (aviso al 80% y al 100%); solo Programa"
              + " consume")
  @ApiResponse(
      responseCode = "200",
      content = {
        @Content(
            mediaType = "application/json",
            array = @ArraySchema(schema = @Schema(implementation = LotePresupuesto.class))),
        @Content(mediaType = XLSX)
      })
  ResponseEntity<?> presupuesto(
      @RequestParam(required = false) Long contratoId,
      @Schema(description = "Lotes separados por coma (avisos al capturar)")
          @RequestParam(required = false)
          List<Long> unidadIds,
      @Schema(description = "Solo los lotes del 80% en adelante")
          @RequestParam(defaultValue = "false")
          boolean avisos,
      @Schema(allowableValues = {"json", "xlsx"}) @RequestParam(defaultValue = "json")
          String formato) {
    return responder(
        servicio.presupuesto(contratoId, unidadIds, avisos),
        Exportar::presupuesto,
        "presupuesto-por-lote",
        formato);
  }

  @GetMapping("/valor-inventario")
  @Operation(
      operationId = "reporteValorInventario",
      summary = "Existencia valorizada por almacén y material (costo promedio sin IVA)")
  @ApiResponse(
      responseCode = "200",
      content = {
        @Content(
            mediaType = "application/json",
            schema = @Schema(implementation = ValorInventario.class)),
        @Content(mediaType = XLSX)
      })
  ResponseEntity<?> valorInventario(
      @RequestParam(required = false) Long almacenId,
      @Schema(allowableValues = {"json", "xlsx"}) @RequestParam(defaultValue = "json")
          String formato) {
    return responder(
        servicio.valorInventario(almacenId),
        Exportar::valorInventario,
        "valor-inventario",
        formato);
  }

  @GetMapping("/requisicion-vs-surtido")
  @Operation(
      operationId = "reporteRequisicionVsSurtido",
      summary = "Cada renglón de requisición contra lo surtido, apartado, por recibir y faltante")
  @ApiResponse(
      responseCode = "200",
      content = {
        @Content(
            mediaType = "application/json",
            array = @ArraySchema(schema = @Schema(implementation = SurtidoRequisicion.class))),
        @Content(mediaType = XLSX)
      })
  ResponseEntity<?> requisicionVsSurtido(
      @RequestParam(required = false) Long contratoId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate desde,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate hasta,
      @Schema(allowableValues = {"json", "xlsx"}) @RequestParam(defaultValue = "json")
          String formato) {
    return responder(
        servicio.requisicionVsSurtido(contratoId, desde, hasta),
        Exportar::requisicionVsSurtido,
        "requisicion-vs-surtido",
        formato);
  }

  @GetMapping("/estabilizacion")
  @Operation(
      operationId = "reporteEstabilizacion",
      summary =
          "Estabilización del piloto (H9): uso del almacén por día, capturas tardías, pendientes,"
              + " incidentes y el criterio del hito")
  @ApiResponse(
      responseCode = "200",
      content = {
        @Content(
            mediaType = "application/json",
            schema = @Schema(implementation = Estabilizacion.class)),
        @Content(mediaType = XLSX)
      })
  ResponseEntity<?> estabilizacion(
      @RequestParam long almacenId,
      @Schema(description = "Por omisión, la fecha de la apertura del almacén")
          @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate desde,
      @Schema(description = "Por omisión, hoy")
          @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate hasta,
      @Schema(allowableValues = {"json", "xlsx"}) @RequestParam(defaultValue = "json")
          String formato) {
    Estabilizacion e = estabilizacion.reporte(almacenId, desde, hasta);
    return responder(e, Exportar::estabilizacion, "estabilizacion-" + e.almacen(), formato);
  }

  /** JSON, o el mismo reporte como .xlsx descargable. */
  static <T> ResponseEntity<?> responder(
      T reporte, Function<T, byte[]> excel, String nombre, String formato) {
    if (formato == null || formato.equalsIgnoreCase("json")) {
      return ResponseEntity.ok(reporte);
    }
    if (!formato.equalsIgnoreCase("xlsx")) {
      throw ErrorNegocio.invalido("FORMATO_INVALIDO", "El formato es json o xlsx");
    }
    byte[] archivo = excel.apply(reporte);
    String limpio = nombre.replaceAll("[^A-Za-z0-9._-]+", "-") + "-" + LocalDate.now() + ".xlsx";
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(XLSX))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(limpio, StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(archivo);
  }
}

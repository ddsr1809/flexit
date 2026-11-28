package mx.constructora.reportes.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Folios;
import mx.constructora.compartido.Permisos;
import mx.constructora.inventario.ActividadAlmacen;
import mx.constructora.inventario.ActividadAlmacen.Actividad;
import mx.constructora.inventario.InventarioApi;
import mx.constructora.inventario.InventarioApi.AlmacenInfo;
import mx.constructora.reportes.dominio.CriterioEstabilizacion;
import mx.constructora.reportes.dominio.CriterioEstabilizacion.DiaUso;
import mx.constructora.reportes.dominio.CriterioEstabilizacion.Resultado;
import mx.constructora.soporte.SoporteApi;
import mx.constructora.soporte.SoporteApi.ResumenIncidentes;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reporte de estabilización del frente piloto (H9): cómo se usa el almacén desde su apertura, día
 * por día, y el criterio de aceptación del hito (ADR-015). Lo ven coordinación, dirección y
 * administración. No muestra costos.
 */
@Service
public class ServicioEstabilizacion {

  static final String VER =
      "hasAnyAuthority('"
          + Permisos.COSTO_VER_TODO
          + "', '"
          + Permisos.ADMINISTRAR
          + "', '"
          + Permisos.INCIDENTE_ATENDER
          + "')";

  /** Días máximos de un reporte. */
  static final int DIAS_MAXIMOS = 92;

  /** El criterio de aceptación de H9, punto por punto. */
  public record CriterioH9(
      @Schema(requiredMode = REQUIRED, description = "Días seguidos sin capturas tardías")
          int racha,
      @Schema(requiredMode = REQUIRED, description = "Días que pide el criterio (14)")
          int diasRequeridos,
      @Schema(requiredMode = REQUIRED, description = "Vales aplicados dentro de la racha")
          int valesEnRacha,
      @Schema(
              requiredMode = REQUIRED,
              description = "Dos semanas seguidas capturando a tiempo, con vales")
          boolean dosSemanasATiempo,
      @Schema(requiredMode = REQUIRED) int criticosAbiertos,
      @Schema(requiredMode = REQUIRED) boolean sinCriticos,
      @Schema(description = "Último mes cerrado (AAAA-MM)") String ultimoMesCerrado,
      @Schema(requiredMode = REQUIRED, description = "Ya se cerró un mes desde la apertura")
          boolean primerCierre,
      @Schema(requiredMode = REQUIRED) boolean cumple) {}

  /** Totales del periodo. */
  public record IndicadoresEstabilizacion(
      @Schema(requiredMode = REQUIRED) int documentos,
      @Schema(requiredMode = REQUIRED, description = "Vales aplicados") int salidas,
      @Schema(requiredMode = REQUIRED, description = "Recepciones aplicadas") int entradas,
      @Schema(requiredMode = REQUIRED) int tardios,
      @Schema(requiredMode = REQUIRED, description = "De 0 a 100") BigDecimal porcentajeTardios,
      @Schema(requiredMode = REQUIRED) int cancelados,
      @Schema(requiredMode = REQUIRED, description = "De 0 a 100")
          BigDecimal porcentajeCancelados,
      @Schema(requiredMode = REQUIRED, description = "Días sin ningún documento")
          int diasSinCaptura,
      @Schema(requiredMode = REQUIRED, description = "Sin aplicar ni cancelar, hoy")
          int pendientes,
      @Schema(requiredMode = REQUIRED, description = "Pendientes de más de 2 días")
          int pendientesViejos,
      @Schema(description = "Mediana de horas de captura a aplicación de los vales")
          BigDecimal medianaHorasVale,
      @Schema(requiredMode = REQUIRED) int prestamosAbiertos,
      @Schema(requiredMode = REQUIRED) int prestamosVencidos,
      @Schema(requiredMode = REQUIRED) int conteosCerrados,
      @Schema(requiredMode = REQUIRED, description = "Renglones de ajuste de los conteos")
          int ajustesDeConteo,
      @Schema(requiredMode = REQUIRED) int incidentesReportados,
      @Schema(requiredMode = REQUIRED) int incidentesResueltos,
      @Schema(requiredMode = REQUIRED) int mayoresAbiertos,
      @Schema(requiredMode = REQUIRED) int menoresAbiertos,
      @Schema(description = "Mediana de horas de reporte a resolución")
          BigDecimal medianaHorasResolucion) {}

  /** Un día del periodo. */
  public record DiaEstabilizacion(
      @Schema(requiredMode = REQUIRED) LocalDate fecha,
      @Schema(requiredMode = REQUIRED) int documentos,
      @Schema(requiredMode = REQUIRED) int salidas,
      @Schema(requiredMode = REQUIRED) int entradas,
      @Schema(requiredMode = REQUIRED) int tardios,
      @Schema(requiredMode = REQUIRED) int cancelados) {}

  /** Un documento capturado tarde, o uno que sigue sin aplicar. */
  public record DocumentoEstabilizacion(
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(requiredMode = REQUIRED) String tipo,
      @Schema(requiredMode = REQUIRED) String estado,
      @Schema(requiredMode = REQUIRED) LocalDate fecha,
      @Schema(description = "Día en que se capturó (solo los tardíos)") LocalDate capturado,
      @Schema(
              requiredMode = REQUIRED,
              description = "Tardío: días de la fecha a la captura; pendiente: días que lleva")
          int dias) {}

  /** Un incidente crítico abierto. */
  public record CriticoEstabilizacion(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(requiredMode = REQUIRED) String titulo,
      @Schema(requiredMode = REQUIRED) OffsetDateTime reportadoEn) {}

  /** El reporte completo. */
  public record Estabilizacion(
      @Schema(requiredMode = REQUIRED) long almacenId,
      @Schema(requiredMode = REQUIRED) String almacen,
      @Schema(description = "Fecha del saldo de apertura; vacío si el almacén no ha abierto")
          LocalDate apertura,
      @Schema(requiredMode = REQUIRED) LocalDate desde,
      @Schema(requiredMode = REQUIRED) LocalDate hasta,
      @Schema(requiredMode = REQUIRED, description = "Días que se toleran para capturar")
          int toleranciaDias,
      @Schema(requiredMode = REQUIRED) CriterioH9 criterio,
      @Schema(requiredMode = REQUIRED) IndicadoresEstabilizacion indicadores,
      @Schema(requiredMode = REQUIRED) List<DiaEstabilizacion> dias,
      @Schema(requiredMode = REQUIRED, description = "Hasta 100, del más tardío al menos")
          List<DocumentoEstabilizacion> tardios,
      @Schema(requiredMode = REQUIRED, description = "Hasta 100, del más viejo al más nuevo")
          List<DocumentoEstabilizacion> pendientes,
      @Schema(requiredMode = REQUIRED) List<CriticoEstabilizacion> criticos) {}

  private final InventarioApi inventario;
  private final ActividadAlmacen actividad;
  private final SoporteApi soporte;

  public ServicioEstabilizacion(
      InventarioApi inventario, ActividadAlmacen actividad, SoporteApi soporte) {
    this.inventario = inventario;
    this.actividad = actividad;
    this.soporte = soporte;
  }

  /**
   * Del almacén, de {@code desde} (por omisión, la apertura o hace 13 días) a {@code hasta} (por
   * omisión, hoy). El periodo no empieza antes de la apertura.
   */
  @PreAuthorize(VER)
  @Transactional(readOnly = true)
  public Estabilizacion reporte(long almacenId, LocalDate desde, LocalDate hasta) {
    AlmacenInfo almacen =
        inventario
            .almacen(almacenId)
            .orElseThrow(
                () -> ErrorNegocio.noEncontrado("ALMACEN_NO_ENCONTRADO", "No existe el almacén"));
    LocalDate hoy = LocalDate.now(Folios.ZONA);
    LocalDate apertura = actividad.apertura(almacenId).orElse(null);
    LocalDate fin = hasta == null || hasta.isAfter(hoy) ? hoy : hasta;
    LocalDate inicio = desde != null ? desde : apertura != null ? apertura : fin.minusDays(13);
    if (apertura != null && inicio.isBefore(apertura)) {
      inicio = apertura;
    }
    if (inicio.isAfter(fin)) {
      throw ErrorNegocio.invalido("PERIODO_INVALIDO", "La fecha inicial es posterior a la final");
    }
    if (ChronoUnit.DAYS.between(inicio, fin) >= DIAS_MAXIMOS) {
      throw ErrorNegocio.invalido(
          "PERIODO_LARGO", "El reporte es de " + DIAS_MAXIMOS + " días como máximo");
    }

    Actividad a =
        actividad.actividad(almacenId, inicio, fin, CriterioEstabilizacion.TOLERANCIA_DIAS);
    ResumenIncidentes incidentes = soporte.resumen(inicio, fin);
    String ultimoCierre = actividad.ultimoMesCerrado().orElse(null);
    boolean primerCierre =
        apertura != null
            && ultimoCierre != null
            && !YearMonth.parse(ultimoCierre).isBefore(YearMonth.from(apertura));
    Resultado r =
        CriterioEstabilizacion.evaluar(
            a.dias().stream().map(d -> new DiaUso(d.fecha(), d.salidas(), d.tardios())).toList(),
            incidentes.criticosAbiertos(),
            primerCierre);

    int documentos = a.dias().stream().mapToInt(ActividadAlmacen.Dia::documentos).sum();
    int tardios = a.dias().stream().mapToInt(ActividadAlmacen.Dia::tardios).sum();
    int cancelados = a.dias().stream().mapToInt(ActividadAlmacen.Dia::cancelados).sum();
    IndicadoresEstabilizacion indicadores =
        new IndicadoresEstabilizacion(
            documentos,
            a.dias().stream().mapToInt(ActividadAlmacen.Dia::salidas).sum(),
            a.dias().stream().mapToInt(ActividadAlmacen.Dia::entradas).sum(),
            tardios,
            porcentaje(tardios, documentos),
            cancelados,
            porcentaje(cancelados, documentos),
            (int) a.dias().stream().filter(d -> d.documentos() == 0).count(),
            a.totalPendientes(),
            (int) a.pendientes().stream().filter(p -> p.dias() > 2).count(),
            a.medianaHorasVale(),
            a.prestamosAbiertos(),
            a.prestamosVencidos(),
            a.conteosCerrados(),
            a.ajustesDeConteo(),
            incidentes.reportados(),
            incidentes.resueltos(),
            incidentes.mayoresAbiertos(),
            incidentes.menoresAbiertos(),
            incidentes.medianaHorasResolucion());

    return new Estabilizacion(
        almacen.id(),
        almacen.clave(),
        apertura,
        inicio,
        fin,
        CriterioEstabilizacion.TOLERANCIA_DIAS,
        new CriterioH9(
            r.racha(),
            CriterioEstabilizacion.DIAS_REQUERIDOS,
            r.valesEnRacha(),
            r.dosSemanasATiempo(),
            incidentes.criticosAbiertos(),
            r.sinCriticos(),
            ultimoCierre,
            r.primerCierre(),
            r.cumple()),
        indicadores,
        a.dias().stream()
            .map(
                d ->
                    new DiaEstabilizacion(
                        d.fecha(),
                        d.documentos(),
                        d.salidas(),
                        d.entradas(),
                        d.tardios(),
                        d.cancelados()))
            .toList(),
        a.tardios().stream()
            .map(
                t ->
                    new DocumentoEstabilizacion(
                        t.folio(), t.tipo(), t.estado(), t.fecha(), t.capturado(), t.dias()))
            .toList(),
        a.pendientes().stream()
            .map(
                p ->
                    new DocumentoEstabilizacion(
                        p.folio(), p.tipo(), p.estado(), p.fecha(), null, p.dias()))
            .toList(),
        incidentes.criticos().stream()
            .map(c -> new CriticoEstabilizacion(c.id(), c.folio(), c.titulo(), c.reportadoEn()))
            .toList());
  }

  /** De 0 a 100 con un decimal; 0 si no hay documentos. */
  static BigDecimal porcentaje(int parte, int total) {
    if (total == 0) {
      return BigDecimal.ZERO.setScale(1);
    }
    return BigDecimal.valueOf(parte * 100L)
        .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
  }
}

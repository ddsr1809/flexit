package mx.constructora.historico.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import mx.constructora.catalogo.CatalogoApi;
import mx.constructora.catalogo.CatalogoApi.ClasificacionInfo;
import mx.constructora.catalogo.CatalogoApi.MaterialInfo;
import mx.constructora.compartido.Autorizacion;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Permisos;
import mx.constructora.estructura.EstructuraApi;
import mx.constructora.estructura.EstructuraApi.ContratoInfo;
import mx.constructora.estructura.EstructuraApi.PaqueteInfo;
import mx.constructora.estructura.EstructuraApi.UnidadInfo;
import mx.constructora.historico.infraestructura.RepositorioHistorico;
import mx.constructora.historico.infraestructura.RepositorioHistorico.FilaAlta;
import mx.constructora.historico.infraestructura.RepositorioHistorico.FilaPago;
import mx.constructora.historico.infraestructura.RepositorioHistorico.FilaSalida;
import mx.constructora.organizacion.Personas;
import mx.constructora.organizacion.Personas.PersonaNombre;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La historia de un lote en el Excel: sus vales, sus pagos de destajo y quién estuvo dado de alta
 * en el IMSS en su contrato (demo de H5). Los nombres salen de las APIs de cada módulo.
 *
 * <p>Qué se ve depende del usuario: el residente solo sus contratos (ADR-006); importes solo con
 * permiso de costo o precio; trabajadores y altas del IMSS (datos personales) solo con {@code
 * personas:administrar}.
 */
@Service
public class ServicioHistorico {

  static final String VER =
      "hasAnyAuthority('"
          + Permisos.COSTO_VER_PROPIO
          + "','"
          + Permisos.COSTO_VER_TODO
          + "','"
          + Permisos.ADMINISTRAR
          + "')";

  // ------------------------------------------------------------------------------------- DTOs

  public record SalidaHistorica(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) LocalDate fecha,
      @Schema(requiredMode = REQUIRED, description = "salida, devolucion o reasignacion")
          String tipo,
      String documento,
      String folio,
      @Schema(requiredMode = REQUIRED) String material,
      @Schema(requiredMode = REQUIRED) String unidad,
      @Schema(requiredMode = REQUIRED) BigDecimal cantidad,
      String paquete,
      String clasificacion,
      String solicitante,
      @Schema(requiredMode = REQUIRED) boolean entregado,
      @Schema(description = "Sin IVA; vacío sin permiso de costo") BigDecimal costoUnitario,
      BigDecimal importe,
      @Schema(requiredMode = REQUIRED) boolean costoEstimado,
      @Schema(description = "El renglón del Excel decía varios lotes (\"32-43\")")
          String lotesTexto,
      String observaciones,
      @Schema(requiredMode = REQUIRED) String origen) {}

  public record PagoHistorico(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) int anio,
      @Schema(requiredMode = REQUIRED) List<Integer> semanas,
      String semanaTexto,
      String paquete,
      String concepto,
      BigDecimal importeConcepto,
      @Schema(description = "Vacío sin permiso de costo") BigDecimal monto,
      @Schema(requiredMode = REQUIRED) int parcial,
      @Schema(requiredMode = REQUIRED) boolean montoCompartido,
      @Schema(requiredMode = REQUIRED) boolean sobreImporte,
      @Schema(requiredMode = REQUIRED, description = "Quién cobró; vacío sin permiso de personas")
          List<String> trabajadores,
      @Schema(requiredMode = REQUIRED) String origen) {}

  public record AltaHistorica(
      @Schema(requiredMode = REQUIRED) LocalDate mes,
      @Schema(requiredMode = REQUIRED, description = "mes o anterior") String origen,
      @Schema(requiredMode = REQUIRED) String persona,
      String estatus,
      Integer dias) {}

  public record HistoriaUnidad(
      @Schema(requiredMode = REQUIRED) long unidadId,
      @Schema(requiredMode = REQUIRED) String clave,
      @Schema(requiredMode = REQUIRED) long contratoId,
      @Schema(requiredMode = REQUIRED) String contrato,
      @Schema(requiredMode = REQUIRED) List<SalidaHistorica> salidas,
      @Schema(requiredMode = REQUIRED) List<PagoHistorico> pagos,
      @Schema(
              requiredMode = REQUIRED,
              description = "Altas del IMSS en el contrato; vacía sin permiso de personas")
          List<AltaHistorica> altas,
      @Schema(description = "Σ importe de lo entregado; vacío sin permiso de costo")
          BigDecimal costoMaterial,
      @Schema(description = "Σ pagos de destajo; vacío sin permiso de costo") BigDecimal destajo,
      @Schema(requiredMode = REQUIRED) boolean veCostos,
      @Schema(requiredMode = REQUIRED) boolean vePersonas) {}

  // ------------------------------------------------------------------------------- servicio

  private final RepositorioHistorico repositorio;
  private final EstructuraApi estructura;
  private final CatalogoApi catalogo;
  private final Personas personas;

  public ServicioHistorico(
      RepositorioHistorico repositorio,
      EstructuraApi estructura,
      CatalogoApi catalogo,
      Personas personas) {
    this.repositorio = repositorio;
    this.estructura = estructura;
    this.catalogo = catalogo;
    this.personas = personas;
  }

  @PreAuthorize(VER)
  @Transactional(readOnly = true)
  public HistoriaUnidad deUnidad(long unidadId) {
    long empresa = ContextoEmpresa.requerida().empresaId();
    UnidadInfo unidad =
        estructura
            .unidad(unidadId)
            .filter(u -> estructura.contratoVisible(u.contratoId()))
            .orElseThrow(
                () -> ErrorNegocio.noEncontrado("UNIDAD_NO_ENCONTRADA", "No existe el lote"));
    boolean veCostos =
        Autorizacion.tieneAlguno(
            Permisos.COSTO_VER_TODO, Permisos.COSTO_VER_PROPIO, Permisos.PRECIO_VER);
    boolean vePersonas = Autorizacion.tiene(Permisos.PERSONAS_ADMINISTRAR);

    List<FilaSalida> salidas = repositorio.salidasDeUnidad(empresa, unidadId);
    List<FilaPago> pagos = repositorio.pagosDeUnidad(empresa, unidadId);
    List<FilaAlta> altas =
        vePersonas ? repositorio.altasDeContrato(empresa, unidad.contratoId()) : List.of();

    Map<Long, MaterialInfo> materiales =
        porId(
            catalogo.materiales(ids(salidas.stream().map(FilaSalida::materialId))),
            MaterialInfo::id);
    Set<Long> paquetesIds = new HashSet<>(ids(salidas.stream().map(FilaSalida::paqueteId)));
    paquetesIds.addAll(ids(pagos.stream().map(FilaPago::paqueteId)));
    Map<Long, PaqueteInfo> paquetes = porId(estructura.paquetes(paquetesIds), PaqueteInfo::id);
    Map<Integer, String> clasificaciones =
        catalogo.clasificaciones().stream()
            .collect(Collectors.toMap(ClasificacionInfo::id, ClasificacionInfo::nombre));
    Set<Long> personasIds = new HashSet<>();
    if (vePersonas) {
      personasIds.addAll(ids(salidas.stream().map(FilaSalida::solicitanteId)));
      pagos.forEach(p -> personasIds.addAll(lista(p.trabajadores())));
      altas.forEach(a -> personasIds.add(a.personaId()));
    }
    Map<Long, String> nombres =
        personas.nombres(personasIds).stream()
            .collect(Collectors.toMap(PersonaNombre::id, PersonaNombre::nombre));
    String contrato =
        estructura.contratos(List.of(unidad.contratoId())).stream()
            .findFirst()
            .map(ContratoInfo::codigo)
            .orElse("—");

    List<SalidaHistorica> salidasVista =
        salidas.stream()
            .map(
                s -> {
                  MaterialInfo m = materiales.get(s.materialId());
                  return new SalidaHistorica(
                      s.id(),
                      s.fecha(),
                      s.tipo(),
                      s.documento(),
                      s.folioExcel(),
                      m == null ? "—" : m.clave() + " · " + m.nombre(),
                      m == null ? "" : m.unidadControl(),
                      sinCeros(s.cantidad()),
                      nombrePaquete(paquetes.get(s.paqueteId()), s.paqueteTexto()),
                      s.clasificacionId() == null ? null : clasificaciones.get(s.clasificacionId()),
                      vePersonas
                          ? (s.solicitanteId() != null
                              ? nombres.get(s.solicitanteId())
                              : s.solicitanteTexto())
                          : null,
                      s.entregado(),
                      veCostos ? s.costoUnitario() : null,
                      veCostos ? s.importe() : null,
                      s.costoEstimado(),
                      s.lotesTexto(),
                      s.observaciones(),
                      s.hoja() + (s.filaExcel() == null ? "" : " fila " + s.filaExcel()));
                })
            .toList();
    List<PagoHistorico> pagosVista =
        pagos.stream()
            .map(
                p ->
                    new PagoHistorico(
                        p.id(),
                        p.anio(),
                        lista(p.semanas()).stream().map(Long::intValue).toList(),
                        p.semanaTexto(),
                        nombrePaquete(paquetes.get(p.paqueteId()), p.seccionTexto()),
                        p.conceptoTexto(),
                        veCostos ? p.importeConcepto() : null,
                        veCostos ? p.monto() : null,
                        p.parcial(),
                        p.montoCompartido(),
                        p.sobreImporte(),
                        vePersonas
                            ? lista(p.trabajadores()).stream()
                                .map(id -> nombres.getOrDefault(id, "—"))
                                .toList()
                            : List.of(),
                        p.hoja() + " " + p.celda()))
            .toList();
    List<AltaHistorica> altasVista =
        altas.stream()
            .map(
                a ->
                    new AltaHistorica(
                        a.mes(),
                        a.origen(),
                        nombres.getOrDefault(a.personaId(), "—"),
                        a.estatus(),
                        a.dias()))
            .toList();
    BigDecimal costo =
        veCostos
            ? salidas.stream()
                .filter(FilaSalida::entregado)
                .map(FilaSalida::importe)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
            : null;
    BigDecimal destajo =
        veCostos
            ? pagos.stream().map(FilaPago::monto).reduce(BigDecimal.ZERO, BigDecimal::add)
            : null;
    return new HistoriaUnidad(
        unidad.id(),
        unidad.clave(),
        unidad.contratoId(),
        contrato,
        salidasVista,
        pagosVista,
        altasVista,
        costo,
        destajo,
        veCostos,
        vePersonas);
  }

  /** {@code 100.00000000} → {@code 100}, sin pasar a notación científica. */
  private static BigDecimal sinCeros(BigDecimal valor) {
    BigDecimal sin = valor.stripTrailingZeros();
    return sin.scale() < 0 ? sin.setScale(0) : sin;
  }

  private static String nombrePaquete(PaqueteInfo paquete, String texto) {
    return paquete != null ? paquete.clave() + " · " + paquete.nombre() : texto;
  }

  private static List<Long> lista(String ids) {
    return ids == null || ids.isBlank()
        ? List.of()
        : Arrays.stream(ids.split(",")).map(String::trim).map(Long::valueOf).toList();
  }

  private static Set<Long> ids(Stream<Long> ids) {
    return ids.filter(Objects::nonNull).collect(Collectors.toSet());
  }

  private static <T> Map<Long, T> porId(List<T> lista, Function<T, Long> id) {
    Map<Long, T> mapa = new LinkedHashMap<>();
    lista.forEach(e -> mapa.put(id.apply(e), e));
    return mapa;
  }
}

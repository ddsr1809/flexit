package mx.constructora.importacion.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import mx.constructora.catalogo.CatalogoApi;
import mx.constructora.catalogo.CatalogoApi.ClasificacionInfo;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Permisos;
import mx.constructora.estructura.EstructuraApi;
import mx.constructora.estructura.EstructuraApi.ContratoInfo;
import mx.constructora.estructura.EstructuraApi.FrenteInfo;
import mx.constructora.estructura.EstructuraApi.UnidadDetalle;
import mx.constructora.estructura.EstructuraApi.UnidadInfo;
import mx.constructora.historico.ConsultaHistorico;
import mx.constructora.historico.ConsultaHistorico.SalidaDeCarga;
import mx.constructora.importacion.dominio.ConciliacionHoja;
import mx.constructora.importacion.dominio.ConciliacionHoja.Motivo;
import mx.constructora.importacion.dominio.ConciliacionHoja.SalidaSistema;
import mx.constructora.importacion.dominio.TipoCarga;
import mx.constructora.importacion.dominio.xlsx.ArchivoInvalido;
import mx.constructora.importacion.dominio.xlsx.LibroXlsx;
import mx.constructora.importacion.infraestructura.RepositorioMigracion;
import mx.constructora.importacion.infraestructura.RepositorioMigracion.FilaCarga;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Concilia un contrato contra la hoja de su frente en el Excel de almacén (issue 38, criterio de
 * H6): el costo de Programa de cada lote en el sistema (histórico) contra lo que diría la hoja ya
 * recalculada, con cada peso de diferencia explicado (IVA, promedio simple contra ponderado, lotes
 * que no son columna, renglones repartidos o corregidos en la cuarentena…).
 *
 * <p>Usa el mismo archivo de la carga vigente del histórico de Salidas: lo que se concilia es justo
 * lo que se migró. Lo capturado en el sistema después del corte no está en la hoja.
 */
@Service
public class ServicioConciliacionContrato {

  static final String VER =
      "hasAnyAuthority('" + Permisos.COSTO_VER_TODO + "', '" + Permisos.ADMINISTRAR + "')";

  /** Renglones de detalle como máximo en la respuesta JSON (el Excel los lleva todos). */
  static final int RENGLONES_MAXIMOS = 500;

  public record Efecto(
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {
                "SIN_COLUMNA",
                "REPARTO",
                "LOTE_CORREGIDO",
                "MATERIAL",
                "RAZON",
                "FRENTE",
                "NO_ENTREGADO",
                "CANTIDAD",
                "SOLO_EN_HOJA",
                "SIN_PRECIO",
                "PONDERACION",
                "IVA"
              })
          String motivo,
      @Schema(requiredMode = REQUIRED) String descripcion,
      @Schema(requiredMode = REQUIRED, description = "Con signo: lo que suma o resta al sistema")
          BigDecimal importe) {}

  public record LoteConciliado(
      @Schema(requiredMode = REQUIRED) String clave,
      Long unidadObraId,
      @Schema(requiredMode = REQUIRED, description = "El lote es columna de la hoja del frente")
          boolean columnaEnHoja,
      @Schema(requiredMode = REQUIRED, description = "Programa del histórico, sin IVA, ponderado")
          BigDecimal sistema,
      @Schema(requiredMode = REQUIRED) List<Efecto> efectos,
      @Schema(requiredMode = REQUIRED, description = "Lo que diría la hoja recalculada")
          BigDecimal hoja,
      @Schema(requiredMode = REQUIRED, description = "Centavos por redondear cada efecto")
          BigDecimal redondeo) {}

  public record RenglonDiferencia(
      @Schema(requiredMode = REQUIRED) int filaExcel,
      @Schema(requiredMode = REQUIRED) String lote,
      String material,
      @Schema(requiredMode = REQUIRED) String motivo,
      BigDecimal cantidad,
      @Schema(requiredMode = REQUIRED) BigDecimal importe,
      String nota) {}

  public record ConciliacionContrato(
      @Schema(requiredMode = REQUIRED) long contratoId,
      @Schema(requiredMode = REQUIRED) String contrato,
      @Schema(requiredMode = REQUIRED) long cargaId,
      @Schema(requiredMode = REQUIRED) String archivo,
      @Schema(requiredMode = REQUIRED) String hoja,
      @Schema(requiredMode = REQUIRED) String razon,
      @Schema(requiredMode = REQUIRED) int columnasDeLote,
      @Schema(requiredMode = REQUIRED) BigDecimal sistema,
      @Schema(requiredMode = REQUIRED) BigDecimal hojaTotal,
      @Schema(requiredMode = REQUIRED, description = "Por motivo, del mayor al menor")
          List<Efecto> efectos,
      @Schema(requiredMode = REQUIRED) List<LoteConciliado> lotes,
      @Schema(requiredMode = REQUIRED, description = "Los renglones que más pesan, hasta 500")
          List<RenglonDiferencia> renglones,
      @Schema(requiredMode = REQUIRED) boolean recortada,
      @Schema(requiredMode = REQUIRED) List<String> avisos) {}

  private final RepositorioMigracion repositorio;
  private final ConsultaHistorico historico;
  private final EstructuraApi estructura;
  private final CatalogoApi catalogo;

  public ServicioConciliacionContrato(
      RepositorioMigracion repositorio,
      ConsultaHistorico historico,
      EstructuraApi estructura,
      CatalogoApi catalogo) {
    this.repositorio = repositorio;
    this.historico = historico;
    this.estructura = estructura;
    this.catalogo = catalogo;
  }

  /**
   * @param completa con todos los renglones de detalle (para Excel)
   */
  @PreAuthorize(VER)
  @Transactional(readOnly = true)
  public ConciliacionContrato conciliar(long contratoId, boolean completa) {
    long empresa = ContextoEmpresa.requerida().empresaId();
    ContratoInfo contrato =
        estructura.contratos(List.of(contratoId)).stream()
            .findFirst()
            .orElseThrow(
                () -> ErrorNegocio.noEncontrado("CONTRATO_NO_ENCONTRADO", "No existe el contrato"));
    if (contrato.frenteId() == null) {
      throw ErrorNegocio.invalido("CONTRATO_SIN_FRENTE", "El contrato no tiene frente");
    }
    FrenteInfo frente = estructura.frente(contrato.frenteId()).orElseThrow();
    FilaCarga carga =
        repositorio.confirmadas(empresa, TipoCarga.HIST_SALIDAS.name()).stream()
            .findFirst()
            .orElseThrow(
                () ->
                    ErrorNegocio.conflicto(
                        "SIN_HISTORICO",
                        "Todavía no hay una carga confirmada de la hoja Salidas para conciliar"));
    byte[] archivo =
        repositorio
            .contenidoArchivo(empresa, carga.sha256())
            .orElseThrow(
                () ->
                    ErrorNegocio.conflicto(
                        "SIN_ARCHIVO", "No se encuentra el archivo de la carga " + carga.id()));

    int programa =
        catalogo.clasificaciones().stream()
            .filter(c -> c.clave().equals("PROGRAMA"))
            .map(ClasificacionInfo::id)
            .findFirst()
            .orElse(-1);
    List<SalidaDeCarga> salidas = historico.salidasDeCarga(carga.id(), frente.id());
    Map<Long, String> claves =
        estructura
            .unidades(
                salidas.stream()
                    .map(SalidaDeCarga::unidadObraId)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(UnidadInfo::id, UnidadInfo::clave));
    List<SalidaSistema> sistema =
        salidas.stream()
            .filter(
                s ->
                    s.entregado() && s.clasificacionId() != null && s.clasificacionId() == programa)
            .map(
                s ->
                    new SalidaSistema(
                        s.filaExcel(),
                        s.unidadObraId() == null ? null : claves.get(s.unidadObraId()),
                        s.cantidad(),
                        s.importe(),
                        s.lotesTexto() != null))
            .toList();
    List<UnidadDetalle> lotes = estructura.unidadesDeContrato(contratoId);
    Map<String, Long> idDeLote =
        lotes.stream()
            .collect(Collectors.toMap(UnidadDetalle::clave, UnidadDetalle::id, (a, b) -> a));

    ConciliacionHoja.Resultado r = leer(archivo, frente.clave(), idDeLote.keySet(), sistema);

    Map<Motivo, BigDecimal> porMotivo = new EnumMap<>(Motivo.class);
    BigDecimal totalSistema = BigDecimal.ZERO;
    BigDecimal totalHoja = BigDecimal.ZERO;
    List<LoteConciliado> conciliados = new ArrayList<>();
    for (ConciliacionHoja.Lote l : r.lotes()) {
      if (l.sistema().signum() == 0 && l.hoja().signum() == 0 && l.efectos().isEmpty()) {
        continue;
      }
      totalSistema = totalSistema.add(l.sistema());
      totalHoja = totalHoja.add(l.hoja());
      l.efectos().forEach((m, v) -> porMotivo.merge(m, v, BigDecimal::add));
      conciliados.add(
          new LoteConciliado(
              l.clave(),
              idDeLote.get(l.clave()),
              l.columnaEnHoja(),
              l.sistema(),
              efectos(l.efectos()),
              l.hoja(),
              l.redondeo()));
    }
    BigDecimal redondeo =
        conciliados.stream().map(LoteConciliado::redondeo).reduce(BigDecimal.ZERO, BigDecimal::add);
    List<String> avisos = new ArrayList<>(r.avisos());
    if (redondeo.signum() != 0) {
      avisos.add(
          "Redondeo de centavos al sumar los efectos lote por lote: " + redondeo.toPlainString());
    }
    List<Efecto> efectos = efectos(porMotivo);
    List<Efecto> ordenados = new ArrayList<>(efectos);
    ordenados.sort((a, b) -> b.importe().abs().compareTo(a.importe().abs()));

    int limite = completa ? Integer.MAX_VALUE : RENGLONES_MAXIMOS;
    Set<String> delContrato = idDeLote.keySet();
    List<RenglonDiferencia> renglones = new ArrayList<>();
    boolean recortada = false;
    for (ConciliacionHoja.Detalle d : r.detalle()) {
      if (!delContrato.contains(d.lote())) {
        continue;
      }
      if (renglones.size() >= limite) {
        recortada = true;
        break;
      }
      renglones.add(
          new RenglonDiferencia(
              d.filaExcel(),
              d.lote(),
              d.material(),
              d.motivo().name(),
              d.cantidad(),
              d.importe(),
              d.nota()));
    }
    return new ConciliacionContrato(
        contratoId,
        contrato.codigo(),
        carga.id(),
        carga.archivo(),
        r.hoja(),
        r.razon(),
        r.columnasDeLote(),
        totalSistema,
        totalHoja,
        ordenados,
        conciliados,
        renglones,
        recortada,
        avisos);
  }

  private static List<Efecto> efectos(Map<Motivo, BigDecimal> mapa) {
    List<Efecto> lista = new ArrayList<>();
    mapa.forEach((m, v) -> lista.add(new Efecto(m.name(), m.descripcion(), v)));
    return lista;
  }

  private static ConciliacionHoja.Resultado leer(
      byte[] archivo, String hoja, Set<String> lotes, List<SalidaSistema> sistema) {
    Path temporal = null;
    try {
      temporal = Files.createTempFile("conciliacion-", ".xlsx");
      Files.write(temporal, archivo);
      try (LibroXlsx libro = LibroXlsx.abrir(temporal)) {
        return ConciliacionHoja.conciliar(libro, hoja, lotes, sistema);
      }
    } catch (ArchivoInvalido | IllegalArgumentException e) {
      throw ErrorNegocio.invalido("HOJA_NO_CONCILIABLE", e.getMessage());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      if (temporal != null) {
        try {
          Files.deleteIfExists(temporal);
        } catch (IOException e) {
          // El sistema operativo limpia el temporal.
        }
      }
    }
  }
}

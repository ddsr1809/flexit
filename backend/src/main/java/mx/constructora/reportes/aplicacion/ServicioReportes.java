package mx.constructora.reportes.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import mx.constructora.abastecimiento.AbastecimientoApi;
import mx.constructora.abastecimiento.AbastecimientoApi.SurtidoRequisicion;
import mx.constructora.catalogo.CatalogoApi;
import mx.constructora.catalogo.CatalogoApi.ClasificacionInfo;
import mx.constructora.catalogo.CatalogoApi.MaterialInfo;
import mx.constructora.compartido.Autorizacion;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Permisos;
import mx.constructora.estructura.EstructuraApi;
import mx.constructora.estructura.EstructuraApi.ContratoInfo;
import mx.constructora.estructura.EstructuraApi.FrenteInfo;
import mx.constructora.estructura.EstructuraApi.UnidadDetalle;
import mx.constructora.estructura.EstructuraApi.UnidadInfo;
import mx.constructora.inventario.InventarioApi;
import mx.constructora.inventario.InventarioApi.AlmacenInfo;
import mx.constructora.reportes.dominio.Semaforo;
import mx.constructora.reportes.infraestructura.RepositorioReportes;
import mx.constructora.reportes.infraestructura.RepositorioReportes.FilaCosto;
import mx.constructora.reportes.infraestructura.RepositorioReportes.FilaDestajo;
import mx.constructora.reportes.infraestructura.RepositorioReportes.FilaExistencia;
import mx.constructora.reportes.infraestructura.RepositorioReportes.FilaLoteClasificacion;
import mx.constructora.reportes.infraestructura.RepositorioReportes.FilaPresupuesto;
import mx.constructora.reportes.infraestructura.RepositorioReportes.Filtro;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reportes de costo de obra que reemplazan las hojas por frente (CORCELES, PALERMO, GRANADA): por
 * contrato, por lote y clasificación, y agrupados como se pida. Suman el histórico del Excel y lo
 * del sistema ({@code reportes.v_costo_unidad}, ADR-011), con el costo sin IVA y ponderado. Además:
 * presupuesto por lote con semáforo, valor del inventario y requisición contra surtido.
 *
 * <p>El residente solo ve sus contratos ({@code costo:ver-propio}); coordinación, dirección y
 * administración, todos ({@code costo:ver-todo}).
 */
@Service
public class ServicioReportes {

  static final String VER_COSTOS =
      "hasAnyAuthority('" + Permisos.COSTO_VER_PROPIO + "', '" + Permisos.COSTO_VER_TODO + "')";
  static final String VER_INVENTARIO =
      "hasAnyAuthority('" + Permisos.COSTO_VER_TODO + "', '" + Permisos.PRECIO_VER + "')";
  static final String VER_SURTIDO =
      "hasAnyAuthority('"
          + Permisos.REQUISICION_CREAR
          + "', '"
          + Permisos.DOCUMENTO_AUTORIZAR
          + "', '"
          + Permisos.PEDIDO_CREAR
          + "', '"
          + Permisos.COSTO_VER_PROPIO
          + "', '"
          + Permisos.COSTO_VER_TODO
          + "')";

  // ------------------------------------------------------------------------------ DTOs

  public record FilaReporteCosto(
      @Schema(description = "Id del contrato, lote, paquete…; o el mes (2026-07). Vacío: sin dato")
          String llave,
      @Schema(requiredMode = REQUIRED) String clave,
      @Schema(requiredMode = REQUIRED) String nombre,
      @Schema(requiredMode = REQUIRED, description = "Del Excel (junio a la fecha del corte)")
          BigDecimal historico,
      @Schema(requiredMode = REQUIRED, description = "De los vales del sistema") BigDecimal sistema,
      @Schema(requiredMode = REQUIRED) BigDecimal total,
      @Schema(description = "Solo al agrupar por material") BigDecimal cantidad,
      String unidad,
      @Schema(requiredMode = REQUIRED, description = "Incluye renglones con costo estimado")
          boolean estimado,
      @Schema(requiredMode = REQUIRED) int movimientos) {}

  public record ReporteCosto(
      @Schema(requiredMode = REQUIRED) String agrupar,
      @Schema(requiredMode = REQUIRED) List<FilaReporteCosto> filas,
      @Schema(requiredMode = REQUIRED) BigDecimal historico,
      @Schema(requiredMode = REQUIRED) BigDecimal sistema,
      @Schema(requiredMode = REQUIRED) BigDecimal total) {}

  public record ColumnaClasificacion(
      @Schema(requiredMode = REQUIRED) int id,
      @Schema(requiredMode = REQUIRED) String clave,
      @Schema(requiredMode = REQUIRED) String nombre) {}

  /** Un lote (o "Sin asignar") con su costo por clasificación, destajo y presupuesto. */
  public record LoteCosto(
      @Schema(description = "Vacío: salidas sin lote (\"Sin asignar\") o el renglón de totales")
          Long unidadObraId,
      @Schema(requiredMode = REQUIRED) String clave,
      String prototipo,
      @Schema(requiredMode = REQUIRED, description = "Material, en el orden de las clasificaciones")
          List<BigDecimal> porClasificacion,
      @Schema(requiredMode = REQUIRED) BigDecimal historico,
      @Schema(requiredMode = REQUIRED) BigDecimal sistema,
      @Schema(requiredMode = REQUIRED) BigDecimal material,
      @Schema(requiredMode = REQUIRED) BigDecimal destajo,
      @Schema(requiredMode = REQUIRED, description = "Material + destajo") BigDecimal total,
      BigDecimal presupuesto,
      @Schema(requiredMode = REQUIRED, description = "Material de Programa") BigDecimal consumido,
      BigDecimal porcentaje,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"sin", "verde", "amarillo", "rojo"})
          String semaforo,
      @Schema(requiredMode = REQUIRED) boolean estimado) {}

  public record CostoContrato(
      @Schema(requiredMode = REQUIRED) long contratoId,
      @Schema(requiredMode = REQUIRED) String contrato,
      @Schema(requiredMode = REQUIRED) List<ColumnaClasificacion> clasificaciones,
      @Schema(requiredMode = REQUIRED) List<LoteCosto> lotes,
      @Schema(requiredMode = REQUIRED) LoteCosto totales) {}

  public record ResumenContrato(
      @Schema(requiredMode = REQUIRED) long contratoId,
      @Schema(requiredMode = REQUIRED) String contrato,
      String frente,
      @Schema(requiredMode = REQUIRED) String estado,
      @Schema(requiredMode = REQUIRED) BigDecimal historico,
      @Schema(requiredMode = REQUIRED) BigDecimal sistema,
      @Schema(requiredMode = REQUIRED) BigDecimal material,
      @Schema(requiredMode = REQUIRED) BigDecimal destajo,
      @Schema(requiredMode = REQUIRED) BigDecimal total,
      @Schema(description = "Suma de los lotes con presupuesto") BigDecimal presupuesto,
      @Schema(requiredMode = REQUIRED, description = "Programa de los lotes con presupuesto")
          BigDecimal consumido,
      BigDecimal porcentaje,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"sin", "verde", "amarillo", "rojo"})
          String semaforo,
      @Schema(requiredMode = REQUIRED) int lotes,
      @Schema(requiredMode = REQUIRED, description = "Lotes del 80% al 100%") int lotesEnAviso,
      @Schema(requiredMode = REQUIRED, description = "Lotes en o arriba del 100%")
          int lotesRebasados) {}

  public record LotePresupuesto(
      @Schema(requiredMode = REQUIRED) long contratoId,
      @Schema(requiredMode = REQUIRED) String contrato,
      @Schema(requiredMode = REQUIRED) long unidadObraId,
      @Schema(requiredMode = REQUIRED) String clave,
      String prototipo,
      BigDecimal presupuesto,
      @Schema(requiredMode = REQUIRED) BigDecimal consumido,
      @Schema(description = "Presupuesto − consumido (negativo si se rebasó)") BigDecimal restante,
      BigDecimal porcentaje,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"sin", "verde", "amarillo", "rojo"})
          String semaforo) {}

  public record ExistenciaValorizada(
      @Schema(requiredMode = REQUIRED) long almacenId,
      @Schema(requiredMode = REQUIRED) String almacen,
      @Schema(requiredMode = REQUIRED) long materialId,
      @Schema(requiredMode = REQUIRED) String clave,
      @Schema(requiredMode = REQUIRED) String material,
      @Schema(requiredMode = REQUIRED) String unidad,
      @Schema(requiredMode = REQUIRED) BigDecimal existencia,
      @Schema(requiredMode = REQUIRED) BigDecimal reservado,
      @Schema(requiredMode = REQUIRED) BigDecimal prestado,
      @Schema(requiredMode = REQUIRED) BigDecimal disponible,
      @Schema(requiredMode = REQUIRED) BigDecimal costoPromedio,
      @Schema(requiredMode = REQUIRED) BigDecimal valor) {}

  public record ValorInventario(
      @Schema(requiredMode = REQUIRED) List<ExistenciaValorizada> materiales,
      @Schema(requiredMode = REQUIRED) BigDecimal valor) {}

  // ------------------------------------------------------------------------------ Servicio

  private final RepositorioReportes repositorio;
  private final EstructuraApi estructura;
  private final CatalogoApi catalogo;
  private final InventarioApi inventario;
  private final AbastecimientoApi abastecimiento;

  public ServicioReportes(
      RepositorioReportes repositorio,
      EstructuraApi estructura,
      CatalogoApi catalogo,
      InventarioApi inventario,
      AbastecimientoApi abastecimiento) {
    this.repositorio = repositorio;
    this.estructura = estructura;
    this.catalogo = catalogo;
    this.inventario = inventario;
    this.abastecimiento = abastecimiento;
  }

  // ------------------------------------------------------------------------------ Costo agrupado

  @PreAuthorize(VER_COSTOS)
  @Transactional(readOnly = true)
  public ReporteCosto costo(String agrupar, Filtro filtro) {
    String clave = agrupar == null ? "contrato" : agrupar.trim().toLowerCase(java.util.Locale.ROOT);
    if (!RepositorioReportes.AGRUPACIONES.containsKey(clave)) {
      throw ErrorNegocio.invalido(
          "AGRUPACION_INVALIDA",
          "Se agrupa por contrato, frente, unidad, paquete, clasificacion, material o mes");
    }
    if (filtro.fuente() != null && !Set.of("historico", "sistema").contains(filtro.fuente())) {
      throw ErrorNegocio.invalido("FUENTE_INVALIDA", "La fuente es historico o sistema");
    }
    exigirContratoVisible(filtro.contratoId());
    List<FilaCosto> filas = repositorio.costo(empresa(), clave, filtro, alcance());
    Nombres nombres = nombres(clave, filas);
    List<FilaReporteCosto> lista = new ArrayList<>();
    BigDecimal historico = BigDecimal.ZERO;
    BigDecimal sistema = BigDecimal.ZERO;
    for (FilaCosto f : filas) {
      historico = historico.add(f.historico());
      sistema = sistema.add(f.sistema());
      String[] n = nombres.de(f.llave());
      lista.add(
          new FilaReporteCosto(
              f.llave(),
              n[0],
              n[1],
              f.historico(),
              f.sistema(),
              f.total(),
              clave.equals("material") ? f.cantidad() : null,
              clave.equals("material") ? n[2] : null,
              f.estimado(),
              f.movimientos()));
    }
    return new ReporteCosto(clave, lista, historico, sistema, historico.add(sistema));
  }

  /** Clave, nombre y unidad de cada llave, según la agrupación. */
  private interface Nombres {
    String[] de(String llave);
  }

  private Nombres nombres(String agrupar, List<FilaCosto> filas) {
    Set<Long> ids =
        filas.stream()
            .map(FilaCosto::llave)
            .filter(Objects::nonNull)
            .filter(l -> !agrupar.equals("mes"))
            .map(Long::valueOf)
            .collect(Collectors.toSet());
    String sinDato =
        switch (agrupar) {
          case "unidad" -> "Sin asignar";
          case "contrato" -> "Sin contrato";
          case "frente" -> "Del almacén (sin frente)";
          case "paquete" -> "Sin paquete";
          case "clasificacion" -> "Sin clasificación";
          default -> "—";
        };
    Map<Long, String[]> mapa = new HashMap<>();
    switch (agrupar) {
      case "contrato" ->
          estructura.contratos(ids).forEach(c -> mapa.put(c.id(), new String[] {c.codigo(), ""}));
      case "frente" ->
          estructura.frentes().forEach(f -> mapa.put(f.id(), new String[] {f.clave(), f.nombre()}));
      case "unidad" -> {
        List<UnidadInfo> unidades = estructura.unidades(ids);
        Map<Long, String> contratos =
            estructura
                .contratos(
                    unidades.stream().map(UnidadInfo::contratoId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(ContratoInfo::id, ContratoInfo::codigo));
        unidades.forEach(
            u ->
                mapa.put(
                    u.id(), new String[] {u.clave(), contratos.getOrDefault(u.contratoId(), "")}));
      }
      case "paquete" ->
          estructura
              .paquetes(ids)
              .forEach(p -> mapa.put(p.id(), new String[] {p.clave(), p.nombre()}));
      case "clasificacion" ->
          catalogo
              .clasificaciones()
              .forEach(c -> mapa.put((long) c.id(), new String[] {c.clave(), c.nombre()}));
      case "material" ->
          catalogo
              .materiales(ids)
              .forEach(
                  m -> mapa.put(m.id(), new String[] {m.clave(), m.nombre(), m.unidadControl()}));
      default -> {}
    }
    return llave -> {
      if (llave == null) {
        return new String[] {"—", sinDato, null};
      }
      if (agrupar.equals("mes")) {
        return new String[] {llave, llave, null};
      }
      String[] n = mapa.get(Long.valueOf(llave));
      return n == null
          ? new String[] {llave, "", null}
          : new String[] {n[0], n[1], n.length > 2 ? n[2] : null};
    };
  }

  // ------------------------------------------------------------------------------ Por contrato

  /** Un renglón por contrato visible: material (histórico y sistema), destajo y presupuesto. */
  @PreAuthorize(VER_COSTOS)
  @Transactional(readOnly = true)
  public List<ResumenContrato> contratos() {
    Collection<Long> alcance = alcance();
    List<ContratoInfo> contratos =
        estructura.todosLosContratos().stream()
            .filter(c -> alcance == null || alcance.contains(c.id()))
            .toList();
    Map<Long, FilaCosto> costo =
        repositorio.costo(empresa(), "contrato", filtroVacio(), alcance).stream()
            .filter(f -> f.llave() != null)
            .collect(Collectors.toMap(f -> Long.valueOf(f.llave()), Function.identity()));
    Map<Long, BigDecimal> destajo =
        repositorio.destajoPorContrato(empresa(), alcance).stream()
            .collect(Collectors.toMap(FilaDestajo::llave, FilaDestajo::monto));
    Map<Long, List<FilaPresupuesto>> presupuestos =
        repositorio.presupuesto(empresa(), null, null, alcance, false).stream()
            .collect(Collectors.groupingBy(FilaPresupuesto::contratoId));
    Map<Long, String> frentes =
        estructura.frentes().stream().collect(Collectors.toMap(FrenteInfo::id, FrenteInfo::nombre));

    List<ResumenContrato> lista = new ArrayList<>();
    for (ContratoInfo c : contratos) {
      FilaCosto f = costo.get(c.id());
      BigDecimal d = destajo.getOrDefault(c.id(), BigDecimal.ZERO);
      List<FilaPresupuesto> lotes = presupuestos.getOrDefault(c.id(), List.of());
      if (f == null && d.signum() == 0 && !c.activo()) {
        continue;
      }
      BigDecimal presupuesto = null;
      BigDecimal consumido = BigDecimal.ZERO;
      int aviso = 0;
      int rebasados = 0;
      for (FilaPresupuesto p : lotes) {
        if (p.presupuesto() == null) {
          continue;
        }
        presupuesto = (presupuesto == null ? BigDecimal.ZERO : presupuesto).add(p.presupuesto());
        consumido = consumido.add(p.consumido());
        switch (Semaforo.de(p.consumido(), p.presupuesto())) {
          case AMARILLO -> aviso++;
          case ROJO -> rebasados++;
          default -> {}
        }
      }
      BigDecimal material = f == null ? BigDecimal.ZERO : f.total();
      lista.add(
          new ResumenContrato(
              c.id(),
              c.codigo(),
              c.frenteId() == null ? null : frentes.get(c.frenteId()),
              c.estado(),
              f == null ? BigDecimal.ZERO : f.historico(),
              f == null ? BigDecimal.ZERO : f.sistema(),
              material,
              d,
              material.add(d),
              presupuesto,
              consumido,
              Semaforo.porcentaje(consumido, presupuesto),
              Semaforo.de(consumido, presupuesto).clave(),
              lotes.size(),
              aviso,
              rebasados));
    }
    lista.sort(Comparator.comparing(ResumenContrato::total).reversed());
    return lista;
  }

  /**
   * El costo de cada lote del contrato por clasificación (Programa, Garantía Ruba…), con el destajo
   * pagado y el presupuesto de material: lo que mostraban las hojas por frente, pero sin IVA,
   * ponderado y con las salidas sin lote en "Sin asignar".
   */
  @PreAuthorize(VER_COSTOS)
  @Transactional(readOnly = true)
  public CostoContrato costoPorLote(long contratoId) {
    exigirContratoVisible(contratoId);
    ContratoInfo contrato =
        estructura.contratos(List.of(contratoId)).stream()
            .findFirst()
            .orElseThrow(ServicioReportes::contratoNoEncontrado);
    List<ClasificacionInfo> clasificaciones = catalogo.clasificaciones();
    Map<Integer, Integer> columna = new HashMap<>();
    for (int i = 0; i < clasificaciones.size(); i++) {
      columna.put(clasificaciones.get(i).id(), i);
    }
    int programa =
        clasificaciones.stream()
            .filter(c -> c.clave().equals("PROGRAMA"))
            .findFirst()
            .map(c -> columna.get(c.id()))
            .orElse(-1);

    Map<Long, Acumulado> porLote = new LinkedHashMap<>();
    List<UnidadDetalle> lotes = estructura.unidadesDeContrato(contratoId);
    for (UnidadDetalle u : lotes) {
      porLote.put(u.id(), new Acumulado(clasificaciones.size()));
    }
    Acumulado sinAsignar = new Acumulado(clasificaciones.size());
    for (FilaLoteClasificacion f : repositorio.costoPorLote(empresa(), contratoId)) {
      Acumulado a =
          f.unidadObraId() == null
              ? sinAsignar
              : porLote.computeIfAbsent(
                  f.unidadObraId(), k -> new Acumulado(clasificaciones.size()));
      a.sumar(
          f.clasificacionId() == null ? -1 : columna.getOrDefault(f.clasificacionId(), -1),
          f.fuente(),
          f.importe(),
          f.estimado());
    }
    for (FilaDestajo d : repositorio.destajoPorLote(empresa(), contratoId)) {
      Acumulado a =
          d.llave() == null
              ? sinAsignar
              : porLote.computeIfAbsent(d.llave(), k -> new Acumulado(clasificaciones.size()));
      a.destajo = a.destajo.add(d.monto());
    }

    Map<Long, UnidadDetalle> detalle =
        lotes.stream().collect(Collectors.toMap(UnidadDetalle::id, Function.identity()));
    List<LoteCosto> filas = new ArrayList<>();
    Acumulado total = new Acumulado(clasificaciones.size());
    BigDecimal presupuestoTotal = null;
    BigDecimal consumidoConPresupuesto = BigDecimal.ZERO;
    for (var e : porLote.entrySet()) {
      UnidadDetalle u = detalle.get(e.getKey());
      Acumulado a = e.getValue();
      BigDecimal consumido = programa < 0 ? BigDecimal.ZERO : a.porClasificacion[programa];
      BigDecimal presupuesto = u == null ? null : u.presupuesto();
      if (presupuesto != null) {
        presupuestoTotal =
            (presupuestoTotal == null ? BigDecimal.ZERO : presupuestoTotal).add(presupuesto);
        consumidoConPresupuesto = consumidoConPresupuesto.add(consumido);
      }
      filas.add(
          a.fila(
              e.getKey(),
              u == null ? String.valueOf(e.getKey()) : u.clave(),
              u == null ? null : u.prototipo(),
              presupuesto,
              consumido));
      total.agregar(a);
    }
    if (!sinAsignar.vacio()) {
      filas.add(sinAsignar.fila(null, "Sin asignar", null, null, BigDecimal.ZERO));
      total.agregar(sinAsignar);
    }
    LoteCosto totales =
        total.fila(
            null,
            "Total",
            null,
            presupuestoTotal,
            presupuestoTotal == null ? BigDecimal.ZERO : consumidoConPresupuesto);
    return new CostoContrato(
        contratoId,
        contrato.codigo(),
        clasificaciones.stream()
            .map(c -> new ColumnaClasificacion(c.id(), c.clave(), c.nombre()))
            .toList(),
        filas,
        totales);
  }

  /** Lo que lleva un lote mientras se arma el reporte. */
  private static final class Acumulado {
    final BigDecimal[] porClasificacion;
    BigDecimal sinClasificacion = BigDecimal.ZERO;
    BigDecimal historico = BigDecimal.ZERO;
    BigDecimal sistema = BigDecimal.ZERO;
    BigDecimal destajo = BigDecimal.ZERO;
    boolean estimado;

    Acumulado(int columnas) {
      porClasificacion = new BigDecimal[columnas];
      java.util.Arrays.fill(porClasificacion, BigDecimal.ZERO);
    }

    void sumar(int columna, String fuente, BigDecimal importe, boolean esEstimado) {
      if (columna < 0) {
        sinClasificacion = sinClasificacion.add(importe);
      } else {
        porClasificacion[columna] = porClasificacion[columna].add(importe);
      }
      if ("historico".equals(fuente)) {
        historico = historico.add(importe);
      } else {
        sistema = sistema.add(importe);
      }
      estimado |= esEstimado;
    }

    void agregar(Acumulado otro) {
      for (int i = 0; i < porClasificacion.length; i++) {
        porClasificacion[i] = porClasificacion[i].add(otro.porClasificacion[i]);
      }
      sinClasificacion = sinClasificacion.add(otro.sinClasificacion);
      historico = historico.add(otro.historico);
      sistema = sistema.add(otro.sistema);
      destajo = destajo.add(otro.destajo);
      estimado |= otro.estimado;
    }

    boolean vacio() {
      return historico.signum() == 0 && sistema.signum() == 0 && destajo.signum() == 0;
    }

    LoteCosto fila(
        Long id, String clave, String prototipo, BigDecimal presupuesto, BigDecimal consumido) {
      BigDecimal material = historico.add(sistema);
      return new LoteCosto(
          id,
          clave,
          prototipo,
          List.of(porClasificacion),
          historico,
          sistema,
          material,
          destajo,
          material.add(destajo),
          presupuesto,
          consumido,
          Semaforo.porcentaje(consumido, presupuesto),
          Semaforo.de(consumido, presupuesto).clave(),
          estimado);
    }
  }

  // ------------------------------------------------------------------------------ Presupuesto

  /**
   * Presupuesto contra consumido por lote. Con {@code contratoId}, todos los lotes del contrato;
   * con {@code unidadIds}, esos lotes (para avisar al capturar una requisición o un vale); sin
   * ninguno, los lotes con presupuesto de todos los contratos visibles. {@code soloAvisos}: del 80%
   * en adelante, el más consumido primero.
   */
  @PreAuthorize(VER_COSTOS)
  @Transactional(readOnly = true)
  public List<LotePresupuesto> presupuesto(
      Long contratoId, Collection<Long> unidadIds, boolean soloAvisos) {
    exigirContratoVisible(contratoId);
    boolean porLista = unidadIds != null && !unidadIds.isEmpty();
    List<FilaPresupuesto> filas =
        repositorio.presupuesto(
            empresa(),
            contratoId,
            porLista ? unidadIds : null,
            alcance(),
            contratoId == null && !porLista);
    Map<Long, String> contratos =
        estructura
            .contratos(filas.stream().map(FilaPresupuesto::contratoId).collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(ContratoInfo::id, ContratoInfo::codigo));
    Map<Long, String> prototipos = new HashMap<>();
    for (Long c : filas.stream().map(FilaPresupuesto::contratoId).collect(Collectors.toSet())) {
      estructura.unidadesDeContrato(c).stream()
          .filter(u -> u.prototipo() != null)
          .forEach(u -> prototipos.put(u.id(), u.prototipo()));
    }
    List<LotePresupuesto> lista = new ArrayList<>();
    for (FilaPresupuesto f : filas) {
      Semaforo s = Semaforo.de(f.consumido(), f.presupuesto());
      if (soloAvisos && s != Semaforo.AMARILLO && s != Semaforo.ROJO) {
        continue;
      }
      lista.add(
          new LotePresupuesto(
              f.contratoId(),
              contratos.getOrDefault(f.contratoId(), ""),
              f.unidadObraId(),
              f.clave(),
              prototipos.get(f.unidadObraId()),
              f.presupuesto(),
              f.consumido(),
              f.presupuesto() == null ? null : f.presupuesto().subtract(f.consumido()),
              Semaforo.porcentaje(f.consumido(), f.presupuesto()),
              s.clave()));
    }
    if (soloAvisos) {
      lista.sort(
          Comparator.comparing(
                  LotePresupuesto::porcentaje,
                  Comparator.nullsFirst(Comparator.<BigDecimal>naturalOrder()))
              .reversed());
    }
    return lista;
  }

  // ------------------------------------------------------------------------------ Inventario

  @PreAuthorize(VER_INVENTARIO)
  @Transactional(readOnly = true)
  public ValorInventario valorInventario(Long almacenId) {
    List<FilaExistencia> filas = repositorio.existencias(empresa(), almacenId);
    Map<Long, AlmacenInfo> almacenes =
        inventario.almacenes().stream()
            .collect(Collectors.toMap(AlmacenInfo::id, Function.identity()));
    Map<Long, MaterialInfo> materiales =
        catalogo
            .materiales(filas.stream().map(FilaExistencia::materialId).collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(MaterialInfo::id, Function.identity()));
    BigDecimal valor = BigDecimal.ZERO;
    List<ExistenciaValorizada> lista = new ArrayList<>();
    for (FilaExistencia f : filas) {
      valor = valor.add(f.valor());
      MaterialInfo m = materiales.get(f.materialId());
      AlmacenInfo a = almacenes.get(f.almacenId());
      lista.add(
          new ExistenciaValorizada(
              f.almacenId(),
              a == null ? "" : a.nombre(),
              f.materialId(),
              m == null ? "" : m.clave(),
              m == null ? "" : m.nombre(),
              m == null ? "" : m.unidadControl(),
              f.existencia(),
              f.reservado(),
              f.prestado(),
              f.disponible(),
              f.costoPromedio(),
              f.valor()));
    }
    return new ValorInventario(lista, valor);
  }

  // ------------------------------------------------------------------------------ Surtido

  @PreAuthorize(VER_SURTIDO)
  @Transactional(readOnly = true)
  public List<SurtidoRequisicion> requisicionVsSurtido(
      Long contratoId, LocalDate desde, LocalDate hasta) {
    exigirContratoVisible(contratoId);
    return abastecimiento.requisicionVsSurtido(contratoId, desde, hasta);
  }

  // ------------------------------------------------------------------------------ Apoyo

  /** {@code null} si ve el costo de todos los contratos; si no, los suyos. */
  private Collection<Long> alcance() {
    if (Autorizacion.tiene(Permisos.COSTO_VER_TODO) || estructura.veTodosLosContratos()) {
      return null;
    }
    return estructura.contratosVisibles();
  }

  private void exigirContratoVisible(Long contratoId) {
    if (contratoId == null) {
      return;
    }
    Collection<Long> alcance = alcance();
    if (alcance != null && !alcance.contains(contratoId)) {
      throw contratoNoEncontrado();
    }
  }

  private static Filtro filtroVacio() {
    return new Filtro(null, null, null, null, null, null, null, null, null);
  }

  private static ErrorNegocio contratoNoEncontrado() {
    return ErrorNegocio.noEncontrado("CONTRATO_NO_ENCONTRADO", "No existe el contrato");
  }

  private static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }
}

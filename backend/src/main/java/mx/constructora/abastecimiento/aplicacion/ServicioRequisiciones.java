package mx.constructora.abastecimiento.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static mx.constructora.abastecimiento.aplicacion.Acceso.AUTORIZAR;
import static mx.constructora.abastecimiento.aplicacion.Acceso.CAPTURAR_ALMACEN;
import static mx.constructora.abastecimiento.aplicacion.Acceso.CREAR_REQUISICION;
import static mx.constructora.abastecimiento.aplicacion.Acceso.RESERVAR;
import static mx.constructora.abastecimiento.aplicacion.Acceso.TERMINAR_REQUISICION;
import static mx.constructora.abastecimiento.aplicacion.Acceso.VER_REQUISICIONES;
import static mx.constructora.abastecimiento.aplicacion.Acceso.empresa;
import static mx.constructora.abastecimiento.aplicacion.Acceso.hoy;
import static mx.constructora.abastecimiento.aplicacion.Acceso.usuario;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import mx.constructora.abastecimiento.AbastecimientoApi.SurtidoRequisicion;
import mx.constructora.abastecimiento.dominio.Avance;
import mx.constructora.abastecimiento.infraestructura.RepositorioPedidos;
import mx.constructora.abastecimiento.infraestructura.RepositorioPedidos.FilaLiga;
import mx.constructora.abastecimiento.infraestructura.RepositorioRequisiciones;
import mx.constructora.abastecimiento.infraestructura.RepositorioRequisiciones.Cabecera;
import mx.constructora.abastecimiento.infraestructura.RepositorioRequisiciones.FilaCobertura;
import mx.constructora.abastecimiento.infraestructura.RepositorioRequisiciones.FilaRenglon;
import mx.constructora.abastecimiento.infraestructura.RepositorioRequisiciones.FilaRequisicion;
import mx.constructora.abastecimiento.infraestructura.RepositorioRequisiciones.FilaResumen;
import mx.constructora.abastecimiento.infraestructura.RepositorioRequisiciones.NuevoRenglon;
import mx.constructora.catalogo.CatalogoApi;
import mx.constructora.catalogo.CatalogoApi.ClasificacionInfo;
import mx.constructora.catalogo.CatalogoApi.MaterialInfo;
import mx.constructora.compartido.Autorizacion;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Folios;
import mx.constructora.compartido.Permisos;
import mx.constructora.compartido.Textos;
import mx.constructora.compartido.Versiones;
import mx.constructora.estructura.EstructuraApi;
import mx.constructora.estructura.EstructuraApi.ContratoInfo;
import mx.constructora.estructura.EstructuraApi.PaqueteInfo;
import mx.constructora.estructura.EstructuraApi.UnidadInfo;
import mx.constructora.inventario.InventarioApi;
import mx.constructora.inventario.InventarioApi.AlmacenInfo;
import mx.constructora.inventario.InventarioApi.Disponibilidad;
import mx.constructora.inventario.InventarioApi.DocumentoLigado;
import mx.constructora.inventario.InventarioApi.LineaVale;
import mx.constructora.inventario.InventarioApi.Surtido;
import mx.constructora.organizacion.DirectorioUsuarios;
import mx.constructora.organizacion.DirectorioUsuarios.UsuarioResumen;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requisición de material (reemplaza la hoja Semanal). El residente pide material para los lotes de
 * su contrato; coordinación o dirección la autoriza (nunca quien la capturó, regla 5) y en ese
 * momento se aparta lo que hay en el almacén del frente. Lo que falta lo pide compras al proveedor
 * ({@link ServicioPedidos}); al llegar, queda apartado para esta requisición. El almacén surte con
 * un vale ligado a los renglones, que consume lo apartado al aplicarse.
 *
 * <p>El residente solo ve y pide para sus contratos (ADR-006).
 */
@Service
public class ServicioRequisiciones {

  /** Tamaño máximo de página. */
  public static final int PAGINA_MAXIMA = 200;

  /** Renglones como máximo por requisición, después de repartir entre lotes. */
  static final int RENGLONES_MAXIMOS = 1000;

  // ------------------------------------------------------------------------------ DTOs

  /** Un material para uno o varios lotes, como se captura. */
  public record RenglonCaptura(
      @NotNull Long materialId,
      @Schema(description = "En la unidad capturada; con varios lotes, el total que se reparte")
          @NotNull
          @Positive
          @Digits(integer = 10, fraction = 4)
          BigDecimal cantidad,
      @Schema(description = "Unidad capturada; vacía: la unidad de control del material")
          Long unidadId,
      @NotEmpty @Size(max = 200) List<Long> unidadObraIds,
      @NotNull Long paqueteId,
      @NotNull Integer clasificacionId,
      @Size(max = 300) String observaciones) {}

  /** Alta o corrección de una requisición. */
  public record DatosRequisicion(
      @NotNull Long contratoId,
      @NotNull LocalDate fechaRequerida,
      Boolean urgente,
      @Size(max = 500) String observaciones,
      @NotEmpty @Size(max = 300) List<@Valid RenglonCaptura> renglones,
      @Schema(description = "Crear y enviar a autorizar en un paso") Boolean enviar) {}

  /** Renglón de la requisición con su avance, en unidad de control. */
  public record RenglonRequisicion(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) int renglon,
      @Schema(requiredMode = REQUIRED) long materialId,
      @Schema(requiredMode = REQUIRED) String materialClave,
      @Schema(requiredMode = REQUIRED) String material,
      @Schema(requiredMode = REQUIRED) String unidadControl,
      @Schema(requiredMode = REQUIRED) BigDecimal cantidad,
      @Schema(requiredMode = REQUIRED) BigDecimal cantidadCaptura,
      @Schema(requiredMode = REQUIRED) long unidadCapturaId,
      @Schema(requiredMode = REQUIRED) String unidadCaptura,
      @Schema(requiredMode = REQUIRED) long unidadObraId,
      @Schema(requiredMode = REQUIRED) String unidadObra,
      @Schema(requiredMode = REQUIRED) long paqueteId,
      @Schema(requiredMode = REQUIRED) String paquete,
      @Schema(requiredMode = REQUIRED) int clasificacionId,
      @Schema(requiredMode = REQUIRED) String clasificacion,
      String observaciones,
      @Schema(
              requiredMode = REQUIRED,
              description = "Salió en vales aplicados, neto de devoluciones")
          BigDecimal surtido,
      @Schema(requiredMode = REQUIRED, description = "En vales que todavía no se aplican")
          BigDecimal enVale,
      @Schema(requiredMode = REQUIRED, description = "Apartado en el almacén") BigDecimal reservado,
      @Schema(requiredMode = REQUIRED, description = "Pedido al proveedor, sin llegar")
          BigDecimal porRecibir,
      @Schema(requiredMode = REQUIRED, description = "Lo que puede ir en un vale nuevo")
          BigDecimal porSurtir,
      @Schema(
              requiredMode = REQUIRED,
              description = "Pendiente sin cubrir ni pedir: hay que comprar")
          BigDecimal faltante,
      @Schema(requiredMode = REQUIRED, description = "Libre en el almacén para cualquier vale")
          BigDecimal disponible) {}

  /** Vale que surte la requisición. */
  public record ValeDeRequisicion(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(requiredMode = REQUIRED) String estado,
      @Schema(requiredMode = REQUIRED) LocalDate fecha,
      @Schema(requiredMode = REQUIRED) int renglones) {}

  /** Pedido a proveedor que cubre renglones de la requisición. */
  public record PedidoDeRequisicion(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(requiredMode = REQUIRED) String estado,
      @Schema(requiredMode = REQUIRED) BigDecimal cantidad,
      @Schema(requiredMode = REQUIRED) BigDecimal recibido) {}

  /** Qué puede hacer el usuario en curso. */
  public record AccionesRequisicion(
      @Schema(requiredMode = REQUIRED) boolean editar,
      @Schema(requiredMode = REQUIRED) boolean enviar,
      @Schema(requiredMode = REQUIRED) boolean autorizar,
      @Schema(requiredMode = REQUIRED) boolean cancelar,
      @Schema(requiredMode = REQUIRED) boolean cerrar,
      @Schema(requiredMode = REQUIRED) boolean reservar,
      @Schema(requiredMode = REQUIRED) boolean surtir) {}

  public record Requisicion(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"borrador", "pendiente", "autorizada", "cerrada", "cancelada"})
          String estado,
      @Schema(requiredMode = REQUIRED) long contratoId,
      @Schema(requiredMode = REQUIRED) String contrato,
      @Schema(requiredMode = REQUIRED) long almacenId,
      @Schema(requiredMode = REQUIRED) String almacen,
      @Schema(requiredMode = REQUIRED) LocalDate fecha,
      @Schema(requiredMode = REQUIRED) LocalDate fechaRequerida,
      @Schema(requiredMode = REQUIRED) boolean urgente,
      String observaciones,
      String notaRechazo,
      String motivoCierre,
      @Schema(requiredMode = REQUIRED) String capturo,
      String autorizo,
      OffsetDateTime autorizadaEn,
      OffsetDateTime cerradaEn,
      @Schema(requiredMode = REQUIRED) OffsetDateTime creado,
      @Schema(requiredMode = REQUIRED, description = "Porcentaje surtido (0 a 100)")
          BigDecimal avance,
      @Schema(requiredMode = REQUIRED) int version,
      @Schema(requiredMode = REQUIRED) List<RenglonRequisicion> renglones,
      @Schema(requiredMode = REQUIRED) List<ValeDeRequisicion> vales,
      @Schema(requiredMode = REQUIRED) List<PedidoDeRequisicion> pedidos,
      @Schema(requiredMode = REQUIRED) AccionesRequisicion acciones) {}

  public record ResumenRequisicion(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(requiredMode = REQUIRED) String estado,
      @Schema(requiredMode = REQUIRED) long contratoId,
      @Schema(requiredMode = REQUIRED) String contrato,
      @Schema(requiredMode = REQUIRED) String almacen,
      @Schema(requiredMode = REQUIRED) LocalDate fecha,
      @Schema(requiredMode = REQUIRED) LocalDate fechaRequerida,
      @Schema(requiredMode = REQUIRED) boolean urgente,
      @Schema(requiredMode = REQUIRED) String capturo,
      String autorizo,
      @Schema(requiredMode = REQUIRED) int renglones,
      @Schema(requiredMode = REQUIRED) BigDecimal avance,
      @Schema(requiredMode = REQUIRED, description = "Algún renglón tiene faltante por comprar")
          boolean conFaltante,
      @Schema(requiredMode = REQUIRED) OffsetDateTime creado) {}

  /** Cuánto surtir de un renglón, en unidad de control. */
  public record RenglonSurtido(
      @NotNull Long requisicionLineaId,
      @NotNull @Positive @Digits(integer = 10, fraction = 4) BigDecimal cantidad) {}

  /** El vale que surte la requisición. */
  public record DatosSurtido(
      @NotNull LocalDate fecha,
      @Schema(description = "Persona que recibe en obra") Long solicitanteId,
      @Size(max = 500) String observaciones,
      @NotEmpty @Size(max = 1000) List<@Valid RenglonSurtido> renglones) {}

  /** Vale creado: queda enviado a autorizar. */
  public record ValeCreado(
      @Schema(requiredMode = REQUIRED) long movimientoId,
      @Schema(requiredMode = REQUIRED) String folio) {}

  // ------------------------------------------------------------------------------ Servicio

  private final RepositorioRequisiciones repositorio;
  private final RepositorioPedidos pedidos;
  private final InventarioApi inventario;
  private final EstructuraApi estructura;
  private final CatalogoApi catalogo;
  private final DirectorioUsuarios usuarios;
  private final Folios folios;

  public ServicioRequisiciones(
      RepositorioRequisiciones repositorio,
      RepositorioPedidos pedidos,
      InventarioApi inventario,
      EstructuraApi estructura,
      CatalogoApi catalogo,
      DirectorioUsuarios usuarios,
      Folios folios) {
    this.repositorio = repositorio;
    this.pedidos = pedidos;
    this.inventario = inventario;
    this.estructura = estructura;
    this.catalogo = catalogo;
    this.usuarios = usuarios;
    this.folios = folios;
  }

  // ------------------------------------------------------------------------------ Captura

  @PreAuthorize(CREAR_REQUISICION)
  @Transactional
  public Requisicion crear(DatosRequisicion datos) {
    Preparada p = preparar(datos, datos.contratoId());
    long id = repositorio.insertar(empresa(), folios.siguiente("REQ"), p.cabecera(), usuario());
    for (NuevoRenglon r : p.renglones()) {
      repositorio.insertarRenglon(empresa(), id, p.cabecera().contratoId(), r);
    }
    if (Boolean.TRUE.equals(datos.enviar())) {
      repositorio.enviar(empresa(), id);
    }
    return requisicion(id);
  }

  /** Corrige un borrador completo. El contrato no cambia. */
  @PreAuthorize(CREAR_REQUISICION)
  @Transactional
  public Requisicion reemplazar(long id, int version, DatosRequisicion datos) {
    FilaRequisicion actual = bloquear(id);
    if (!actual.estado().equals("borrador")) {
      throw ErrorNegocio.conflicto("NO_ES_BORRADOR", "Solo se corrige una requisición en borrador");
    }
    if (datos.contratoId() != null && datos.contratoId() != actual.contratoId()) {
      throw ErrorNegocio.invalido("CONTRATO_NO_CAMBIA", "El contrato de la requisición no cambia");
    }
    Preparada p = preparar(datos, actual.contratoId());
    if (repositorio.actualizarCabecera(empresa(), id, version, p.cabecera()) == 0) {
      throw Versiones.desactualizada();
    }
    repositorio.borrarRenglones(empresa(), id);
    for (NuevoRenglon r : p.renglones()) {
      repositorio.insertarRenglon(empresa(), id, actual.contratoId(), r);
    }
    if (Boolean.TRUE.equals(datos.enviar())) {
      repositorio.enviar(empresa(), id);
    }
    return requisicion(id);
  }

  @PreAuthorize(CREAR_REQUISICION)
  @Transactional
  public Requisicion enviar(long id) {
    FilaRequisicion r = bloquear(id);
    if (!r.estado().equals("borrador")) {
      throw ErrorNegocio.conflicto("NO_ES_BORRADOR", "La requisición ya se envió");
    }
    repositorio.enviar(empresa(), id);
    return requisicion(id);
  }

  // ------------------------------------------------------------------------------ Autorización

  /**
   * Autoriza y aparta en el almacén lo que haya de cada renglón; lo que no alcance queda como
   * faltante para compras.
   */
  @PreAuthorize(AUTORIZAR)
  @Transactional
  public Requisicion autorizar(long id) {
    FilaRequisicion r = bloquear(id);
    exigirPuedeAutorizar(r);
    ContratoInfo contrato = contrato(r.contratoId());
    if (!contrato.activo()) {
      throw ErrorNegocio.conflicto(
          "CONTRATO_NO_ACTIVO", "El contrato " + contrato.codigo() + " ya no está activo");
    }
    repositorio.autorizar(empresa(), id, usuario());
    apartar(r);
    return requisicion(id);
  }

  @PreAuthorize(AUTORIZAR)
  @Transactional
  public Requisicion rechazar(long id, String nota) {
    String texto = Textos.limpio(nota);
    if (texto == null) {
      throw ErrorNegocio.invalido("FALTA_NOTA", "Escribe qué hay que corregir");
    }
    FilaRequisicion r = bloquear(id);
    exigirPuedeAutorizar(r);
    repositorio.rechazar(empresa(), id, texto);
    return requisicion(id);
  }

  private void exigirPuedeAutorizar(FilaRequisicion r) {
    if (!r.estado().equals("pendiente")) {
      throw ErrorNegocio.conflicto(
          "REQUISICION_NO_PENDIENTE",
          "La requisición " + r.folio() + " no está pendiente de autorizar");
    }
    if (r.usuarioCaptura() == usuario()) {
      throw new ErrorNegocio(
          HttpStatus.FORBIDDEN,
          "AUTORIZA_QUIEN_CAPTURO",
          "Quien captura una requisición no la autoriza");
    }
  }

  // ------------------------------------------------------------------------------ Reserva

  /** Vuelve a apartar lo que haya en el almacén para lo pendiente de cada renglón. */
  @PreAuthorize(RESERVAR)
  @Transactional
  public Requisicion reservar(long id) {
    FilaRequisicion r = bloquear(id);
    if (!r.estado().equals("autorizada")) {
      throw ErrorNegocio.conflicto(
          "REQUISICION_NO_AUTORIZADA", "Solo se aparta material para una requisición autorizada");
    }
    apartar(r);
    return requisicion(id);
  }

  /**
   * Aparta para cada renglón lo que le falta por surtir (hasta lo disponible), en orden de
   * material: el mismo orden en que se bloquean los saldos al aplicar un vale.
   */
  private void apartar(FilaRequisicion r) {
    List<FilaRenglon> renglones = repositorio.renglones(empresa(), r.id());
    List<Long> ids = renglones.stream().map(FilaRenglon::id).toList();
    Map<Long, Surtido> surtido = inventario.surtido(ids);
    Map<Long, BigDecimal> reservado = inventario.reservado(ids);
    renglones.stream()
        .sorted(
            Comparator.comparingLong(FilaRenglon::materialId)
                .thenComparingInt(FilaRenglon::renglon)
                .thenComparingLong(FilaRenglon::id))
        .forEach(
            l -> {
              BigDecimal entregado =
                  surtido
                      .getOrDefault(l.id(), new Surtido(BigDecimal.ZERO, BigDecimal.ZERO))
                      .surtido();
              BigDecimal falta =
                  l.cantidad()
                      .subtract(entregado)
                      .subtract(reservado.getOrDefault(l.id(), BigDecimal.ZERO));
              if (falta.signum() > 0) {
                inventario.reservar(r.almacenId(), l.materialId(), l.id(), falta);
              }
            });
  }

  // ------------------------------------------------------------------------------ Cierre

  /**
   * Cancela: en borrador o pendiente, siempre; autorizada, solo si nada se ha surtido ni va en un
   * vale. Libera lo apartado.
   */
  @PreAuthorize(TERMINAR_REQUISICION)
  @Transactional
  public Requisicion cancelar(long id, String motivo) {
    String texto = exigirMotivo(motivo);
    FilaRequisicion r = bloquear(id);
    switch (r.estado()) {
      case "borrador", "pendiente" -> {}
      case "autorizada" -> {
        List<Long> ids =
            repositorio.renglones(empresa(), id).stream().map(FilaRenglon::id).toList();
        boolean movida =
            inventario.surtido(ids).values().stream()
                .anyMatch(s -> s.surtido().signum() != 0 || s.enProceso().signum() != 0);
        if (movida) {
          throw ErrorNegocio.conflicto(
              "REQUISICION_CON_SURTIDO",
              "La requisición ya tiene vales: ciérrala en lugar de cancelarla");
        }
        inventario.liberar(ids);
      }
      default ->
          throw ErrorNegocio.conflicto(
              "REQUISICION_TERMINADA", "La requisición " + r.folio() + " ya está " + r.estado());
    }
    repositorio.terminar(empresa(), id, "cancelada", texto);
    return requisicion(id);
  }

  /** Da por terminada una requisición autorizada: lo que no se surtió ya no se pide. */
  @PreAuthorize(TERMINAR_REQUISICION)
  @Transactional
  public Requisicion cerrar(long id, String motivo) {
    String texto = exigirMotivo(motivo);
    FilaRequisicion r = bloquear(id);
    if (!r.estado().equals("autorizada")) {
      throw ErrorNegocio.conflicto(
          "REQUISICION_NO_AUTORIZADA", "Solo se cierra una requisición autorizada");
    }
    inventario.liberar(repositorio.renglones(empresa(), id).stream().map(FilaRenglon::id).toList());
    repositorio.terminar(empresa(), id, "cerrada", texto);
    return requisicion(id);
  }

  private static String exigirMotivo(String motivo) {
    String texto = Textos.limpio(motivo);
    if (texto == null) {
      throw ErrorNegocio.invalido("FALTA_MOTIVO", "Escribe el motivo");
    }
    return texto;
  }

  // ------------------------------------------------------------------------------ Surtido

  /**
   * Crea el vale que surte los renglones indicados y lo envía a autorizar. Cada renglón surte como
   * máximo lo que le falta y no va ya en otro vale; los renglones de un mismo número salen juntos
   * en el vale para que el importe sume exacto.
   */
  @PreAuthorize(CAPTURAR_ALMACEN)
  @Transactional
  public ValeCreado surtir(long id, DatosSurtido datos) {
    FilaRequisicion r = bloquear(id);
    if (!r.estado().equals("autorizada")) {
      throw ErrorNegocio.conflicto(
          "REQUISICION_NO_AUTORIZADA", "Solo se surte una requisición autorizada");
    }
    Map<Long, FilaRenglon> renglones =
        repositorio.renglones(empresa(), id).stream()
            .collect(Collectors.toMap(FilaRenglon::id, Function.identity()));
    Map<Long, Surtido> surtido = inventario.surtido(renglones.keySet());
    Set<Long> vistos = new HashSet<>();
    List<LineaVale> lineas = new ArrayList<>();
    for (RenglonSurtido s : datos.renglones()) {
      FilaRenglon l = renglones.get(s.requisicionLineaId());
      if (l == null) {
        throw ErrorNegocio.invalido(
            "RENGLON_NO_ENCONTRADO", "El renglón no es de la requisición " + r.folio());
      }
      if (!vistos.add(l.id())) {
        throw ErrorNegocio.invalido("RENGLON_REPETIDO", "Un renglón va una sola vez en el vale");
      }
      Avance avance = avance(l, surtido, Map.of(), Map.of());
      BigDecimal cantidad =
          s.cantidad().setScale(InventarioApi.DECIMALES_CANTIDAD, RoundingMode.HALF_UP);
      if (cantidad.compareTo(avance.porSurtir()) > 0) {
        throw ErrorNegocio.conflicto(
            "SURTIDO_MAYOR",
            "Renglón "
                + l.renglon()
                + ": se pueden surtir "
                + avance.porSurtir().stripTrailingZeros().toPlainString()
                + " y se pidió surtir "
                + cantidad.stripTrailingZeros().toPlainString());
      }
      lineas.add(
          new LineaVale(
              l.id(),
              l.renglon(),
              l.materialId(),
              cantidad,
              l.contratoId(),
              l.unidadObraId(),
              l.paqueteId(),
              l.clasificacionId()));
    }
    lineas.sort(
        Comparator.comparingInt(LineaVale::renglon)
            .thenComparingLong(LineaVale::requisicionLineaId));
    String nota = Textos.limpio(datos.observaciones());
    String observaciones = "Surte " + r.folio() + (nota == null ? "" : ": " + nota);
    var vale =
        inventario.crearVale(
            r.almacenId(),
            datos.fecha(),
            datos.solicitanteId(),
            observaciones.length() > 500 ? observaciones.substring(0, 500) : observaciones,
            lineas);
    return new ValeCreado(vale.id(), vale.folio());
  }

  // ------------------------------------------------------------------------------ Consulta

  @PreAuthorize(VER_REQUISICIONES)
  @Transactional(readOnly = true)
  public Requisicion requisicion(long id) {
    FilaRequisicion r =
        repositorio.requisicion(empresa(), id).orElseThrow(ServicioRequisiciones::noEncontrada);
    exigirVisible(r);
    return presentar(r, repositorio.renglones(empresa(), id));
  }

  /** Requisiciones filtradas; {@code estados} separados por coma. */
  @PreAuthorize(VER_REQUISICIONES)
  @Transactional(readOnly = true)
  public List<ResumenRequisicion> buscar(
      String estados,
      Long contratoId,
      LocalDate desde,
      LocalDate hasta,
      String folio,
      int pagina,
      int tamano) {
    int limite = Math.clamp(tamano, 1, PAGINA_MAXIMA);
    List<FilaResumen> filas =
        repositorio.buscar(
            empresa(),
            lista(estados),
            contratoId,
            desde,
            hasta,
            Textos.limpio(folio),
            alcance(),
            null,
            limite,
            (long) Math.max(pagina, 0) * limite);
    return resumenes(filas);
  }

  /** Bandeja: requisiciones pendientes que el usuario puede autorizar (sin las suyas). */
  @PreAuthorize(AUTORIZAR)
  @Transactional(readOnly = true)
  public List<ResumenRequisicion> porAutorizar() {
    return resumenes(
        repositorio.buscar(
            empresa(),
            List.of("pendiente"),
            null,
            null,
            null,
            null,
            alcance(),
            usuario(),
            PAGINA_MAXIMA,
            0));
  }

  /**
   * Renglones de requisiciones autorizadas o cerradas del periodo contra lo surtido (reporte
   * "requisición contra surtido"). Sin revisar permisos: lo llama {@code AbastecimientoApi}.
   */
  @Transactional(readOnly = true)
  public List<SurtidoRequisicion> surtidoDelPeriodo(
      Long contratoId, LocalDate desde, LocalDate hasta) {
    List<FilaResumen> filas =
        repositorio.buscar(
            empresa(),
            List.of("autorizada", "cerrada"),
            contratoId,
            desde,
            hasta,
            null,
            alcance(),
            null,
            10_000,
            0);
    if (filas.isEmpty()) {
      return List.of();
    }
    Map<Long, FilaResumen> porId =
        filas.stream().collect(Collectors.toMap(FilaResumen::id, Function.identity()));
    List<FilaRenglon> renglones = repositorio.renglonesDe(empresa(), porId.keySet());
    List<Long> ids = renglones.stream().map(FilaRenglon::id).toList();
    Map<Long, Surtido> surtido = inventario.surtido(ids);
    Map<Long, BigDecimal> reservado = inventario.reservado(ids);
    Map<Long, BigDecimal> porRecibir = porRecibir(ids);
    Map<Long, MaterialInfo> materiales =
        catalogo
            .materiales(renglones.stream().map(FilaRenglon::materialId).collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(MaterialInfo::id, Function.identity()));
    Map<Long, String> lotes =
        estructura
            .unidades(renglones.stream().map(FilaRenglon::unidadObraId).collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(UnidadInfo::id, UnidadInfo::clave));
    Map<Long, String> contratos =
        estructura
            .contratos(filas.stream().map(FilaResumen::contratoId).collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(ContratoInfo::id, ContratoInfo::codigo));
    List<SurtidoRequisicion> resultado = new ArrayList<>();
    for (FilaRenglon l : renglones) {
      FilaResumen r = porId.get(l.requisicionId());
      Avance a = avance(l, surtido, reservado, porRecibir);
      MaterialInfo m = materiales.get(l.materialId());
      boolean abierta = r.estado().equals("autorizada");
      resultado.add(
          new SurtidoRequisicion(
              r.id(),
              r.folio(),
              r.estado(),
              r.fecha(),
              r.fechaRequerida(),
              r.contratoId(),
              contratos.getOrDefault(r.contratoId(), ""),
              l.unidadObraId(),
              lotes.getOrDefault(l.unidadObraId(), ""),
              m == null ? "" : m.clave(),
              m == null ? "" : m.nombre(),
              m == null ? "" : m.unidadControl(),
              l.cantidad(),
              a.surtido(),
              a.enVale(),
              abierta ? a.reservado() : BigDecimal.ZERO,
              abierta ? a.porRecibir() : BigDecimal.ZERO,
              abierta ? a.faltante() : BigDecimal.ZERO,
              Avance.porcentaje(List.of(a))));
    }
    resultado.sort(
        Comparator.comparing(SurtidoRequisicion::fecha, Comparator.reverseOrder())
            .thenComparing(SurtidoRequisicion::folio)
            .thenComparing(SurtidoRequisicion::unidadObra));
    return resultado;
  }

  // ------------------------------------------------------------------------------ Preparar

  private record Preparada(Cabecera cabecera, List<NuevoRenglon> renglones) {}

  private Preparada preparar(DatosRequisicion d, Long contratoId) {
    if (contratoId == null) {
      throw ErrorNegocio.invalido("FALTA_CONTRATO", "Elige el contrato");
    }
    ContratoInfo contrato = contrato(contratoId);
    if (!estructura.contratoVisible(contratoId)) {
      throw ErrorNegocio.noEncontrado("CONTRATO_NO_ENCONTRADO", "No existe el contrato");
    }
    if (!contrato.activo()) {
      throw ErrorNegocio.conflicto(
          "CONTRATO_NO_ACTIVO", "El contrato " + contrato.codigo() + " ya no está activo");
    }
    if (contrato.frenteId() == null) {
      throw ErrorNegocio.invalido(
          "CONTRATO_SIN_FRENTE", "El contrato " + contrato.codigo() + " no tiene frente");
    }
    AlmacenInfo almacen =
        inventario
            .almacenDeFrente(contrato.frenteId())
            .orElseThrow(
                () ->
                    ErrorNegocio.conflicto(
                        "FRENTE_SIN_ALMACEN", "El frente del contrato no tiene almacén"));
    if (!almacen.activo()) {
      throw ErrorNegocio.conflicto(
          "ALMACEN_INACTIVO", "El almacén " + almacen.clave() + " está desactivado");
    }
    if (d.fechaRequerida() == null) {
      throw ErrorNegocio.invalido("FALTA_FECHA", "Escribe para cuándo se necesita");
    }
    if (d.fechaRequerida().isBefore(hoy())) {
      throw ErrorNegocio.invalido("FECHA_PASADA", "La fecha requerida ya pasó");
    }
    if (d.renglones() == null || d.renglones().isEmpty()) {
      throw ErrorNegocio.invalido("SIN_RENGLONES", "Agrega al menos un material");
    }

    Map<Long, MaterialInfo> materiales =
        catalogo
            .materiales(
                d.renglones().stream()
                    .map(RenglonCaptura::materialId)
                    .filter(Objects::nonNull)
                    .toList())
            .stream()
            .collect(Collectors.toMap(MaterialInfo::id, Function.identity()));
    Map<Long, Boolean> fraccionable =
        catalogo.unidades().stream()
            .collect(
                Collectors.toMap(CatalogoApi.UnidadInfo::id, CatalogoApi.UnidadInfo::fraccionable));
    Map<Long, UnidadInfo> unidades =
        estructura
            .unidades(
                d.renglones().stream()
                    .flatMap(
                        r ->
                            r.unidadObraIds() == null
                                ? java.util.stream.Stream.<Long>empty()
                                : r.unidadObraIds().stream())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(UnidadInfo::id, Function.identity()));
    Map<Long, PaqueteInfo> paquetes =
        estructura
            .paquetes(
                d.renglones().stream()
                    .map(RenglonCaptura::paqueteId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(PaqueteInfo::id, Function.identity()));
    Set<Integer> clasificaciones =
        catalogo.clasificaciones().stream().map(ClasificacionInfo::id).collect(Collectors.toSet());

    List<NuevoRenglon> resultado = new ArrayList<>();
    int numero = 0;
    for (RenglonCaptura c : d.renglones()) {
      numero++;
      String donde = "Renglón " + numero + ": ";
      MaterialInfo material = c.materialId() == null ? null : materiales.get(c.materialId());
      if (material == null) {
        throw ErrorNegocio.invalido("MATERIAL_NO_ENCONTRADO", donde + "no existe el material");
      }
      if (!material.activo()) {
        throw ErrorNegocio.conflicto(
            "MATERIAL_INACTIVO", donde + material.nombre() + " está desactivado");
      }
      if (c.cantidad() == null || c.cantidad().signum() <= 0) {
        throw ErrorNegocio.invalido(
            "CANTIDAD_INVALIDA", donde + "la cantidad debe ser mayor que cero");
      }
      long unidadCaptura = c.unidadId() == null ? material.unidadControlId() : c.unidadId();
      BigDecimal cantidad =
          unidadCaptura == material.unidadControlId()
              ? c.cantidad().setScale(InventarioApi.DECIMALES_CANTIDAD, RoundingMode.HALF_UP)
              : catalogo.aUnidadControl(material.id(), unidadCaptura, c.cantidad());
      if (cantidad.signum() <= 0) {
        throw ErrorNegocio.invalido("CANTIDAD_INVALIDA", donde + "la cantidad convertida es cero");
      }
      List<Long> lotes =
          c.unidadObraIds() == null
              ? List.of()
              : List.copyOf(new LinkedHashSet<>(c.unidadObraIds()));
      if (lotes.isEmpty()) {
        throw ErrorNegocio.invalido("FALTA_LOTE", donde + "elige el lote");
      }
      for (Long lote : lotes) {
        UnidadInfo u = unidades.get(lote);
        if (u == null || u.contratoId() != contratoId) {
          throw ErrorNegocio.invalido(
              "LOTE_DE_OTRO_CONTRATO", donde + "el lote no es del contrato " + contrato.codigo());
        }
        if (!u.activa()) {
          throw ErrorNegocio.conflicto(
              "LOTE_INACTIVO", donde + "el lote " + u.clave() + " está inactivo");
        }
      }
      PaqueteInfo paquete = c.paqueteId() == null ? null : paquetes.get(c.paqueteId());
      if (paquete == null) {
        throw ErrorNegocio.invalido("PAQUETE_NO_ENCONTRADO", donde + "elige el paquete");
      }
      if (!paquete.activo()) {
        throw ErrorNegocio.conflicto(
            "PAQUETE_INACTIVO", donde + "el paquete " + paquete.clave() + " está inactivo");
      }
      if (c.clasificacionId() == null || !clasificaciones.contains(c.clasificacionId())) {
        throw ErrorNegocio.invalido(
            "CLASIFICACION_NO_ENCONTRADA", donde + "elige la clasificación (Programa, Garantía…)");
      }
      boolean puedeFraccion = fraccionable.getOrDefault(material.unidadControlId(), true);
      if (!puedeFraccion && cantidad.stripTrailingZeros().scale() > 0) {
        throw ErrorNegocio.invalido(
            "CANTIDAD_FRACCIONARIA",
            donde + material.nombre() + " se lleva en " + material.unidadControl() + " enteras");
      }
      List<BigDecimal> partes =
          InventarioApi.repartir(
              cantidad, lotes.size(), puedeFraccion ? InventarioApi.DECIMALES_CANTIDAD : 0);
      if (partes.stream().anyMatch(p -> p.signum() <= 0)) {
        throw ErrorNegocio.invalido(
            "REPARTO_INSUFICIENTE",
            donde
                + "no alcanza para "
                + lotes.size()
                + " lotes en "
                + material.unidadControl()
                + " enteras");
      }
      List<BigDecimal> capturas =
          lotes.size() == 1
              ? List.of(c.cantidad())
              : InventarioApi.prorratear(c.cantidad(), partes, InventarioApi.DECIMALES_CANTIDAD);
      boolean capturaEnControl = capturas.stream().anyMatch(x -> x.signum() <= 0);
      String observaciones = Textos.limpio(c.observaciones());
      for (int i = 0; i < lotes.size(); i++) {
        resultado.add(
            new NuevoRenglon(
                numero,
                material.id(),
                partes.get(i),
                capturaEnControl ? partes.get(i) : capturas.get(i),
                capturaEnControl ? material.unidadControlId() : unidadCaptura,
                lotes.get(i),
                paquete.id(),
                c.clasificacionId(),
                observaciones));
      }
    }
    if (resultado.size() > RENGLONES_MAXIMOS) {
      throw ErrorNegocio.invalido(
          "DEMASIADOS_RENGLONES",
          "La requisición pasa de " + RENGLONES_MAXIMOS + " renglones por lote: divídela");
    }
    Cabecera cabecera =
        new Cabecera(
            contratoId,
            contrato.frenteId(),
            almacen.id(),
            hoy(),
            d.fechaRequerida(),
            Boolean.TRUE.equals(d.urgente()),
            Textos.limpio(d.observaciones()));
    return new Preparada(cabecera, resultado);
  }

  // ------------------------------------------------------------------------------ Presentar

  private Requisicion presentar(FilaRequisicion r, List<FilaRenglon> renglones) {
    List<Long> ids = renglones.stream().map(FilaRenglon::id).toList();
    Map<Long, Surtido> surtido = inventario.surtido(ids);
    Map<Long, BigDecimal> reservado = inventario.reservado(ids);
    Map<Long, BigDecimal> porRecibir = porRecibir(ids);
    Set<Long> idsMateriales =
        renglones.stream().map(FilaRenglon::materialId).collect(Collectors.toSet());
    Map<Long, Disponibilidad> disponible = inventario.disponibilidad(r.almacenId(), idsMateriales);
    Map<Long, MaterialInfo> materiales =
        catalogo.materiales(idsMateriales).stream()
            .collect(Collectors.toMap(MaterialInfo::id, Function.identity()));
    Map<Long, String> unidadesMedida =
        catalogo.unidades().stream()
            .collect(Collectors.toMap(CatalogoApi.UnidadInfo::id, CatalogoApi.UnidadInfo::clave));
    Map<Long, String> lotes =
        estructura
            .unidades(renglones.stream().map(FilaRenglon::unidadObraId).collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(UnidadInfo::id, UnidadInfo::clave));
    Map<Long, PaqueteInfo> paquetes =
        estructura
            .paquetes(renglones.stream().map(FilaRenglon::paqueteId).collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(PaqueteInfo::id, Function.identity()));
    Map<Integer, String> clasificaciones =
        catalogo.clasificaciones().stream()
            .collect(Collectors.toMap(ClasificacionInfo::id, ClasificacionInfo::nombre));

    List<RenglonRequisicion> lista = new ArrayList<>();
    List<Avance> avances = new ArrayList<>();
    for (FilaRenglon l : renglones) {
      Avance a = avance(l, surtido, reservado, porRecibir);
      avances.add(a);
      MaterialInfo m = materiales.get(l.materialId());
      PaqueteInfo p = paquetes.get(l.paqueteId());
      Disponibilidad d = disponible.get(l.materialId());
      lista.add(
          new RenglonRequisicion(
              l.id(),
              l.renglon(),
              l.materialId(),
              m == null ? "" : m.clave(),
              m == null ? "" : m.nombre(),
              m == null ? "" : m.unidadControl(),
              l.cantidad(),
              l.cantidadCaptura(),
              l.unidadCapturaId(),
              unidadesMedida.getOrDefault(l.unidadCapturaId(), ""),
              l.unidadObraId(),
              lotes.getOrDefault(l.unidadObraId(), ""),
              l.paqueteId(),
              p == null ? "" : p.clave() + " " + p.nombre(),
              l.clasificacionId(),
              clasificaciones.getOrDefault(l.clasificacionId(), ""),
              l.observaciones(),
              a.surtido(),
              a.enVale(),
              a.reservado(),
              a.porRecibir(),
              a.porSurtir(),
              a.faltante(),
              d == null ? BigDecimal.ZERO : d.disponible()));
    }

    Map<Long, ValeDeRequisicion> vales = new LinkedHashMap<>();
    for (DocumentoLigado v : inventario.valesDe(ids)) {
      vales.merge(
          v.movimientoId(),
          new ValeDeRequisicion(v.movimientoId(), v.folio(), v.estado(), v.fecha(), 1),
          (a, b) ->
              new ValeDeRequisicion(a.id(), a.folio(), a.estado(), a.fecha(), a.renglones() + 1));
    }
    Map<Long, PedidoDeRequisicion> pedidosDe = new LinkedHashMap<>();
    for (FilaCobertura c : repositorio.coberturas(empresa(), ids)) {
      pedidosDe.merge(
          c.pedidoId(),
          new PedidoDeRequisicion(c.pedidoId(), c.folio(), c.estado(), c.cantidad(), c.recibido()),
          (a, b) ->
              new PedidoDeRequisicion(
                  a.id(),
                  a.folio(),
                  a.estado(),
                  a.cantidad().add(b.cantidad()),
                  a.recibido().add(b.recibido())));
    }

    Map<Long, String> nombres =
        nombresDeUsuarios(listaSinNulos(r.usuarioCaptura(), r.usuarioAutoriza()));
    ContratoInfo contrato = contrato(r.contratoId());
    String almacen = inventario.almacen(r.almacenId()).map(AlmacenInfo::nombre).orElse("");
    return new Requisicion(
        r.id(),
        r.folio(),
        r.estado(),
        r.contratoId(),
        contrato.codigo(),
        r.almacenId(),
        almacen,
        r.fecha(),
        r.fechaRequerida(),
        r.urgente(),
        r.observaciones(),
        r.notaRechazo(),
        r.motivoCierre(),
        nombres.getOrDefault(r.usuarioCaptura(), ""),
        r.usuarioAutoriza() == null ? null : nombres.get(r.usuarioAutoriza()),
        r.autorizadaEn(),
        r.cerradaEn(),
        r.creado(),
        Avance.porcentaje(avances),
        r.version(),
        lista,
        List.copyOf(vales.values()),
        List.copyOf(pedidosDe.values()),
        acciones(r, avances));
  }

  private List<ResumenRequisicion> resumenes(List<FilaResumen> filas) {
    if (filas.isEmpty()) {
      return List.of();
    }
    List<FilaRenglon> renglones =
        repositorio.renglonesDe(empresa(), filas.stream().map(FilaResumen::id).toList());
    List<Long> ids = renglones.stream().map(FilaRenglon::id).toList();
    Map<Long, Surtido> surtido = inventario.surtido(ids);
    Map<Long, BigDecimal> reservado = inventario.reservado(ids);
    Map<Long, BigDecimal> porRecibir = porRecibir(ids);
    Map<Long, List<Avance>> avances = new HashMap<>();
    for (FilaRenglon l : renglones) {
      avances
          .computeIfAbsent(l.requisicionId(), k -> new ArrayList<>())
          .add(avance(l, surtido, reservado, porRecibir));
    }
    Map<Long, String> contratos =
        estructura
            .contratos(filas.stream().map(FilaResumen::contratoId).collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(ContratoInfo::id, ContratoInfo::codigo));
    Map<Long, String> almacenes =
        inventario.almacenes().stream()
            .collect(Collectors.toMap(AlmacenInfo::id, AlmacenInfo::nombre));
    Set<Long> idsUsuarios = new HashSet<>();
    filas.forEach(
        f -> {
          idsUsuarios.add(f.usuarioCaptura());
          if (f.usuarioAutoriza() != null) {
            idsUsuarios.add(f.usuarioAutoriza());
          }
        });
    Map<Long, String> nombres = nombresDeUsuarios(idsUsuarios);
    return filas.stream()
        .map(
            f -> {
              List<Avance> lista = avances.getOrDefault(f.id(), List.of());
              boolean conFaltante =
                  f.estado().equals("autorizada")
                      && lista.stream().anyMatch(a -> a.faltante().signum() > 0);
              return new ResumenRequisicion(
                  f.id(),
                  f.folio(),
                  f.estado(),
                  f.contratoId(),
                  contratos.getOrDefault(f.contratoId(), ""),
                  almacenes.getOrDefault(f.almacenId(), ""),
                  f.fecha(),
                  f.fechaRequerida(),
                  f.urgente(),
                  nombres.getOrDefault(f.usuarioCaptura(), ""),
                  f.usuarioAutoriza() == null ? null : nombres.get(f.usuarioAutoriza()),
                  f.renglones(),
                  Avance.porcentaje(lista),
                  conFaltante,
                  f.creado());
            })
        .toList();
  }

  private AccionesRequisicion acciones(FilaRequisicion r, List<Avance> avances) {
    boolean crea = Autorizacion.tiene(Permisos.REQUISICION_CREAR);
    boolean autoriza = Autorizacion.tiene(Permisos.DOCUMENTO_AUTORIZAR);
    boolean almacen = Autorizacion.tiene(Permisos.MOVIMIENTO_CAPTURAR);
    boolean reserva = almacen || autoriza || Autorizacion.tiene(Permisos.PEDIDO_CREAR);
    boolean borrador = r.estado().equals("borrador");
    boolean autorizada = r.estado().equals("autorizada");
    boolean sinMovimiento =
        avances.stream().allMatch(a -> a.surtido().signum() == 0 && a.enVale().signum() == 0);
    return new AccionesRequisicion(
        crea && borrador,
        crea && borrador,
        autoriza && r.estado().equals("pendiente") && r.usuarioCaptura() != usuario(),
        (crea || autoriza)
            && (borrador || r.estado().equals("pendiente") || (autorizada && sinMovimiento)),
        (crea || autoriza) && autorizada,
        reserva
            && autorizada
            && avances.stream()
                .anyMatch(
                    a -> a.faltante().signum() > 0 || a.porSurtir().compareTo(a.reservado()) > 0),
        almacen && autorizada && avances.stream().anyMatch(a -> a.porSurtir().signum() > 0));
  }

  // ------------------------------------------------------------------------------ Apoyo

  static Avance avance(
      FilaRenglon l,
      Map<Long, Surtido> surtido,
      Map<Long, BigDecimal> reservado,
      Map<Long, BigDecimal> porRecibir) {
    Surtido s = surtido.get(l.id());
    return new Avance(
        l.cantidad(),
        s == null ? BigDecimal.ZERO : s.surtido(),
        s == null ? BigDecimal.ZERO : s.enProceso(),
        reservado.get(l.id()),
        porRecibir.get(l.id()));
  }

  private Map<Long, BigDecimal> porRecibir(Collection<Long> lineas) {
    Map<Long, BigDecimal> resultado = new HashMap<>();
    for (FilaLiga f : pedidos.porRecibir(empresa(), lineas)) {
      resultado.put(f.requisicionLineaId(), f.cantidad());
    }
    return resultado;
  }

  /** La requisición bloqueada, si el usuario la puede ver. */
  private FilaRequisicion bloquear(long id) {
    FilaRequisicion r =
        repositorio.bloquear(empresa(), id).orElseThrow(ServicioRequisiciones::noEncontrada);
    exigirVisible(r);
    return r;
  }

  private void exigirVisible(FilaRequisicion r) {
    if (!estructura.veTodosLosContratos()
        && !estructura.contratosVisibles().contains(r.contratoId())) {
      throw noEncontrada();
    }
  }

  private ContratoInfo contrato(long id) {
    return estructura.contratos(List.of(id)).stream()
        .findFirst()
        .orElseThrow(
            () -> ErrorNegocio.noEncontrado("CONTRATO_NO_ENCONTRADO", "No existe el contrato"));
  }

  private Collection<Long> alcance() {
    return estructura.veTodosLosContratos() ? null : estructura.contratosVisibles();
  }

  private Map<Long, String> nombresDeUsuarios(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return usuarios.buscar(ids).stream()
        .collect(Collectors.toMap(UsuarioResumen::id, UsuarioResumen::nombre));
  }

  private static List<Long> listaSinNulos(Long... ids) {
    return Arrays.stream(ids).filter(Objects::nonNull).toList();
  }

  private static List<String> lista(String separadosPorComa) {
    if (separadosPorComa == null || separadosPorComa.isBlank()) {
      return List.of();
    }
    return Arrays.stream(separadosPorComa.split(","))
        .map(String::trim)
        .filter(e -> !e.isEmpty())
        .toList();
  }

  static ErrorNegocio noEncontrada() {
    return ErrorNegocio.noEncontrado("REQUISICION_NO_ENCONTRADA", "No existe la requisición");
  }
}

package mx.constructora.abastecimiento.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static mx.constructora.abastecimiento.aplicacion.Acceso.CAPTURAR_ALMACEN;
import static mx.constructora.abastecimiento.aplicacion.Acceso.CREAR_PEDIDO;
import static mx.constructora.abastecimiento.aplicacion.Acceso.VER_PEDIDOS;
import static mx.constructora.abastecimiento.aplicacion.Acceso.empresa;
import static mx.constructora.abastecimiento.aplicacion.Acceso.hoy;
import static mx.constructora.abastecimiento.aplicacion.Acceso.usuario;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
import mx.constructora.abastecimiento.dominio.Asignacion;
import mx.constructora.abastecimiento.dominio.Avance;
import mx.constructora.abastecimiento.infraestructura.RepositorioPedidos;
import mx.constructora.abastecimiento.infraestructura.RepositorioPedidos.Cabecera;
import mx.constructora.abastecimiento.infraestructura.RepositorioPedidos.FilaLiga;
import mx.constructora.abastecimiento.infraestructura.RepositorioPedidos.FilaPedido;
import mx.constructora.abastecimiento.infraestructura.RepositorioPedidos.FilaRenglon;
import mx.constructora.abastecimiento.infraestructura.RepositorioPedidos.FilaResumen;
import mx.constructora.abastecimiento.infraestructura.RepositorioPedidos.NuevoRenglon;
import mx.constructora.abastecimiento.infraestructura.RepositorioRequisiciones;
import mx.constructora.abastecimiento.infraestructura.RepositorioRequisiciones.RenglonConRequisicion;
import mx.constructora.catalogo.CatalogoApi;
import mx.constructora.catalogo.CatalogoApi.MaterialInfo;
import mx.constructora.catalogo.CatalogoApi.ProveedorInfo;
import mx.constructora.compartido.Autorizacion;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Folios;
import mx.constructora.compartido.Permisos;
import mx.constructora.compartido.Textos;
import mx.constructora.compartido.Versiones;
import mx.constructora.estructura.EstructuraApi;
import mx.constructora.estructura.EstructuraApi.ContratoInfo;
import mx.constructora.estructura.EstructuraApi.UnidadInfo;
import mx.constructora.historico.ConsultaHistorico;
import mx.constructora.historico.ConsultaHistorico.ProveedorDeMaterial;
import mx.constructora.inventario.InventarioApi;
import mx.constructora.inventario.InventarioApi.AlmacenInfo;
import mx.constructora.inventario.InventarioApi.DocumentoLigado;
import mx.constructora.inventario.InventarioApi.LineaRecepcion;
import mx.constructora.inventario.InventarioApi.Surtido;
import mx.constructora.organizacion.DirectorioUsuarios;
import mx.constructora.organizacion.DirectorioUsuarios.UsuarioResumen;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Faltantes por proveedor, pedido a proveedor y recepción contra pedido.
 *
 * <p>Compras ve lo que falta comprar de las requisiciones autorizadas, agrupado por el proveedor
 * que lo surtió la última vez (en el sistema o, si no, en la hoja Entradas del Excel). Arma el
 * pedido con esos renglones: lo pedido se asigna primero a las requisiciones urgentes y a las que
 * se necesitan antes. El almacén recibe contra el pedido: la recepción entra al inventario en el
 * mismo paso y lo recibido queda apartado para las requisiciones que cubre.
 */
@Service
public class ServicioPedidos {

  /** Tamaño máximo de página. */
  public static final int PAGINA_MAXIMA = 200;

  /**
   * Tasa de IVA cuando ni el renglón ni el proveedor la traen: la misma regla que la migración del
   * Excel (8%, zona fronteriza).
   */
  static final BigDecimal TASA_IVA_POR_DEFECTO = new BigDecimal("0.08");

  // ------------------------------------------------------------------------------ DTOs

  /** Renglón de requisición con faltante por comprar, en unidad de control. */
  public record Faltante(
      @Schema(requiredMode = REQUIRED) long requisicionLineaId,
      @Schema(requiredMode = REQUIRED) long requisicionId,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(requiredMode = REQUIRED) int renglon,
      @Schema(requiredMode = REQUIRED) long contratoId,
      @Schema(requiredMode = REQUIRED) String contrato,
      @Schema(requiredMode = REQUIRED) String unidadObra,
      @Schema(requiredMode = REQUIRED) LocalDate fechaRequerida,
      @Schema(requiredMode = REQUIRED) boolean urgente,
      @Schema(requiredMode = REQUIRED) long almacenId,
      @Schema(requiredMode = REQUIRED) long materialId,
      @Schema(requiredMode = REQUIRED) String materialClave,
      @Schema(requiredMode = REQUIRED) String material,
      @Schema(requiredMode = REQUIRED) long unidadControlId,
      @Schema(requiredMode = REQUIRED) String unidadControl,
      @Schema(requiredMode = REQUIRED) BigDecimal faltante,
      @Schema(description = "Sin IVA; vacío sin permiso de precios") BigDecimal precioReferencia,
      @Schema(
              description = "De dónde sale el proveedor sugerido",
              allowableValues = {"sistema", "excel"})
          String origenSugerencia) {}

  /** Faltantes que se le piden a un mismo proveedor. */
  public record GrupoFaltantes(
      @Schema(description = "Vacío si ningún proveedor ha surtido estos materiales")
          Long proveedorId,
      @Schema(requiredMode = REQUIRED) String proveedor,
      @Schema(requiredMode = REQUIRED) List<Faltante> faltantes) {}

  /** Un material del pedido, como se captura. */
  public record RenglonPedidoCaptura(
      @NotNull Long materialId,
      @Schema(description = "En la unidad capturada")
          @NotNull
          @Positive
          @Digits(integer = 10, fraction = 4)
          BigDecimal cantidad,
      @Schema(description = "Unidad capturada; vacía: la unidad de control") Long unidadId,
      @Schema(description = "Sin IVA, por unidad capturada")
          @NotNull
          @PositiveOrZero
          @Digits(integer = 10, fraction = 6)
          BigDecimal precio,
      @Schema(description = "Vacía: la del proveedor (o 8%)") @DecimalMin("0") @DecimalMax("0.16")
          BigDecimal tasaIva,
      @Schema(description = "Renglones de requisición que cubre, el más urgente primero")
          @Size(max = 500)
          List<Long> requisicionLineaIds,
      @Size(max = 300) String observaciones) {}

  /** Alta o corrección de un pedido. */
  public record DatosPedido(
      @NotNull Long proveedorId,
      @NotNull Long almacenId,
      LocalDate fechaEntrega,
      @Size(max = 500) String observaciones,
      @NotEmpty @Size(min = 1, max = 300) List<@Valid RenglonPedidoCaptura> renglones,
      @Schema(description = "Crear y enviar en un paso") Boolean enviar) {}

  /** Lo que un renglón del pedido cubre de un renglón de requisición. */
  public record Cobertura(
      @Schema(requiredMode = REQUIRED) long requisicionLineaId,
      @Schema(requiredMode = REQUIRED) long requisicionId,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(requiredMode = REQUIRED) String unidadObra,
      @Schema(requiredMode = REQUIRED) BigDecimal cantidad,
      @Schema(requiredMode = REQUIRED) BigDecimal recibido) {}

  public record RenglonPedido(
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
      @Schema(description = "Vacío sin permiso de precios") BigDecimal precio,
      @Schema(requiredMode = REQUIRED) BigDecimal tasaIva,
      @Schema(description = "Cantidad capturada × precio, sin IVA") BigDecimal importe,
      @Schema(requiredMode = REQUIRED, description = "En unidad de control, neto de devoluciones")
          BigDecimal recibido,
      @Schema(requiredMode = REQUIRED) BigDecimal pendiente,
      String observaciones,
      @Schema(requiredMode = REQUIRED) List<Cobertura> requisiciones) {}

  /** Recepción hecha contra el pedido. */
  public record RecepcionDePedido(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(requiredMode = REQUIRED) String estado,
      @Schema(requiredMode = REQUIRED) LocalDate fecha) {}

  public record AccionesPedido(
      @Schema(requiredMode = REQUIRED) boolean editar,
      @Schema(requiredMode = REQUIRED) boolean enviar,
      @Schema(requiredMode = REQUIRED) boolean recibir,
      @Schema(requiredMode = REQUIRED) boolean cerrar,
      @Schema(requiredMode = REQUIRED) boolean cancelar) {}

  public record Pedido(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"borrador", "enviado", "recibido", "cerrado", "cancelado"})
          String estado,
      @Schema(requiredMode = REQUIRED) long proveedorId,
      @Schema(requiredMode = REQUIRED) String proveedor,
      @Schema(requiredMode = REQUIRED) long almacenId,
      @Schema(requiredMode = REQUIRED) String almacen,
      @Schema(requiredMode = REQUIRED) LocalDate fecha,
      LocalDate fechaEntrega,
      String observaciones,
      String motivoCierre,
      @Schema(requiredMode = REQUIRED) String capturo,
      String envio,
      OffsetDateTime enviadoEn,
      OffsetDateTime cerradoEn,
      @Schema(requiredMode = REQUIRED) OffsetDateTime creado,
      @Schema(description = "Sin IVA; vacío sin permiso de precios") BigDecimal subtotal,
      BigDecimal iva,
      BigDecimal total,
      @Schema(requiredMode = REQUIRED, description = "Porcentaje recibido (0 a 100)")
          BigDecimal avance,
      @Schema(requiredMode = REQUIRED) int version,
      @Schema(requiredMode = REQUIRED) List<RenglonPedido> renglones,
      @Schema(requiredMode = REQUIRED) List<RecepcionDePedido> recepciones,
      @Schema(requiredMode = REQUIRED) AccionesPedido acciones) {}

  public record ResumenPedido(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(requiredMode = REQUIRED) String estado,
      @Schema(requiredMode = REQUIRED) String proveedor,
      @Schema(requiredMode = REQUIRED) String almacen,
      @Schema(requiredMode = REQUIRED) LocalDate fecha,
      LocalDate fechaEntrega,
      @Schema(requiredMode = REQUIRED) int renglones,
      @Schema(description = "Sin IVA; vacío sin permiso de precios") BigDecimal subtotal,
      @Schema(requiredMode = REQUIRED) OffsetDateTime creado) {}

  /** Cuánto llegó de un renglón del pedido, en su unidad capturada. */
  public record RenglonRecibido(
      @NotNull Long pedidoLineaId,
      @NotNull @Positive @Digits(integer = 10, fraction = 4) BigDecimal cantidad) {}

  public record DatosRecepcion(
      @NotNull LocalDate fecha,
      @Size(max = 40) String folioFactura,
      @PositiveOrZero @Digits(integer = 12, fraction = 4) BigDecimal flete,
      @Size(max = 500) String observaciones,
      @NotEmpty @Size(min = 1, max = 300) List<@Valid RenglonRecibido> renglones) {}

  /** Lo recibido que quedó apartado para una requisición. */
  public record Apartado(
      @Schema(requiredMode = REQUIRED) long requisicionId,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(requiredMode = REQUIRED) String unidadObra,
      @Schema(requiredMode = REQUIRED) String material,
      @Schema(requiredMode = REQUIRED) BigDecimal cantidad) {}

  public record ResultadoRecepcion(
      @Schema(requiredMode = REQUIRED) long movimientoId,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(requiredMode = REQUIRED) List<Apartado> apartados,
      @Schema(requiredMode = REQUIRED) Pedido pedido) {}

  // ------------------------------------------------------------------------------ Servicio

  private final RepositorioPedidos repositorio;
  private final RepositorioRequisiciones requisiciones;
  private final InventarioApi inventario;
  private final ConsultaHistorico historico;
  private final CatalogoApi catalogo;
  private final EstructuraApi estructura;
  private final DirectorioUsuarios usuarios;
  private final Folios folios;

  public ServicioPedidos(
      RepositorioPedidos repositorio,
      RepositorioRequisiciones requisiciones,
      InventarioApi inventario,
      ConsultaHistorico historico,
      CatalogoApi catalogo,
      EstructuraApi estructura,
      DirectorioUsuarios usuarios,
      Folios folios) {
    this.repositorio = repositorio;
    this.requisiciones = requisiciones;
    this.inventario = inventario;
    this.historico = historico;
    this.catalogo = catalogo;
    this.estructura = estructura;
    this.usuarios = usuarios;
    this.folios = folios;
  }

  // ------------------------------------------------------------------------------ Faltantes

  /**
   * Lo que falta comprar de las requisiciones autorizadas, agrupado por proveedor sugerido (los
   * grupos sin proveedor al final). Dentro de cada grupo, lo más urgente primero.
   */
  @PreAuthorize(CREAR_PEDIDO)
  @Transactional(readOnly = true)
  public List<GrupoFaltantes> faltantes(Long proveedorId, Long almacenId) {
    List<RenglonConRequisicion> renglones =
        requisiciones.autorizados(empresa(), null).stream()
            .filter(r -> almacenId == null || r.almacenId() == almacenId)
            .toList();
    Map<Long, BigDecimal> faltantes = faltantes(renglones);
    List<RenglonConRequisicion> conFaltante =
        renglones.stream().filter(r -> faltantes.get(r.id()).signum() > 0).toList();
    if (conFaltante.isEmpty()) {
      return List.of();
    }
    Set<Long> idsMateriales =
        conFaltante.stream().map(RenglonConRequisicion::materialId).collect(Collectors.toSet());
    Map<Long, Long> delSistema = inventario.ultimosProveedores(idsMateriales);
    Map<Long, ProveedorDeMaterial> delExcel = historico.ultimosProveedores(idsMateriales);
    Map<Long, MaterialInfo> materiales = materiales(idsMateriales);
    Map<Long, String> contratos =
        estructura
            .contratos(
                conFaltante.stream()
                    .map(RenglonConRequisicion::contratoId)
                    .collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(ContratoInfo::id, ContratoInfo::codigo));
    Map<Long, String> lotes = lotes(conFaltante.stream().map(RenglonConRequisicion::unidadObraId));
    boolean precios = vePrecios();

    Map<Long, List<Faltante>> porProveedor = new LinkedHashMap<>();
    List<Faltante> sinProveedor = new ArrayList<>();
    for (RenglonConRequisicion r : conFaltante) {
      Long sugerido = delSistema.get(r.materialId());
      String origen = sugerido == null ? null : "sistema";
      if (sugerido == null && delExcel.containsKey(r.materialId())) {
        sugerido = delExcel.get(r.materialId()).proveedorId();
        origen = "excel";
      }
      if (proveedorId != null && !proveedorId.equals(sugerido)) {
        continue;
      }
      MaterialInfo m = materiales.get(r.materialId());
      Faltante f =
          new Faltante(
              r.id(),
              r.requisicionId(),
              r.folio(),
              r.renglon(),
              r.contratoId(),
              contratos.getOrDefault(r.contratoId(), ""),
              lotes.getOrDefault(r.unidadObraId(), ""),
              r.fechaRequerida(),
              r.urgente(),
              r.almacenId(),
              r.materialId(),
              m == null ? "" : m.clave(),
              m == null ? "" : m.nombre(),
              m == null ? 0 : m.unidadControlId(),
              m == null ? "" : m.unidadControl(),
              faltantes.get(r.id()),
              precios && m != null ? m.precioReferencia() : null,
              origen);
      if (sugerido == null) {
        sinProveedor.add(f);
      } else {
        porProveedor.computeIfAbsent(sugerido, k -> new ArrayList<>()).add(f);
      }
    }
    Map<Long, String> nombres =
        catalogo.proveedores(porProveedor.keySet()).stream()
            .collect(Collectors.toMap(ProveedorInfo::id, ProveedorInfo::nombre));
    List<GrupoFaltantes> grupos = new ArrayList<>();
    porProveedor.forEach(
        (id, lista) -> grupos.add(new GrupoFaltantes(id, nombres.getOrDefault(id, ""), lista)));
    grupos.sort(Comparator.comparing(GrupoFaltantes::proveedor, String.CASE_INSENSITIVE_ORDER));
    if (!sinProveedor.isEmpty()) {
      grupos.add(new GrupoFaltantes(null, "Sin proveedor sugerido", sinProveedor));
    }
    return grupos;
  }

  /** Faltante de cada renglón (cero si no le falta). */
  private Map<Long, BigDecimal> faltantes(List<RenglonConRequisicion> renglones) {
    List<Long> ids = renglones.stream().map(RenglonConRequisicion::id).toList();
    Map<Long, Surtido> surtido = inventario.surtido(ids);
    Map<Long, BigDecimal> reservado = inventario.reservado(ids);
    Map<Long, BigDecimal> porRecibir = new HashMap<>();
    for (FilaLiga f : repositorio.porRecibir(empresa(), ids)) {
      porRecibir.put(f.requisicionLineaId(), f.cantidad());
    }
    Map<Long, BigDecimal> resultado = new HashMap<>();
    for (RenglonConRequisicion r : renglones) {
      Surtido s = surtido.get(r.id());
      Avance a =
          new Avance(
              r.cantidad(),
              s == null ? null : s.surtido(),
              s == null ? null : s.enProceso(),
              reservado.get(r.id()),
              porRecibir.get(r.id()));
      resultado.put(r.id(), a.faltante());
    }
    return resultado;
  }

  // ------------------------------------------------------------------------------ Captura

  @PreAuthorize(CREAR_PEDIDO)
  @Transactional
  public Pedido crear(DatosPedido datos) {
    Cabecera cabecera = cabecera(datos);
    long id = repositorio.insertar(empresa(), folios.siguiente("PED"), cabecera, usuario());
    guardarRenglones(id, cabecera, datos);
    if (Boolean.TRUE.equals(datos.enviar())) {
      repositorio.enviar(empresa(), id, usuario());
    }
    return pedido(id);
  }

  @PreAuthorize(CREAR_PEDIDO)
  @Transactional
  public Pedido reemplazar(long id, int version, DatosPedido datos) {
    FilaPedido actual = bloquear(id);
    if (!actual.estado().equals("borrador")) {
      throw ErrorNegocio.conflicto("NO_ES_BORRADOR", "Solo se corrige un pedido en borrador");
    }
    Cabecera cabecera = cabecera(datos);
    if (repositorio.actualizarCabecera(empresa(), id, version, cabecera) == 0) {
      throw Versiones.desactualizada();
    }
    // Primero se borran los renglones (y sus ligas) para que no cuenten como ya pedidos.
    repositorio.borrarRenglones(empresa(), id);
    guardarRenglones(id, cabecera, datos);
    if (Boolean.TRUE.equals(datos.enviar())) {
      repositorio.enviar(empresa(), id, usuario());
    }
    return pedido(id);
  }

  @PreAuthorize(CREAR_PEDIDO)
  @Transactional
  public Pedido enviar(long id) {
    FilaPedido p = bloquear(id);
    if (!p.estado().equals("borrador")) {
      throw ErrorNegocio.conflicto("NO_ES_BORRADOR", "El pedido ya se envió");
    }
    repositorio.enviar(empresa(), id, usuario());
    return pedido(id);
  }

  /** Da por terminado un pedido enviado: lo que no llegó vuelve a ser faltante. */
  @PreAuthorize(CREAR_PEDIDO)
  @Transactional
  public Pedido cerrar(long id, String motivo) {
    String texto = exigirMotivo(motivo);
    FilaPedido p = bloquear(id);
    if (!p.estado().equals("enviado")) {
      throw ErrorNegocio.conflicto("PEDIDO_NO_ENVIADO", "Solo se cierra un pedido enviado");
    }
    repositorio.terminar(empresa(), id, "cerrado", texto);
    return pedido(id);
  }

  /** Cancela un borrador, o un pedido enviado del que no ha llegado nada. */
  @PreAuthorize(CREAR_PEDIDO)
  @Transactional
  public Pedido cancelar(long id, String motivo) {
    String texto = exigirMotivo(motivo);
    FilaPedido p = bloquear(id);
    switch (p.estado()) {
      case "borrador" -> {}
      case "enviado" -> {
        List<Long> lineas =
            repositorio.renglones(empresa(), id).stream().map(FilaRenglon::id).toList();
        boolean llego =
            inventario.recibido(lineas).values().stream().anyMatch(r -> r.signum() != 0);
        if (llego || !inventario.recepcionesDe(lineas).isEmpty()) {
          throw ErrorNegocio.conflicto(
              "PEDIDO_CON_RECEPCION",
              "Ya se recibió parte del pedido: ciérralo en lugar de cancelarlo");
        }
      }
      default ->
          throw ErrorNegocio.conflicto(
              "PEDIDO_TERMINADO", "El pedido " + p.folio() + " ya está " + p.estado());
    }
    repositorio.terminar(empresa(), id, "cancelado", texto);
    return pedido(id);
  }

  private Cabecera cabecera(DatosPedido d) {
    ProveedorInfo proveedor =
        catalogo
            .proveedor(d.proveedorId())
            .orElseThrow(
                () -> ErrorNegocio.invalido("PROVEEDOR_NO_ENCONTRADO", "No existe el proveedor"));
    if (!proveedor.activo()) {
      throw ErrorNegocio.conflicto("PROVEEDOR_INACTIVO", "El proveedor está desactivado");
    }
    AlmacenInfo almacen =
        inventario
            .almacen(d.almacenId())
            .orElseThrow(
                () -> ErrorNegocio.invalido("ALMACEN_NO_ENCONTRADO", "No existe el almacén"));
    if (!almacen.activo()) {
      throw ErrorNegocio.conflicto(
          "ALMACEN_INACTIVO", "El almacén " + almacen.clave() + " está desactivado");
    }
    if (d.fechaEntrega() != null && d.fechaEntrega().isBefore(hoy())) {
      throw ErrorNegocio.invalido("FECHA_PASADA", "La fecha de entrega ya pasó");
    }
    return new Cabecera(
        proveedor.id(), almacen.id(), hoy(), d.fechaEntrega(), Textos.limpio(d.observaciones()));
  }

  /**
   * Guarda los renglones y asigna lo pedido a los renglones de requisición indicados, el más
   * urgente primero, hasta su faltante. Lo que sobra es para existencia del almacén.
   */
  private void guardarRenglones(long pedido, Cabecera cabecera, DatosPedido d) {
    if (d.renglones() == null || d.renglones().isEmpty()) {
      throw ErrorNegocio.invalido("SIN_RENGLONES", "Agrega al menos un material");
    }
    ProveedorInfo proveedor = catalogo.proveedor(cabecera.proveedorId()).orElseThrow();
    Map<Long, MaterialInfo> materiales =
        materiales(
            d.renglones().stream()
                .map(RenglonPedidoCaptura::materialId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
    Set<Long> idsRequisicion = new LinkedHashSet<>();
    d.renglones().stream()
        .filter(r -> r.requisicionLineaIds() != null)
        .forEach(r -> idsRequisicion.addAll(r.requisicionLineaIds()));
    Map<Long, RenglonConRequisicion> renglonesRequisicion =
        requisiciones.conRequisicion(empresa(), idsRequisicion).stream()
            .collect(
                Collectors.toMap(
                    RenglonConRequisicion::id,
                    Function.identity(),
                    (a, b) -> a,
                    LinkedHashMap::new));
    // Faltante vivo de cada renglón de requisición; baja conforme este pedido lo cubre.
    Map<Long, BigDecimal> faltante =
        new HashMap<>(faltantes(List.copyOf(renglonesRequisicion.values())));

    int numero = 0;
    for (RenglonPedidoCaptura c : d.renglones()) {
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
      if (c.precio() == null || c.precio().signum() < 0) {
        throw ErrorNegocio.invalido("FALTA_PRECIO", donde + "escribe el precio sin IVA");
      }
      long unidad = c.unidadId() == null ? material.unidadControlId() : c.unidadId();
      BigDecimal cantidad =
          unidad == material.unidadControlId()
              ? c.cantidad().setScale(InventarioApi.DECIMALES_CANTIDAD, RoundingMode.HALF_UP)
              : catalogo.aUnidadControl(material.id(), unidad, c.cantidad());
      if (cantidad.signum() <= 0) {
        throw ErrorNegocio.invalido("CANTIDAD_INVALIDA", donde + "la cantidad convertida es cero");
      }
      BigDecimal tasa =
          c.tasaIva() != null
              ? c.tasaIva()
              : proveedor.tasaIva() != null ? proveedor.tasaIva() : TASA_IVA_POR_DEFECTO;
      long linea =
          repositorio.insertarRenglon(
              empresa(),
              pedido,
              new NuevoRenglon(
                  numero,
                  material.id(),
                  cantidad,
                  c.cantidad(),
                  unidad,
                  c.precio(),
                  tasa,
                  Textos.limpio(c.observaciones())));

      List<RenglonConRequisicion> cubre = new ArrayList<>();
      for (Long id : c.requisicionLineaIds() == null ? List.<Long>of() : c.requisicionLineaIds()) {
        RenglonConRequisicion r = renglonesRequisicion.get(id);
        if (r == null || !r.estado().equals("autorizada")) {
          throw ErrorNegocio.invalido(
              "REQUISICION_NO_AUTORIZADA",
              donde + "solo se piden renglones de requisiciones autorizadas");
        }
        if (r.materialId() != material.id()) {
          throw ErrorNegocio.invalido(
              "REQUISICION_OTRO_MATERIAL",
              donde + "la requisición " + r.folio() + " pide otro material");
        }
        if (r.almacenId() != cabecera.almacenId()) {
          throw ErrorNegocio.invalido(
              "REQUISICION_OTRO_ALMACEN",
              donde + "la requisición " + r.folio() + " es de otro almacén");
        }
        if (cubre.stream().noneMatch(x -> x.id() == r.id())) {
          cubre.add(r);
        }
      }
      // En el orden de urgencia de las requisiciones, no en el de captura.
      List<RenglonConRequisicion> enOrden =
          renglonesRequisicion.values().stream().filter(cubre::contains).toList();
      List<BigDecimal> asignado =
          Asignacion.enOrden(cantidad, enOrden.stream().map(r -> faltante.get(r.id())).toList());
      for (int i = 0; i < enOrden.size(); i++) {
        BigDecimal x = asignado.get(i);
        if (x.signum() > 0) {
          RenglonConRequisicion r = enOrden.get(i);
          repositorio.ligar(empresa(), linea, r.id(), material.id(), x);
          faltante.put(r.id(), faltante.get(r.id()).subtract(x));
        }
      }
    }
  }

  // ------------------------------------------------------------------------------ Recepción

  /**
   * Recibe contra el pedido: crea y aplica la recepción en el inventario, asigna lo recibido a las
   * requisiciones que cubre cada renglón (en el orden en que se pidieron) y lo aparta para ellas.
   * Cuando todo llegó, el pedido queda recibido.
   */
  @PreAuthorize(CAPTURAR_ALMACEN)
  @Transactional
  public ResultadoRecepcion recibir(long id, DatosRecepcion datos) {
    FilaPedido p = bloquear(id);
    if (!p.estado().equals("enviado")) {
      throw ErrorNegocio.conflicto(
          "PEDIDO_NO_ENVIADO", "Solo se recibe un pedido enviado (este está " + p.estado() + ")");
    }
    Map<Long, FilaRenglon> renglones =
        repositorio.renglones(empresa(), id).stream()
            .collect(Collectors.toMap(FilaRenglon::id, Function.identity()));
    Map<Long, MaterialInfo> materiales =
        materiales(
            renglones.values().stream().map(FilaRenglon::materialId).collect(Collectors.toSet()));
    Set<Long> vistos = new HashSet<>();
    List<LineaRecepcion> lineas = new ArrayList<>();
    Map<Long, BigDecimal> enControl = new HashMap<>();
    for (RenglonRecibido r : datos.renglones()) {
      FilaRenglon l = renglones.get(r.pedidoLineaId());
      if (l == null) {
        throw ErrorNegocio.invalido(
            "RENGLON_NO_ENCONTRADO", "El renglón no es del pedido " + p.folio());
      }
      if (!vistos.add(l.id())) {
        throw ErrorNegocio.invalido(
            "RENGLON_REPETIDO", "Un renglón va una sola vez en la recepción");
      }
      lineas.add(
          new LineaRecepcion(
              l.id(), l.materialId(), r.cantidad(), l.unidadCapturaId(), l.precio(), l.tasaIva()));
      MaterialInfo material = materiales.get(l.materialId());
      enControl.put(
          l.id(),
          material != null && material.unidadControlId() == l.unidadCapturaId()
              ? r.cantidad().setScale(InventarioApi.DECIMALES_CANTIDAD, RoundingMode.HALF_UP)
              : catalogo.aUnidadControl(l.materialId(), l.unidadCapturaId(), r.cantidad()));
    }
    String nota = Textos.limpio(datos.observaciones());
    String observaciones = "Pedido " + p.folio() + (nota == null ? "" : ": " + nota);
    var recepcion =
        inventario.recibir(
            p.almacenId(),
            p.proveedorId(),
            datos.fecha(),
            Textos.limpio(datos.folioFactura()),
            datos.flete(),
            observaciones.length() > 500 ? observaciones.substring(0, 500) : observaciones,
            lineas);

    // Lo recibido llena las ligas con requisiciones (la más urgente primero) y se aparta.
    List<FilaLiga> ligas = repositorio.ligas(empresa(), id);
    Map<Long, RenglonConRequisicion> renglonesRequisicion =
        requisiciones
            .conRequisicion(
                empresa(),
                ligas.stream().map(FilaLiga::requisicionLineaId).collect(Collectors.toSet()))
            .stream()
            .collect(
                Collectors.toMap(
                    RenglonConRequisicion::id,
                    Function.identity(),
                    (a, b) -> a,
                    LinkedHashMap::new));
    List<Long> ordenRequisicion = List.copyOf(renglonesRequisicion.keySet());
    List<Apartado> apartados = new ArrayList<>();
    Map<Long, String> lotes =
        lotes(renglonesRequisicion.values().stream().map(RenglonConRequisicion::unidadObraId));
    List<FilaRenglon> recibidos =
        enControl.keySet().stream()
            .map(renglones::get)
            .sorted(
                Comparator.comparingLong(FilaRenglon::materialId)
                    .thenComparingInt(FilaRenglon::renglon))
            .toList();
    for (FilaRenglon l : recibidos) {
      List<FilaLiga> deLinea =
          ligas.stream()
              .filter(g -> g.pedidoLineaId() == l.id())
              .filter(
                  g -> {
                    RenglonConRequisicion r = renglonesRequisicion.get(g.requisicionLineaId());
                    return r != null && r.estado().equals("autorizada");
                  })
              .sorted(
                  Comparator.comparingInt(g -> ordenRequisicion.indexOf(g.requisicionLineaId())))
              .toList();
      List<BigDecimal> asignado =
          Asignacion.enOrden(
              enControl.get(l.id()),
              deLinea.stream().map(g -> g.cantidad().subtract(g.recibido())).toList());
      for (int i = 0; i < deLinea.size(); i++) {
        BigDecimal x = asignado.get(i);
        if (x.signum() <= 0) {
          continue;
        }
        FilaLiga g = deLinea.get(i);
        RenglonConRequisicion r = renglonesRequisicion.get(g.requisicionLineaId());
        repositorio.sumarRecibido(empresa(), l.id(), r.id(), x);
        BigDecimal apartado = inventario.reservar(p.almacenId(), l.materialId(), r.id(), x);
        if (apartado.signum() > 0) {
          MaterialInfo m = materiales.get(l.materialId());
          apartados.add(
              new Apartado(
                  r.requisicionId(),
                  r.folio(),
                  lotes.getOrDefault(r.unidadObraId(), ""),
                  m == null ? "" : m.nombre(),
                  apartado));
        }
      }
    }

    Map<Long, BigDecimal> recibido = inventario.recibido(renglones.keySet());
    boolean completo =
        renglones.values().stream()
            .allMatch(
                l -> recibido.getOrDefault(l.id(), BigDecimal.ZERO).compareTo(l.cantidad()) >= 0);
    if (completo) {
      repositorio.marcarRecibido(empresa(), id);
    }
    return new ResultadoRecepcion(recepcion.id(), recepcion.folio(), apartados, pedido(id));
  }

  // ------------------------------------------------------------------------------ Consulta

  @PreAuthorize(VER_PEDIDOS)
  @Transactional(readOnly = true)
  public Pedido pedido(long id) {
    FilaPedido p = repositorio.pedido(empresa(), id).orElseThrow(ServicioPedidos::noEncontrado);
    return presentar(p);
  }

  @PreAuthorize(VER_PEDIDOS)
  @Transactional(readOnly = true)
  public List<ResumenPedido> buscar(
      String estados,
      Long proveedorId,
      Long almacenId,
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
            proveedorId,
            almacenId,
            desde,
            hasta,
            Textos.limpio(folio),
            limite,
            (long) Math.max(pagina, 0) * limite);
    Map<Long, String> proveedores =
        catalogo
            .proveedores(filas.stream().map(FilaResumen::proveedorId).collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(ProveedorInfo::id, ProveedorInfo::nombre));
    Map<Long, String> almacenes =
        inventario.almacenes().stream()
            .collect(Collectors.toMap(AlmacenInfo::id, AlmacenInfo::nombre));
    boolean precios = vePrecios();
    return filas.stream()
        .map(
            f ->
                new ResumenPedido(
                    f.id(),
                    f.folio(),
                    f.estado(),
                    proveedores.getOrDefault(f.proveedorId(), ""),
                    almacenes.getOrDefault(f.almacenId(), ""),
                    f.fecha(),
                    f.fechaEntrega(),
                    f.renglones(),
                    precios ? f.subtotal() : null,
                    f.creado()))
        .toList();
  }

  private Pedido presentar(FilaPedido p) {
    List<FilaRenglon> renglones = repositorio.renglones(empresa(), p.id());
    List<Long> ids = renglones.stream().map(FilaRenglon::id).toList();
    Map<Long, BigDecimal> recibido = inventario.recibido(ids);
    List<FilaLiga> ligas = repositorio.ligas(empresa(), p.id());
    Map<Long, RenglonConRequisicion> renglonesRequisicion =
        requisiciones
            .conRequisicion(
                empresa(),
                ligas.stream().map(FilaLiga::requisicionLineaId).collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(RenglonConRequisicion::id, Function.identity()));
    Map<Long, String> lotes =
        lotes(renglonesRequisicion.values().stream().map(RenglonConRequisicion::unidadObraId));
    Map<Long, MaterialInfo> materiales =
        materiales(renglones.stream().map(FilaRenglon::materialId).collect(Collectors.toSet()));
    Map<Long, String> unidadesMedida =
        catalogo.unidades().stream()
            .collect(Collectors.toMap(CatalogoApi.UnidadInfo::id, CatalogoApi.UnidadInfo::clave));
    boolean precios = vePrecios();

    BigDecimal subtotal = BigDecimal.ZERO;
    BigDecimal iva = BigDecimal.ZERO;
    BigDecimal pedido = BigDecimal.ZERO;
    BigDecimal llegado = BigDecimal.ZERO;
    List<RenglonPedido> lista = new ArrayList<>();
    for (FilaRenglon l : renglones) {
      MaterialInfo m = materiales.get(l.materialId());
      BigDecimal importe =
          l.cantidadCaptura().multiply(l.precio()).setScale(2, RoundingMode.HALF_UP);
      subtotal = subtotal.add(importe);
      iva = iva.add(importe.multiply(l.tasaIva()).setScale(2, RoundingMode.HALF_UP));
      BigDecimal r = recibido.getOrDefault(l.id(), BigDecimal.ZERO);
      pedido = pedido.add(l.cantidad());
      llegado = llegado.add(r.min(l.cantidad()).max(BigDecimal.ZERO));
      List<Cobertura> coberturas =
          ligas.stream()
              .filter(g -> g.pedidoLineaId() == l.id())
              .map(
                  g -> {
                    RenglonConRequisicion q = renglonesRequisicion.get(g.requisicionLineaId());
                    return new Cobertura(
                        g.requisicionLineaId(),
                        q == null ? 0 : q.requisicionId(),
                        q == null ? "" : q.folio(),
                        q == null ? "" : lotes.getOrDefault(q.unidadObraId(), ""),
                        g.cantidad(),
                        g.recibido());
                  })
              .toList();
      lista.add(
          new RenglonPedido(
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
              precios ? l.precio() : null,
              l.tasaIva(),
              precios ? importe : null,
              r,
              l.cantidad().subtract(r).max(BigDecimal.ZERO),
              l.observaciones(),
              coberturas));
    }
    Map<Long, RecepcionDePedido> recepciones = new LinkedHashMap<>();
    for (DocumentoLigado d : inventario.recepcionesDe(ids)) {
      recepciones.putIfAbsent(
          d.movimientoId(),
          new RecepcionDePedido(d.movimientoId(), d.folio(), d.estado(), d.fecha()));
    }
    Set<Long> idsUsuarios = new HashSet<>();
    idsUsuarios.add(p.usuarioCaptura());
    if (p.usuarioEnvia() != null) {
      idsUsuarios.add(p.usuarioEnvia());
    }
    Map<Long, String> nombres =
        usuarios.buscar(idsUsuarios).stream()
            .collect(Collectors.toMap(UsuarioResumen::id, UsuarioResumen::nombre));
    String proveedor = catalogo.proveedor(p.proveedorId()).map(ProveedorInfo::nombre).orElse("");
    String almacen = inventario.almacen(p.almacenId()).map(AlmacenInfo::nombre).orElse("");
    BigDecimal avance =
        pedido.signum() == 0
            ? BigDecimal.ZERO.setScale(2)
            : llegado.multiply(BigDecimal.valueOf(100)).divide(pedido, 2, RoundingMode.HALF_UP);
    boolean compra = Autorizacion.tiene(Permisos.PEDIDO_CREAR);
    boolean almacenista = Autorizacion.tiene(Permisos.MOVIMIENTO_CAPTURAR);
    boolean borrador = p.estado().equals("borrador");
    boolean enviado = p.estado().equals("enviado");
    return new Pedido(
        p.id(),
        p.folio(),
        p.estado(),
        p.proveedorId(),
        proveedor,
        p.almacenId(),
        almacen,
        p.fecha(),
        p.fechaEntrega(),
        p.observaciones(),
        p.motivoCierre(),
        nombres.getOrDefault(p.usuarioCaptura(), ""),
        p.usuarioEnvia() == null ? null : nombres.get(p.usuarioEnvia()),
        p.enviadoEn(),
        p.cerradoEn(),
        p.creado(),
        precios ? subtotal : null,
        precios ? iva : null,
        precios ? subtotal.add(iva) : null,
        avance,
        p.version(),
        lista,
        List.copyOf(recepciones.values()),
        new AccionesPedido(
            compra && borrador,
            compra && borrador,
            almacenista && enviado,
            compra && enviado,
            compra && (borrador || (enviado && recepciones.isEmpty()))));
  }

  // ------------------------------------------------------------------------------ Apoyo

  private FilaPedido bloquear(long id) {
    return repositorio.bloquear(empresa(), id).orElseThrow(ServicioPedidos::noEncontrado);
  }

  private Map<Long, MaterialInfo> materiales(Collection<Long> ids) {
    return catalogo.materiales(ids).stream()
        .collect(Collectors.toMap(MaterialInfo::id, Function.identity()));
  }

  private Map<Long, String> lotes(java.util.stream.Stream<Long> ids) {
    return estructura.unidades(ids.collect(Collectors.toSet())).stream()
        .collect(Collectors.toMap(UnidadInfo::id, UnidadInfo::clave));
  }

  private static boolean vePrecios() {
    return Autorizacion.tieneAlguno(
        Permisos.PRECIO_VER, Permisos.COSTO_VER_TODO, Permisos.PEDIDO_CREAR);
  }

  private static String exigirMotivo(String motivo) {
    String texto = Textos.limpio(motivo);
    if (texto == null) {
      throw ErrorNegocio.invalido("FALTA_MOTIVO", "Escribe el motivo");
    }
    return texto;
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

  static ErrorNegocio noEncontrado() {
    return ErrorNegocio.noEncontrado("PEDIDO_NO_ENCONTRADO", "No existe el pedido");
  }
}

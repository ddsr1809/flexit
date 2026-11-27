package mx.constructora.inventario;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.inventario.aplicacion.ServicioAplicacion;
import mx.constructora.inventario.aplicacion.ServicioMovimientos;
import mx.constructora.inventario.aplicacion.ServicioMovimientos.DatosMovimiento;
import mx.constructora.inventario.aplicacion.ServicioMovimientos.LineaCaptura;
import mx.constructora.inventario.aplicacion.ServicioMovimientos.Movimiento;
import mx.constructora.inventario.aplicacion.ServicioMovimientos.Vinculo;
import mx.constructora.inventario.dominio.Reparto;
import mx.constructora.inventario.dominio.Valuacion;
import mx.constructora.inventario.infraestructura.RepositorioAlmacenes;
import mx.constructora.inventario.infraestructura.RepositorioAlmacenes.FilaAlmacen;
import mx.constructora.inventario.infraestructura.RepositorioReservas;
import mx.constructora.inventario.infraestructura.RepositorioReservas.FilaReserva;
import mx.constructora.inventario.infraestructura.RepositorioSaldos;
import mx.constructora.inventario.infraestructura.RepositorioSaldos.FilaSaldo;
import mx.constructora.inventario.infraestructura.RepositorioVinculos;
import mx.constructora.inventario.infraestructura.RepositorioVinculos.FilaDocumento;
import mx.constructora.inventario.infraestructura.RepositorioVinculos.FilaRecibido;
import mx.constructora.inventario.infraestructura.RepositorioVinculos.FilaSurtido;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que el abastecimiento (H6) usa del inventario sin tocar sus tablas: existencia disponible,
 * reservas para requisiciones, el vale que surte una requisición, la recepción contra pedido y
 * cuánto se ha surtido o recibido de cada renglón.
 *
 * <p>No revisa permisos por sí mismo: quien llama ya los exigió, y crear o aplicar documentos pasa
 * por los servicios del inventario, que piden {@code movimiento:capturar}. Las operaciones que
 * escriben exigen una transacción abierta ({@code MANDATORY}): las reservas y los documentos se
 * hacen en la misma transacción que la requisición o el pedido.
 */
@Service
public class InventarioApi {

  /** Decimales con que se guardan las cantidades en unidad de control. */
  public static final int DECIMALES_CANTIDAD = Valuacion.DECIMALES_CANTIDAD;

  /** Almacén con su frente. */
  public record AlmacenInfo(long id, long frenteId, String clave, String nombre, boolean activo) {}

  /** Existencia de un material en un almacén y cuánto está libre para un vale o una reserva. */
  public record Disponibilidad(
      long almacenId,
      long materialId,
      BigDecimal existencia,
      BigDecimal reservado,
      BigDecimal prestado,
      BigDecimal disponible) {}

  /**
   * Lo surtido de un renglón de requisición (neto de devoluciones) y lo que va en vales abiertos.
   */
  public record Surtido(BigDecimal surtido, BigDecimal enProceso) {}

  /** Un documento del almacén ligado a un renglón de requisición o de pedido. */
  public record DocumentoLigado(
      long lineaId,
      long movimientoId,
      String folio,
      String tipo,
      String estado,
      LocalDate fecha,
      BigDecimal cantidad) {}

  /** Renglón de un vale que surte un renglón de requisición (en unidad de control, a un lote). */
  public record LineaVale(
      long requisicionLineaId,
      int renglon,
      long materialId,
      BigDecimal cantidad,
      long contratoId,
      long unidadObraId,
      long paqueteId,
      int clasificacionId) {}

  /** Renglón recibido de un pedido: la cantidad y el precio van en la unidad capturada. */
  public record LineaRecepcion(
      long pedidoLineaId,
      long materialId,
      BigDecimal cantidad,
      long unidadId,
      BigDecimal precio,
      BigDecimal tasaIva) {}

  /** Documento creado: id y folio. */
  public record Documento(long id, String folio) {}

  private final RepositorioAlmacenes almacenes;
  private final RepositorioSaldos saldos;
  private final RepositorioReservas reservas;
  private final RepositorioVinculos vinculos;
  private final ServicioMovimientos movimientos;
  private final ServicioAplicacion aplicacion;

  InventarioApi(
      RepositorioAlmacenes almacenes,
      RepositorioSaldos saldos,
      RepositorioReservas reservas,
      RepositorioVinculos vinculos,
      ServicioMovimientos movimientos,
      ServicioAplicacion aplicacion) {
    this.almacenes = almacenes;
    this.saldos = saldos;
    this.reservas = reservas;
    this.vinculos = vinculos;
    this.movimientos = movimientos;
    this.aplicacion = aplicacion;
  }

  // ------------------------------------------------------------------------------ Almacenes

  @Transactional(readOnly = true)
  public Optional<AlmacenInfo> almacen(long id) {
    return almacenes.almacen(empresa(), id).map(InventarioApi::info);
  }

  /** El almacén del frente (uno por frente, decisión de H4). */
  @Transactional(readOnly = true)
  public Optional<AlmacenInfo> almacenDeFrente(long frenteId) {
    return almacenes.almacenes(empresa()).stream()
        .filter(a -> a.frenteId() == frenteId)
        .findFirst()
        .map(InventarioApi::info);
  }

  @Transactional(readOnly = true)
  public List<AlmacenInfo> almacenes() {
    return almacenes.almacenes(empresa()).stream().map(InventarioApi::info).toList();
  }

  // ------------------------------------------------------------------------------ Existencia

  /** Disponibilidad de esos materiales en el almacén, sin bloquear (para mostrar). */
  @Transactional(readOnly = true)
  public Map<Long, Disponibilidad> disponibilidad(long almacen, Collection<Long> materiales) {
    Map<Long, Disponibilidad> resultado = new HashMap<>();
    if (materiales.isEmpty()) {
      return resultado;
    }
    for (FilaSaldo s : saldos.saldos(empresa(), almacen, materiales, false)) {
      resultado.put(s.materialId(), disponibilidad(s));
    }
    return resultado;
  }

  /**
   * Aparta para el renglón de requisición hasta {@code hasta} de lo disponible y devuelve lo que
   * apartó (puede ser cero). Bloquea el saldo: quien reserva varios materiales debe llamarlo en
   * orden de material.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public BigDecimal reservar(long almacen, long material, long requisicionLinea, BigDecimal hasta) {
    if (hasta.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    FilaSaldo saldo = saldos.bloquear(empresa(), almacen, material);
    BigDecimal apartar = hasta.min(saldo.disponible());
    if (apartar.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    reservas.sumar(empresa(), almacen, material, requisicionLinea, apartar);
    return apartar;
  }

  /** Libera lo reservado para esos renglones de requisición (cerrar o cancelar). */
  @Transactional(propagation = Propagation.MANDATORY)
  public void liberar(Collection<Long> requisicionLineas) {
    List<FilaReserva> filas = reservas.deRenglones(empresa(), requisicionLineas, false);
    // Saldo antes que reserva, en orden de (almacén, material), como al aplicar un vale.
    TreeSet<String> bloqueados = new TreeSet<>();
    filas.stream()
        .filter(r -> r.cantidad().signum() > 0)
        .sorted(
            java.util.Comparator.comparingLong(FilaReserva::almacenId)
                .thenComparingLong(FilaReserva::materialId))
        .forEach(
            r -> {
              if (bloqueados.add(r.almacenId() + "/" + r.materialId())) {
                saldos.bloquear(empresa(), r.almacenId(), r.materialId());
              }
            });
    for (FilaReserva r : reservas.deRenglones(empresa(), requisicionLineas, true)) {
      if (r.cantidad().signum() > 0) {
        reservas.fijar(empresa(), r.id(), BigDecimal.ZERO);
      }
    }
  }

  /** Lo reservado hoy para cada renglón de requisición (los que no tienen reserva no aparecen). */
  @Transactional(readOnly = true)
  public Map<Long, BigDecimal> reservado(Collection<Long> requisicionLineas) {
    Map<Long, BigDecimal> resultado = new HashMap<>();
    for (FilaReserva r : reservas.deRenglones(empresa(), requisicionLineas, false)) {
      resultado.put(r.requisicionLineaId(), r.cantidad());
    }
    return resultado;
  }

  // ------------------------------------------------------------------------------ Surtido y
  // recibido

  @Transactional(readOnly = true)
  public Map<Long, Surtido> surtido(Collection<Long> requisicionLineas) {
    Map<Long, Surtido> resultado = new HashMap<>();
    for (FilaSurtido f : vinculos.surtido(empresa(), requisicionLineas)) {
      resultado.put(f.lineaId(), new Surtido(f.surtido(), f.enProceso()));
    }
    return resultado;
  }

  @Transactional(readOnly = true)
  public Map<Long, BigDecimal> recibido(Collection<Long> pedidoLineas) {
    Map<Long, BigDecimal> resultado = new HashMap<>();
    for (FilaRecibido f : vinculos.recibido(empresa(), pedidoLineas)) {
      resultado.put(f.lineaId(), f.recibido());
    }
    return resultado;
  }

  @Transactional(readOnly = true)
  public List<DocumentoLigado> valesDe(Collection<Long> requisicionLineas) {
    return vinculos.vales(empresa(), requisicionLineas).stream()
        .map(InventarioApi::documento)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<DocumentoLigado> recepcionesDe(Collection<Long> pedidoLineas) {
    return vinculos.recepciones(empresa(), pedidoLineas).stream()
        .map(InventarioApi::documento)
        .toList();
  }

  /**
   * Proveedor de la última recepción aplicada de cada material (los que no tienen, no aparecen).
   */
  @Transactional(readOnly = true)
  public Map<Long, Long> ultimosProveedores(Collection<Long> materiales) {
    Map<Long, Long> resultado = new HashMap<>();
    vinculos
        .ultimosProveedores(empresa(), materiales)
        .forEach(f -> resultado.put(f.materialId(), f.proveedorId()));
    return resultado;
  }

  // ------------------------------------------------------------------------------ Documentos

  /**
   * Crea el vale que surte renglones de requisición y lo deja enviado a autorizar (el residente del
   * contrato o coordinación lo autoriza, y el almacén lo aplica: ahí consume la reserva).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Documento crearVale(
      long almacen,
      LocalDate fecha,
      Long solicitanteId,
      String observaciones,
      List<LineaVale> lineas) {
    List<LineaCaptura> capturas = new ArrayList<>();
    List<Vinculo> ligas = new ArrayList<>();
    for (LineaVale l : lineas) {
      capturas.add(
          new LineaCaptura(
              l.materialId(),
              l.cantidad(),
              null,
              null,
              null,
              null,
              null,
              l.contratoId(),
              List.of(l.unidadObraId()),
              l.paqueteId(),
              l.clasificacionId(),
              null));
      ligas.add(new Vinculo(l.renglon(), l.requisicionLineaId(), null));
    }
    long id =
        movimientos.crearLigado(
            new DatosMovimiento(
                "salida",
                fecha,
                almacen,
                null,
                null,
                null,
                null,
                solicitanteId,
                null,
                null,
                null,
                observaciones,
                capturas,
                true),
            ligas);
    return new Documento(id, movimientos.movimiento(id).folio());
  }

  /** Crea y aplica la recepción de un pedido: la existencia entra en el mismo paso. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Documento recibir(
      long almacen,
      long proveedor,
      LocalDate fecha,
      String folioFactura,
      BigDecimal flete,
      String observaciones,
      List<LineaRecepcion> lineas) {
    List<LineaCaptura> capturas = new ArrayList<>();
    List<Vinculo> ligas = new ArrayList<>();
    int renglon = 0;
    for (LineaRecepcion l : lineas) {
      capturas.add(
          new LineaCaptura(
              l.materialId(),
              l.cantidad(),
              l.unidadId(),
              l.precio(),
              null,
              l.tasaIva(),
              null,
              null,
              null,
              null,
              null,
              null));
      ligas.add(new Vinculo(++renglon, null, l.pedidoLineaId()));
    }
    long id =
        movimientos.crearLigado(
            new DatosMovimiento(
                "entrada",
                fecha,
                almacen,
                null,
                proveedor,
                folioFactura,
                flete,
                null,
                null,
                null,
                null,
                observaciones,
                capturas,
                false),
            ligas);
    Movimiento aplicado = aplicacion.aplicar(id);
    return new Documento(id, aplicado.folio());
  }

  // ------------------------------------------------------------------------------ Apoyo

  /** Reparte {@code total} en {@code partes} que suman exacto (el último lleva el residuo). */
  public static List<BigDecimal> repartir(BigDecimal total, int partes, int decimales) {
    return Reparto.repartir(total, partes, decimales);
  }

  /** Prorratea {@code total} en proporción a {@code pesos}, sumando exacto. */
  public static List<BigDecimal> prorratear(
      BigDecimal total, List<BigDecimal> pesos, int decimales) {
    return Reparto.prorratear(total, pesos, decimales);
  }

  private static Disponibilidad disponibilidad(FilaSaldo s) {
    return new Disponibilidad(
        s.almacenId(),
        s.materialId(),
        s.existencia(),
        s.reservado(),
        s.prestado(),
        s.disponible().max(BigDecimal.ZERO));
  }

  private static DocumentoLigado documento(FilaDocumento f) {
    return new DocumentoLigado(
        f.lineaId(), f.movimientoId(), f.folio(), f.tipo(), f.estado(), f.fecha(), f.cantidad());
  }

  private static AlmacenInfo info(FilaAlmacen a) {
    return new AlmacenInfo(a.id(), a.frenteId(), a.clave(), a.nombre(), a.activo());
  }

  private static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }
}

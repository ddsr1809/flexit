package mx.constructora.inventario.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static mx.constructora.inventario.aplicacion.Sesion.ADMINISTRAR;
import static mx.constructora.inventario.aplicacion.Sesion.CONSULTAR;
import static mx.constructora.inventario.aplicacion.Sesion.empresa;
import static mx.constructora.inventario.aplicacion.Sesion.hoy;
import static mx.constructora.inventario.aplicacion.Sesion.usuario;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import mx.constructora.catalogo.CatalogoApi.MaterialInfo;
import mx.constructora.compartido.Autorizacion;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Folios;
import mx.constructora.compartido.LibroExcel;
import mx.constructora.compartido.LibroExcel.Columna;
import mx.constructora.compartido.LibroExcel.Formato;
import mx.constructora.compartido.Permisos;
import mx.constructora.inventario.AvisosDeCierre;
import mx.constructora.inventario.infraestructura.RepositorioPeriodos;
import mx.constructora.inventario.infraestructura.RepositorioPeriodos.FilaCierre;
import mx.constructora.inventario.infraestructura.RepositorioPeriodos.FilaMovimientosMes;
import mx.constructora.inventario.infraestructura.RepositorioPeriodos.FilaPendiente;
import mx.constructora.inventario.infraestructura.RepositorioPeriodos.FilaPeriodo;
import mx.constructora.inventario.infraestructura.RepositorioPeriodos.Pendientes;
import mx.constructora.inventario.infraestructura.RepositorioPeriodos.ValorInicial;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cierre de mes. Un mes se cierra cuando ya terminó, los anteriores están cerrados y no le quedan
 * documentos sin aplicar o cancelar, ni conteos o aperturas abiertos con fecha hasta su fin (H9:
 * después del cierre ya no se podrían cerrar ni aplicar). Cerrado, la base no acepta movimientos
 * con fecha en él y se guarda la foto del inventario (existencia y valor por almacén y material).
 * No se reabre desde la aplicación.
 *
 * <p>Antes de cerrar se revisa el mes ({@link #revision}): lo que impide cerrar y los avisos que no
 * lo impiden (préstamos vencidos, recepciones sin factura y lo que avisan otros módulos por {@link
 * AvisosDeCierre}). Cerrado, el acta del cierre se descarga en Excel ({@link #acta}).
 */
@Service
public class ServicioPeriodos {

  /** Valor del inventario de un almacén al cierre. */
  public record ValorAlmacen(
      @Schema(requiredMode = REQUIRED) long almacenId,
      @Schema(requiredMode = REQUIRED) String almacen,
      @Schema(description = "Solo para quien ve costos") BigDecimal valor,
      @Schema(requiredMode = REQUIRED) int materiales) {}

  /** Mes cerrado. */
  public record Periodo(
      @Schema(requiredMode = REQUIRED) int anio,
      @Schema(requiredMode = REQUIRED) int mes,
      @Schema(requiredMode = REQUIRED) String cerro,
      @Schema(requiredMode = REQUIRED) OffsetDateTime cerradoEn,
      @Schema(requiredMode = REQUIRED) List<ValorAlmacen> valor) {}

  /** Meses cerrados y el siguiente por cerrar. */
  public record Periodos(
      @Schema(requiredMode = REQUIRED) List<Periodo> cerrados,
      @Schema(description = "Siguiente mes por cerrar (AAAA-MM); vacío si no hay movimientos")
          String siguiente,
      @Schema(requiredMode = REQUIRED, description = "Documentos sin aplicar en el siguiente mes")
          int pendientesSiguiente,
      @Schema(requiredMode = REQUIRED, description = "El siguiente mes ya terminó")
          boolean siguienteTerminado) {}

  /** Un documento, conteo, apertura, préstamo o requisición que sale en la revisión. */
  public record DocumentoRevision(
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(
              requiredMode = REQUIRED,
              description = "Tipo de movimiento, o conteo, apertura, prestamo o requisicion")
          String tipo,
      String estado,
      @Schema(description = "Fecha del documento; del préstamo, la fecha en que debía regresar")
          LocalDate fecha,
      String almacen) {}

  /** Algo que impide cerrar el mes, o un aviso que no lo impide. */
  public record PuntoRevision(
      @Schema(requiredMode = REQUIRED) String codigo,
      @Schema(requiredMode = REQUIRED) String mensaje,
      @Schema(requiredMode = REQUIRED) int cuantos,
      @Schema(requiredMode = REQUIRED, description = "Los primeros 50 (20 de otros módulos)")
          List<DocumentoRevision> documentos) {}

  /** Lo que hay que saber antes de cerrar un mes. */
  public record RevisionCierre(
      @Schema(requiredMode = REQUIRED, description = "AAAA-MM") String mes,
      @Schema(requiredMode = REQUIRED) boolean cerrado,
      @Schema(requiredMode = REQUIRED, description = "Sin bloqueos: se puede cerrar ya")
          boolean sePuedeCerrar,
      @Schema(requiredMode = REQUIRED) List<PuntoRevision> bloqueos,
      @Schema(requiredMode = REQUIRED) List<PuntoRevision> avisos) {}

  private static final DateTimeFormatter FECHA_HORA =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private static final Map<String, String> TIPOS =
      Map.ofEntries(
          Map.entry("entrada", "Recepción"),
          Map.entry("salida", "Vale de salida"),
          Map.entry("devolucion", "Devolución"),
          Map.entry("devolucion_proveedor", "Devolución a proveedor"),
          Map.entry("traspaso", "Traspaso"),
          Map.entry("reasignacion", "Reasignación"),
          Map.entry("merma", "Merma"),
          Map.entry("ajuste_entrada", "Ajuste de entrada (conteo)"),
          Map.entry("ajuste_salida", "Ajuste de salida (conteo)"),
          Map.entry("ajuste_costo", "Ajuste de costo"),
          Map.entry("apertura", "Saldo de apertura"));

  private final RepositorioPeriodos repositorio;
  private final Nombres nombres;
  private final List<AvisosDeCierre> otrosAvisos;

  public ServicioPeriodos(
      RepositorioPeriodos repositorio, Nombres nombres, List<AvisosDeCierre> otrosAvisos) {
    this.repositorio = repositorio;
    this.nombres = nombres;
    this.otrosAvisos = List.copyOf(otrosAvisos);
  }

  @PreAuthorize(CONSULTAR)
  @Transactional(readOnly = true)
  public Periodos periodos() {
    List<FilaPeriodo> cerrados = repositorio.cerrados(empresa());
    Set<Long> usuarioIds = new HashSet<>();
    cerrados.forEach(p -> usuarioIds.add(p.cerradoPor()));
    Map<Long, String> usuarios = nombres.usuarios(usuarioIds);
    Map<Long, String> claves = nombres.almacenes();
    boolean costos = veCostos();
    List<Periodo> lista =
        cerrados.stream()
            .map(
                p ->
                    new Periodo(
                        p.anio(),
                        p.mes(),
                        usuarios.getOrDefault(p.cerradoPor(), ""),
                        p.cerradoEn(),
                        repositorio.valorAlCierre(empresa(), p.anio(), p.mes()).stream()
                            .map(
                                v ->
                                    new ValorAlmacen(
                                        v.almacenId(),
                                        claves.getOrDefault(v.almacenId(), ""),
                                        costos ? v.valor() : null,
                                        v.materiales()))
                            .toList()))
            .toList();
    YearMonth siguiente = siguiente(cerrados);
    if (siguiente == null) {
      return new Periodos(lista, null, 0, false);
    }
    return new Periodos(
        lista,
        siguiente.toString(),
        repositorio.pendientes(empresa(), siguiente.atDay(1), siguiente.atEndOfMonth()),
        siguiente.atEndOfMonth().isBefore(hoy()));
  }

  /** Lo que impide cerrar el mes y los avisos que no lo impiden. */
  @PreAuthorize(CONSULTAR)
  @Transactional(readOnly = true)
  public RevisionCierre revision(int anio, int mes) {
    YearMonth periodo = mesValido(anio, mes);
    boolean cerrado = repositorio.cerrado(empresa(), periodo.atDay(1));
    Map<Long, String> almacenes = nombres.almacenes();
    List<PuntoRevision> bloqueos = cerrado ? List.of() : bloqueos(periodo, almacenes);
    List<PuntoRevision> avisos = cerrado ? List.of() : avisos(periodo, almacenes);
    return new RevisionCierre(
        periodo.toString(), cerrado, !cerrado && bloqueos.isEmpty(), bloqueos, avisos);
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public Periodos cerrar(int anio, int mes) {
    YearMonth periodo = mesValido(anio, mes);
    if (repositorio.cerrado(empresa(), periodo.atDay(1))) {
      throw ErrorNegocio.conflicto("MES_YA_CERRADO", "El mes " + periodo + " ya está cerrado");
    }
    List<PuntoRevision> bloqueos = bloqueos(periodo, nombres.almacenes());
    if (!bloqueos.isEmpty()) {
      PuntoRevision primero = bloqueos.getFirst();
      throw ErrorNegocio.conflicto(primero.codigo(), primero.mensaje());
    }
    try {
      repositorio.cerrar(empresa(), anio, mes, periodo.atEndOfMonth(), usuario());
    } catch (DuplicateKeyException e) {
      throw ErrorNegocio.conflicto("MES_YA_CERRADO", "El mes " + periodo + " ya está cerrado");
    } catch (DataIntegrityViolationException e) {
      // Otro usuario dejó algo pendiente entre la revisión y el cierre: lo detuvo la base (V012).
      throw codigoDeLaBase(e)
          .map(c -> ErrorNegocio.conflicto(c, "Algo del mes quedó pendiente: revisa otra vez"))
          .orElseThrow(() -> e);
    }
    return periodos();
  }

  /**
   * El acta del cierre en Excel: el valor de cada almacén (inicial, lo que movió el mes y al
   * cierre), la foto del inventario y los movimientos del mes por tipo. Sin permiso de costos, solo
   * cantidades.
   */
  @PreAuthorize(CONSULTAR)
  @Transactional(readOnly = true)
  public byte[] acta(int anio, int mes) {
    YearMonth periodo = mesValido(anio, mes);
    FilaPeriodo cierre =
        repositorio.cerrados(empresa()).stream()
            .filter(p -> p.anio() == anio && p.mes() == mes)
            .findFirst()
            .orElseThrow(
                () ->
                    ErrorNegocio.noEncontrado(
                        "MES_ABIERTO", "El mes " + periodo + " no está cerrado: no tiene acta"));
    boolean costos = veCostos();
    Map<Long, String> almacenes = nombres.almacenes();
    List<FilaCierre> foto = repositorio.fotoAlCierre(empresa(), anio, mes);
    List<FilaMovimientosMes> movimientos =
        repositorio.movimientosDelMes(empresa(), periodo.atDay(1), periodo.atEndOfMonth());
    Map<Long, BigDecimal> inicial = new HashMap<>();
    for (ValorInicial v : repositorio.valorInicial(empresa(), periodo.atDay(1))) {
      inicial.put(v.almacenId(), v.valor());
    }
    Map<Long, MaterialInfo> materiales =
        nombres.materiales(foto.stream().map(FilaCierre::materialId).toList());
    String cerro =
        nombres.usuarios(List.of(cierre.cerradoPor())).getOrDefault(cierre.cerradoPor(), "");

    LibroExcel libro = new LibroExcel();
    libro
        .hoja("Cierre", List.of(Columna.texto("Dato", 22), Columna.texto("Valor", 40)))
        .fila("Mes", periodo.toString())
        .fila("Cerró", cerro)
        .fila(
            "Cerrado el", FECHA_HORA.format(cierre.cerradoEn().atZoneSameInstant(Folios.ZONA)))
        .fila("Costos", costos ? "Ponderados, sin IVA" : "Sin permiso para ver costos");

    // Valor por almacén: inicial + movimientos del mes = al cierre (la diferencia debe ser 0).
    Set<Long> ids = new TreeSet<>();
    foto.forEach(f -> ids.add(f.almacenId()));
    movimientos.forEach(m -> ids.add(m.almacenId()));
    ids.addAll(inicial.keySet());
    List<Columna> columnasResumen = new ArrayList<>();
    columnasResumen.add(Columna.texto("Almacén", 14));
    columnasResumen.add(new Columna("Materiales", Formato.ENTERO, 12));
    if (costos) {
      columnasResumen.add(Columna.moneda("Valor inicial"));
      columnasResumen.add(Columna.moneda("Movimientos del mes"));
      columnasResumen.add(Columna.moneda("Valor al cierre"));
      columnasResumen.add(Columna.moneda("Diferencia"));
    }
    var resumen = libro.hoja("Valor por almacén", columnasResumen);
    for (long id : ids) {
      int cuantos = (int) foto.stream().filter(f -> f.almacenId() == id).count();
      if (!costos) {
        resumen.fila(almacenes.getOrDefault(id, ""), cuantos);
        continue;
      }
      BigDecimal antes = inicial.getOrDefault(id, BigDecimal.ZERO);
      BigDecimal delMes =
          movimientos.stream()
              .filter(m -> m.almacenId() == id)
              .map(FilaMovimientosMes::importe)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal alCierre =
          foto.stream()
              .filter(f -> f.almacenId() == id)
              .map(FilaCierre::valor)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      resumen.fila(
          almacenes.getOrDefault(id, ""),
          cuantos,
          antes,
          delMes,
          alCierre,
          alCierre.subtract(antes.add(delMes)));
    }

    List<Columna> columnasFoto = new ArrayList<>();
    columnasFoto.add(Columna.texto("Almacén", 12));
    columnasFoto.add(Columna.texto("Clave", 14));
    columnasFoto.add(Columna.texto("Material", 44));
    columnasFoto.add(Columna.texto("Unidad", 10));
    columnasFoto.add(Columna.cantidad("Existencia"));
    if (costos) {
      columnasFoto.add(new Columna("Costo promedio", Formato.MONEDA, 15));
      columnasFoto.add(Columna.moneda("Valor"));
    }
    var hojaFoto = libro.hoja("Inventario al cierre", columnasFoto);
    for (FilaCierre f : foto) {
      MaterialInfo m = materiales.get(f.materialId());
      String almacen = almacenes.getOrDefault(f.almacenId(), "");
      String clave = m == null ? "" : m.clave();
      String material = m == null ? "" : m.nombre();
      String unidad = m == null ? "" : m.unidadControl();
      if (costos) {
        BigDecimal costo =
            f.existencia().signum() == 0
                ? null
                : f.valor().divide(f.existencia(), 6, RoundingMode.HALF_UP);
        hojaFoto.fila(almacen, clave, material, unidad, f.existencia(), costo, f.valor());
      } else {
        hojaFoto.fila(almacen, clave, material, unidad, f.existencia());
      }
    }

    List<Columna> columnasMov = new ArrayList<>();
    columnasMov.add(Columna.texto("Almacén", 12));
    columnasMov.add(Columna.texto("Tipo", 28));
    columnasMov.add(new Columna("Documentos", Formato.ENTERO, 12));
    if (costos) {
      columnasMov.add(Columna.moneda("Importe (+ entra, − sale)"));
    }
    var hojaMov = libro.hoja("Movimientos del mes", columnasMov);
    for (FilaMovimientosMes m : movimientos) {
      String almacen = almacenes.getOrDefault(m.almacenId(), "");
      String tipo = TIPOS.getOrDefault(m.tipo(), m.tipo());
      if (costos) {
        hojaMov.fila(almacen, tipo, m.documentos(), m.importe());
      } else {
        hojaMov.fila(almacen, tipo, m.documentos());
      }
    }
    return libro.bytes();
  }

  // ------------------------------------------------------------------------------ Revisión

  private List<PuntoRevision> bloqueos(YearMonth periodo, Map<Long, String> almacenes) {
    List<PuntoRevision> bloqueos = new ArrayList<>();
    LocalDate inicio = periodo.atDay(1);
    LocalDate fin = periodo.atEndOfMonth();
    if (!fin.isBefore(hoy())) {
      bloqueos.add(punto("MES_EN_CURSO", "El mes todavía no termina", 0, List.of(), almacenes));
    }
    YearMonth siguiente = siguiente(repositorio.cerrados(empresa()));
    if (siguiente != null && periodo.isAfter(siguiente)) {
      bloqueos.add(
          punto(
              "MES_ANTERIOR_ABIERTO",
              "Primero cierra " + siguiente + ": los meses se cierran en orden",
              0,
              List.of(),
              almacenes));
    }
    Pendientes documentos = repositorio.documentosSinAplicar(empresa(), inicio, fin);
    if (documentos.total() > 0) {
      bloqueos.add(
          punto(
              "MES_CON_PENDIENTES",
              "Hay "
                  + documentos.total()
                  + " documentos de "
                  + periodo
                  + " sin aplicar ni cancelar",
              documentos,
              almacenes));
    }
    Pendientes aperturas = repositorio.aperturasAbiertas(empresa(), inicio, fin);
    if (aperturas.total() > 0) {
      bloqueos.add(
          punto(
              "MES_CON_APERTURA_ABIERTA",
              plural(aperturas.total(), "apertura", "aperturas")
                  + " con fecha hasta el "
                  + fin
                  + " sin aplicar: fírmala o cancélala; con el mes cerrado ya no se podría"
                  + " aplicar",
              aperturas,
              almacenes));
    }
    Pendientes conteos = repositorio.conteosAbiertos(empresa(), inicio, fin);
    if (conteos.total() > 0) {
      bloqueos.add(
          punto(
              "MES_CON_CONTEO_ABIERTO",
              plural(conteos.total(), "conteo físico abierto", "conteos físicos abiertos")
                  + " con fecha hasta el "
                  + fin
                  + ": ciérralo o cancélalo; con el mes cerrado ya no se podría cerrar",
              conteos,
              almacenes));
    }
    return bloqueos;
  }

  private List<PuntoRevision> avisos(YearMonth periodo, Map<Long, String> almacenes) {
    List<PuntoRevision> avisos = new ArrayList<>();
    LocalDate inicio = periodo.atDay(1);
    LocalDate fin = periodo.atEndOfMonth();
    Pendientes prestamos = repositorio.prestamosVencidos(empresa(), inicio, fin);
    if (prestamos.total() > 0) {
      avisos.add(
          punto(
              "PRESTAMOS_VENCIDOS",
              plural(prestamos.total(), "préstamo debía regresar", "préstamos debían regresar")
                  + " a más tardar el "
                  + fin
                  + " y sigue abierto: lo prestado sigue apartado",
              prestamos,
              almacenes));
    }
    Pendientes sinFactura = repositorio.recepcionesSinFactura(empresa(), inicio, fin);
    if (sinFactura.total() > 0) {
      avisos.add(
          punto(
              "RECEPCIONES_SIN_FACTURA",
              plural(
                      sinFactura.total(),
                      "recepción del mes no tiene",
                      "recepciones del mes no tienen")
                  + " folio de factura: si la factura trae otro precio, el ajuste de costo entra"
                  + " con fecha del mes siguiente",
              sinFactura,
              almacenes));
    }
    for (AvisosDeCierre otro : otrosAvisos) {
      for (AvisosDeCierre.Aviso a : otro.avisos(inicio, fin)) {
        avisos.add(
            new PuntoRevision(
                a.codigo(),
                a.mensaje(),
                a.cuantos(),
                a.folios().stream()
                    .map(f -> new DocumentoRevision(f, "requisicion", null, null, null))
                    .toList()));
      }
    }
    return avisos;
  }

  private static PuntoRevision punto(
      String codigo, String mensaje, Pendientes pendientes, Map<Long, String> almacenes) {
    return punto(codigo, mensaje, pendientes.total(), pendientes.primeros(), almacenes);
  }

  private static PuntoRevision punto(
      String codigo,
      String mensaje,
      int cuantos,
      List<FilaPendiente> filas,
      Map<Long, String> almacenes) {
    return new PuntoRevision(
        codigo,
        mensaje,
        cuantos,
        filas.stream()
            .map(
                f ->
                    new DocumentoRevision(
                        f.folio(),
                        f.tipo(),
                        f.estado(),
                        f.fecha(),
                        almacenes.getOrDefault(f.almacenId(), "")))
            .toList());
  }

  private static String plural(int n, String uno, String varios) {
    return n == 1 ? "1 " + uno : n + " " + varios;
  }

  /** El código que puso el trigger de la base al rechazar el cierre (V012), si es uno de esos. */
  static Optional<String> codigoDeLaBase(DataIntegrityViolationException e) {
    String mensaje = String.valueOf(e.getMostSpecificCause().getMessage());
    for (String codigo :
        List.of("MES_CON_PENDIENTES", "MES_CON_CONTEO_ABIERTO", "MES_CON_APERTURA_ABIERTA")) {
      if (mensaje.contains(codigo)) {
        return Optional.of(codigo);
      }
    }
    return Optional.empty();
  }

  private static YearMonth mesValido(int anio, int mes) {
    if (mes < 1 || mes > 12 || anio < 2020 || anio > 2100) {
      throw ErrorNegocio.invalido("MES_INVALIDO", "Mes inválido");
    }
    return YearMonth.of(anio, mes);
  }

  private static boolean veCostos() {
    return Autorizacion.tieneAlguno(Permisos.PRECIO_VER, Permisos.COSTO_VER_TODO);
  }

  /** El mes más antiguo sin cerrar desde el primer movimiento; {@code null} sin movimientos. */
  private YearMonth siguiente(List<FilaPeriodo> cerrados) {
    if (!cerrados.isEmpty()) {
      FilaPeriodo ultimo = cerrados.getFirst();
      return YearMonth.of(ultimo.anio(), ultimo.mes()).plusMonths(1);
    }
    LocalDate primero = repositorio.primerMovimiento(empresa());
    return primero == null ? null : YearMonth.from(primero);
  }
}

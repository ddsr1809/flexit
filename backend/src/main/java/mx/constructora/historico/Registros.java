package mx.constructora.historico;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Lo que la migración (módulo importacion) escribe en el histórico: un record por tabla, sin Spring
 * ni base, para que el motor de reglas lo arme y lo pruebe en memoria. Los ids son de la empresa en
 * curso; la base revisa con FK compuestas que lo sean.
 */
public final class Registros {

  private Registros() {}

  /** Calidad con la que entró el renglón. */
  public enum Calidad {
    VALIDA("valida"),
    CORREGIDA("corregida");

    private final String clave;

    Calidad(String clave) {
      this.clave = clave;
    }

    public String clave() {
      return clave;
    }
  }

  /**
   * De dónde salió cada renglón: el consecutivo dentro de la carga ({@code numero}, el mismo de sus
   * incidencias), la hoja y la fila del Excel.
   */
  public record Traza(Calidad calidad, int numero, String hoja, Integer filaExcel) {}

  /** Hoja Entradas: precio e importe sin IVA, netos de descuento. */
  public record Entrada(
      LocalDate fecha,
      String semanaTexto,
      long materialId,
      BigDecimal cantidad,
      BigDecimal precioUnitario,
      BigDecimal descuento,
      BigDecimal importe,
      BigDecimal tasaIva,
      boolean precioEstimado,
      Long proveedorId,
      String proveedorTexto,
      String folio,
      Long recibeId,
      String recibeTexto,
      String observaciones,
      Traza traza) {}

  /**
   * Hoja Salidas. {@code tipo}: salida, devolucion (cantidad negativa) o reasignacion. Sin unidad
   * de obra es la "Sin asignar" del frente; sin frente, una salida para el propio almacén.
   */
  public record Salida(
      LocalDate fecha,
      String tipo,
      String documento,
      String folioExcel,
      long materialId,
      BigDecimal cantidad,
      Long frenteId,
      Long contratoId,
      Long unidadObraId,
      String lotesTexto,
      Long paqueteId,
      String paqueteTexto,
      Integer clasificacionId,
      Long solicitanteId,
      String solicitanteTexto,
      String entregarATexto,
      boolean entregado,
      BigDecimal costoUnitario,
      BigDecimal importe,
      boolean costoEstimado,
      String observaciones,
      Traza traza) {}

  /** Hojas Semanal ({@code origen} semanal) y Faltante solicitud diaria (diaria). */
  public record RequisicionLinea(
      String origen,
      LocalDate fecha,
      Integer semana,
      String semanaTexto,
      String urgencia,
      Long frenteId,
      String prototipoTexto,
      Long contratoId,
      Long unidadObraId,
      String lotesTexto,
      long materialId,
      BigDecimal cantidad,
      String unidadTexto,
      BigDecimal cantidadPedida,
      BigDecimal cantidadCancelada,
      BigDecimal suministrado,
      Long paqueteId,
      String paqueteTexto,
      Long solicitanteId,
      String solicitanteTexto,
      String autorizacion,
      String estado,
      String observaciones,
      Traza traza) {}

  /** Hoja Préstamo Herramientas-Material. */
  public record Prestamo(
      LocalDate fecha,
      Long materialId,
      String articulo,
      BigDecimal cantidad,
      String unidadTexto,
      Long frenteId,
      String lotesTexto,
      Long solicitanteId,
      String solicitanteTexto,
      Long entregarAId,
      String entregarATexto,
      boolean devuelto,
      LocalDate devueltoEn,
      String observaciones,
      Traza traza) {}

  /** IMSS del mes ({@code origen} mes) o SUA ANTERIOR (anterior). {@code mes}: día 1. */
  public record AltaImss(
      LocalDate mes,
      String origen,
      long personaId,
      String estatus,
      Integer dias,
      String frenteTexto,
      Long contratoId,
      String contratoTexto,
      String numeroObra,
      Traza traza) {}

  /** Una marca de asistencia: 1, 0.5 o 0. */
  public record AsistenciaDia(
      LocalDate fecha,
      long personaId,
      Long frenteId,
      String frenteTexto,
      String tipoPago,
      String ocupacion,
      BigDecimal valor,
      boolean dobleAsistencia,
      Traza traza) {}

  /** Hoja BD: frente asignado para el IMSS. */
  public record Asignacion(
      LocalDate mes,
      long personaId,
      Long frenteId,
      String frenteTexto,
      String asignacionTexto,
      Traza traza) {}

  /**
   * Un pago de destajo. {@code parcial}: el sumando de una celda {@code =2500+2500} (1, 2…). {@code
   * trabajadores}: las personas que cobraron, en orden; vacía si la celda no decía quién.
   */
  public record PagoDestajo(
      int anio,
      List<Integer> semanas,
      String semanaTexto,
      long frenteId,
      long contratoId,
      long unidadObraId,
      Long prototipoId,
      Long paqueteId,
      String seccionTexto,
      String conceptoTexto,
      BigDecimal importeConcepto,
      BigDecimal monto,
      int parcial,
      boolean montoCompartido,
      boolean sobreImporte,
      String trabajadorTexto,
      List<Long> trabajadores,
      String celda,
      Traza traza) {

    public PagoDestajo {
      semanas = List.copyOf(semanas);
      trabajadores = List.copyOf(trabajadores);
    }
  }

  /**
   * Hoja Existencias: lo capturado de cada material (Inventario inicial, Material defectuoso y
   * Apartados). La existencia del Excel no se lee (es fórmula): se recalcula con las entradas y
   * salidas del histórico.
   */
  public record SaldoExcel(
      long materialId,
      BigDecimal inventarioInicial,
      LocalDate fechaInicial,
      BigDecimal defectuoso,
      BigDecimal apartados,
      Traza traza) {}

  /** Una entrada con importe, para valuar las salidas del histórico. */
  public record EntradaValorada(
      long materialId, LocalDate fecha, BigDecimal cantidad, BigDecimal importe) {}

  /** Todo lo que escribe una carga, en una transacción. */
  public static final class Lote {
    public final List<Entrada> entradas = new ArrayList<>();
    public final List<Salida> salidas = new ArrayList<>();
    public final List<RequisicionLinea> requisiciones = new ArrayList<>();
    public final List<Prestamo> prestamos = new ArrayList<>();
    public final List<AltaImss> altas = new ArrayList<>();
    public final List<AsistenciaDia> asistencias = new ArrayList<>();
    public final List<Asignacion> asignaciones = new ArrayList<>();
    public final List<PagoDestajo> pagos = new ArrayList<>();
    public final List<SaldoExcel> saldos = new ArrayList<>();

    public int total() {
      return entradas.size()
          + salidas.size()
          + requisiciones.size()
          + prestamos.size()
          + altas.size()
          + asistencias.size()
          + asignaciones.size()
          + pagos.size()
          + saldos.size();
    }
  }
}

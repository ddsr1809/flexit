package mx.constructora.inventario.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * El saldo de apertura de un almacén (H8): por material, lo contado más lo prestado, al costo que
 * se firma. Java puro: qué costo se toma, cuánto vale y la huella de lo que se firma.
 *
 * <p>El costo sale, en este orden (ADR-014):
 *
 * <ol>
 *   <li>el que capturó administración;
 *   <li>el ponderado sin IVA de las entradas del Excel hasta la fecha del corte (el mismo criterio
 *       con que se valuó el histórico, ADR-011);
 *   <li>el precio de referencia del catálogo.
 * </ol>
 *
 * Sin ninguno de los tres, lo que entra no se puede firmar.
 *
 * <p>La huella resume la fecha, cada línea y cada préstamo que se vuelve vivo. Quien firma manda la
 * huella de lo que vio; si algo cambió entre que lo vio y firmó, la firma se rechaza.
 */
public final class SaldoApertura {

  /** De dónde salió el costo de una línea. */
  public enum Origen {
    CAPTURADO("capturado"),
    HISTORICO("historico"),
    REFERENCIA("referencia");

    private final String clave;

    Origen(String clave) {
      this.clave = clave;
    }

    public String clave() {
      return clave;
    }

    public static Origen deClave(String clave) {
      for (Origen o : values()) {
        if (o.clave.equals(clave)) {
          return o;
        }
      }
      throw new IllegalArgumentException(clave);
    }
  }

  /** Un costo sin IVA y de dónde salió. */
  public record Costo(BigDecimal unitario, Origen origen) {
    public Costo {
      unitario = unitario.setScale(Valuacion.DECIMALES_COSTO, RoundingMode.HALF_EVEN);
    }
  }

  /**
   * Un material de la apertura. {@code contado} es nulo mientras no se cuenta; {@code costo} nulo
   * si no hay de dónde sacarlo.
   */
  public record Linea(long materialId, BigDecimal contado, BigDecimal prestado, Costo costo) {

    public Linea {
      prestado = prestado == null ? BigDecimal.ZERO : prestado;
    }

    /** Lo que entra al almacén: lo contado más lo prestado. */
    public BigDecimal cantidad() {
      return (contado == null ? BigDecimal.ZERO : contado)
          .add(prestado)
          .setScale(Valuacion.DECIMALES_CANTIDAD, RoundingMode.UNNECESSARY);
    }

    /** Importe con el mismo redondeo que el kardex; nulo sin costo. */
    public BigDecimal importe() {
      return costo == null ? null : Valuacion.importe(cantidad(), costo.unitario());
    }

    /** Entra algo y no hay costo: no se puede firmar. */
    public boolean faltaCosto() {
      return costo == null && cantidad().signum() > 0;
    }
  }

  /** Un préstamo que se vuelve vivo al aplicar: id del renglón, material, cantidad, responsable. */
  public record PrestamoListo(long id, long materialId, BigDecimal cantidad, long responsableId) {}

  private SaldoApertura() {}

  /** El costo que manda: capturado, histórico o referencia; nulo si no hay ninguno. */
  public static Costo elegir(BigDecimal capturado, BigDecimal historico, BigDecimal referencia) {
    if (capturado != null) {
      return new Costo(capturado, Origen.CAPTURADO);
    }
    if (historico != null && historico.signum() > 0) {
      return new Costo(historico, Origen.HISTORICO);
    }
    if (referencia != null && referencia.signum() > 0) {
      return new Costo(referencia, Origen.REFERENCIA);
    }
    return null;
  }

  /** Valor total de la apertura (las líneas sin costo no entran: no se puede firmar con ellas). */
  public static BigDecimal importe(List<Linea> lineas) {
    return lineas.stream()
        .map(Linea::importe)
        .filter(i -> i != null)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(Valuacion.DECIMALES_IMPORTE, RoundingMode.UNNECESSARY);
  }

  /** Huella SHA-256 de lo que se firma. No depende del orden de las listas. */
  public static String huella(LocalDate fecha, List<Linea> lineas, List<PrestamoListo> prestamos) {
    StringBuilder texto = new StringBuilder("apertura|").append(fecha).append('\n');
    List<Linea> ordenadas = new ArrayList<>(lineas);
    ordenadas.sort(Comparator.comparingLong(Linea::materialId));
    for (Linea l : ordenadas) {
      texto
          .append(l.materialId())
          .append('|')
          .append(numero(l.contado()))
          .append('|')
          .append(numero(l.prestado()))
          .append('|')
          .append(l.costo() == null ? "" : numero(l.costo().unitario()))
          .append('|')
          .append(l.costo() == null ? "" : l.costo().origen().clave())
          .append('\n');
    }
    List<PrestamoListo> listos = new ArrayList<>(prestamos);
    listos.sort(Comparator.comparingLong(PrestamoListo::id));
    for (PrestamoListo p : listos) {
      texto
          .append('p')
          .append(p.id())
          .append('|')
          .append(p.materialId())
          .append('|')
          .append(numero(p.cantidad()))
          .append('|')
          .append(p.responsableId())
          .append('\n');
    }
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(sha.digest(texto.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String numero(BigDecimal valor) {
    return valor == null ? "" : valor.stripTrailingZeros().toPlainString();
  }
}

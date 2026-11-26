package mx.constructora.importacion.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mx.constructora.historico.Registros.EntradaValorada;

/**
 * Costo de las salidas del histórico (issue 47): promedio ponderado, sin IVA, de las entradas del
 * material hasta la fecha de la salida, acumulado desde el principio del Excel.
 *
 * <p>No es el promedio móvil del kardex: el Excel tiene existencias negativas y salidas antes de su
 * entrada, así que restar salidas daría costos sin sentido. El ponderado acumulado no depende del
 * orden de las salidas y siempre da el mismo resultado (ADR-011). Si la salida es anterior a toda
 * entrada, o el material nunca entró por el Excel, el costo se estima.
 */
public final class Valuacion {

  /** De dónde salió el costo. */
  public enum Origen {
    /** Ponderado de las entradas hasta la fecha. */
    PONDERADO,
    /** La salida es anterior a toda entrada: ponderado de todas las entradas del material. */
    ENTRADAS_POSTERIORES,
    /** El material nunca entró por el Excel: su precio de referencia del catálogo. */
    REFERENCIA
  }

  /** Un costo unitario sin IVA y de dónde salió. */
  public record Costo(BigDecimal unitario, Origen origen) {
    public boolean estimado() {
      return origen != Origen.PONDERADO;
    }
  }

  private record Acumulado(LocalDate fecha, BigDecimal cantidad, BigDecimal importe) {}

  private final Map<Long, List<Acumulado>> porMaterial = new HashMap<>();
  private final Map<Long, BigDecimal> referencia;

  public Valuacion(List<EntradaValorada> entradas, Map<Long, BigDecimal> referencia) {
    this.referencia = referencia;
    Map<Long, List<EntradaValorada>> agrupadas = new HashMap<>();
    for (EntradaValorada e : entradas) {
      if (e.cantidad().signum() > 0 && e.importe() != null && e.importe().signum() >= 0) {
        agrupadas.computeIfAbsent(e.materialId(), m -> new ArrayList<>()).add(e);
      }
    }
    agrupadas.forEach(
        (material, lista) -> {
          lista.sort(Comparator.comparing(EntradaValorada::fecha));
          List<Acumulado> acumulados = new ArrayList<>();
          BigDecimal cantidad = BigDecimal.ZERO;
          BigDecimal importe = BigDecimal.ZERO;
          for (EntradaValorada e : lista) {
            cantidad = cantidad.add(e.cantidad());
            importe = importe.add(e.importe());
            if (!acumulados.isEmpty() && acumulados.getLast().fecha().equals(e.fecha())) {
              acumulados.removeLast();
            }
            acumulados.add(new Acumulado(e.fecha(), cantidad, importe));
          }
          porMaterial.put(material, acumulados);
        });
  }

  /** Costo unitario del material en la fecha; {@code null} si no hay con qué valuarlo. */
  public Costo costo(long materialId, LocalDate fecha) {
    List<Acumulado> acumulados = porMaterial.get(materialId);
    if (acumulados != null && !acumulados.isEmpty()) {
      Acumulado hasta = null;
      int bajo = 0;
      int alto = acumulados.size() - 1;
      while (bajo <= alto) {
        int medio = (bajo + alto) >>> 1;
        if (!acumulados.get(medio).fecha().isAfter(fecha)) {
          hasta = acumulados.get(medio);
          bajo = medio + 1;
        } else {
          alto = medio - 1;
        }
      }
      if (hasta != null && hasta.cantidad().signum() > 0) {
        return new Costo(unitario(hasta), Origen.PONDERADO);
      }
      Acumulado total = acumulados.getLast();
      if (total.cantidad().signum() > 0) {
        return new Costo(unitario(total), Origen.ENTRADAS_POSTERIORES);
      }
    }
    BigDecimal precio = referencia.get(materialId);
    if (precio != null && precio.signum() > 0) {
      return new Costo(precio.setScale(6, RoundingMode.HALF_UP), Origen.REFERENCIA);
    }
    return null;
  }

  private static BigDecimal unitario(Acumulado a) {
    return a.importe().divide(a.cantidad(), 6, RoundingMode.HALF_UP);
  }

  /**
   * Precio sin IVA de una recepción que no lo trae: ponderado de las entradas con precio del mismo
   * material en las {@code dias} alrededor de la fecha; si no hay, de todas. {@code null} si el
   * material no tiene ninguna con precio.
   */
  public static BigDecimal precioCercano(
      List<EntradaValorada> conPrecio, long materialId, LocalDate fecha, int dias) {
    BigDecimal cantidadCerca = BigDecimal.ZERO;
    BigDecimal importeCerca = BigDecimal.ZERO;
    BigDecimal cantidad = BigDecimal.ZERO;
    BigDecimal importe = BigDecimal.ZERO;
    for (EntradaValorada e : conPrecio) {
      if (e.materialId() != materialId || e.cantidad().signum() <= 0) {
        continue;
      }
      cantidad = cantidad.add(e.cantidad());
      importe = importe.add(e.importe());
      if (fecha != null
          && !e.fecha().isBefore(fecha.minusDays(dias))
          && !e.fecha().isAfter(fecha.plusDays(dias))) {
        cantidadCerca = cantidadCerca.add(e.cantidad());
        importeCerca = importeCerca.add(e.importe());
      }
    }
    if (cantidadCerca.signum() > 0) {
      return importeCerca.divide(cantidadCerca, 6, RoundingMode.HALF_UP);
    }
    if (cantidad.signum() > 0) {
      return importe.divide(cantidad, 6, RoundingMode.HALF_UP);
    }
    return null;
  }
}

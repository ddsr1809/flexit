package mx.constructora.importacion.dominio;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.constructora.importacion.dominio.Modelo.EstadoFila;

/**
 * Totales del Excel contra lo que se carga, por concepto ("Cantidad por material y mes") y llave
 * ("CEMENT BOND GRIS · 2026-07"). Lo que dice el Excel se suma de las celdas capturadas de cada
 * renglón; lo cargado, de los registros que el renglón produce (después de repartir lotes y separar
 * pagos parciales). Cada diferencia tiene que ser 0: lo que no se carga está en cuarentena o
 * descartado con motivo.
 */
public final class Conciliacion {

  /** Un total. {@code diferencia} = excel − cargado − pendiente − descartado. */
  public record Total(
      String concepto,
      String llave,
      BigDecimal excel,
      BigDecimal cargado,
      BigDecimal pendiente,
      BigDecimal descartado) {

    public BigDecimal diferencia() {
      return excel.subtract(cargado).subtract(pendiente).subtract(descartado);
    }
  }

  private final Map<String, BigDecimal[]> sumas = new LinkedHashMap<>();

  /**
   * Suma un renglón.
   *
   * @param excel lo que dice el Excel para esta llave
   * @param cargado lo que producen sus registros (solo si el renglón entra)
   */
  public void sumar(
      String concepto, String llave, EstadoFila estado, BigDecimal excel, BigDecimal cargado) {
    BigDecimal[] t =
        sumas.computeIfAbsent(
            concepto + "\u0000" + (llave == null ? "—" : llave),
            k ->
                new BigDecimal[] {
                  BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
                });
    BigDecimal valor = excel == null ? BigDecimal.ZERO : excel;
    t[0] = t[0].add(valor);
    switch (estado) {
      case CUARENTENA -> t[2] = t[2].add(valor);
      case DESCARTADA -> t[3] = t[3].add(valor);
      default -> t[1] = t[1].add(cargado == null ? BigDecimal.ZERO : cargado);
    }
  }

  public List<Total> totales() {
    List<Total> lista = new ArrayList<>();
    sumas.forEach(
        (k, t) -> {
          String[] partes = k.split("\u0000", 2);
          lista.add(
              new Total(
                  partes[0],
                  partes[1],
                  Aritmetica.normal(t[0]),
                  Aritmetica.normal(t[1]),
                  Aritmetica.normal(t[2]),
                  Aritmetica.normal(t[3])));
        });
    return lista;
  }
}

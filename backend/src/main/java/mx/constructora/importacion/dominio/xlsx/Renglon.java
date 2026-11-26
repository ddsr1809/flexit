package mx.constructora.importacion.dominio.xlsx;

import java.util.Map;

/**
 * Una fila de la hoja con sus celdas no vacías, por letra de columna.
 *
 * @param numero fila de Excel, desde 1
 */
public record Renglon(int numero, Map<String, Celda> celdas) {

  public Renglon {
    celdas = Map.copyOf(celdas);
  }

  /** La celda de la columna, o {@code null}. */
  public Celda celda(String columna) {
    return celdas.get(columna);
  }

  /** Lo capturado en la columna (ver {@link Celda#capturado()}), o {@code null}. */
  public String capturado(String columna) {
    Celda celda = celdas.get(columna);
    return celda == null ? null : celda.capturado();
  }

  /** {@code true} si la columna tiene una fórmula. */
  public boolean esFormula(String columna) {
    Celda celda = celdas.get(columna);
    return celda != null && celda.esFormula();
  }
}

package mx.constructora.importacion.dominio.xlsx;

/**
 * Una celda tal como está guardada en el archivo.
 *
 * <p>{@code valor} es el texto exacto: los números como los escribió Excel ({@code 1072615.53}, sin
 * redondear por formato), las fechas en ISO ({@code 2026-06-22}, {@code 2026-06-22T10:00} o {@code
 * 10:00:00} si es solo hora), los lógicos como {@code TRUE}/{@code FALSE} y los errores como {@code
 * #REF!}. Si la celda es una fórmula, {@code formula} trae su texto (sin el {@code =}) y {@code
 * valor} es el último resultado guardado, que <b>no se usa</b>: el archivo de almacén se guardó sin
 * terminar de calcular.
 *
 * @param referencia {@code B12}
 * @param columna {@code B}
 */
public record Celda(String referencia, String columna, Tipo tipo, String valor, String formula) {

  /** Qué hay en la celda. */
  public enum Tipo {
    TEXTO,
    NUMERO,
    FECHA,
    LOGICO,
    ERROR
  }

  /** {@code true} si la celda es una fórmula (simple, compartida o de matriz). */
  public boolean esFormula() {
    return formula != null;
  }

  /**
   * Lo capturado: el valor si no es fórmula ni error y no está vacío; si no, {@code null}. Es lo
   * único que la migración lee de una celda.
   */
  public String capturado() {
    if (esFormula() || tipo == Tipo.ERROR || valor == null || valor.isBlank()) {
      return null;
    }
    return valor;
  }
}

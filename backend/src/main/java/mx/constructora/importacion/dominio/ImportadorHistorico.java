package mx.constructora.importacion.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import mx.constructora.historico.Registros.Calidad;
import mx.constructora.historico.Registros.Lote;
import mx.constructora.historico.Registros.Traza;
import mx.constructora.importacion.dominio.Modelo.Accion;
import mx.constructora.importacion.dominio.Modelo.EstadoFila;
import mx.constructora.importacion.dominio.Modelo.FilaValidada;

/**
 * Base de los importadores del histórico (H5): cada renglón válido o corregido produce uno o varios
 * registros del esquema {@code historico}. Confirmar y conciliar usan el mismo {@link #registrar},
 * así que lo que se concilia es exactamente lo que se escribe.
 */
abstract class ImportadorHistorico implements Importador {

  /** Los registros que produce un renglón que entra. */
  abstract void registrar(FilaValidada fila, Contexto contexto, Lote lote);

  /** Suma el renglón a la conciliación; {@code lote} trae lo que produjo (vacío si no entra). */
  abstract void conciliar(FilaValidada fila, Lote lote, Conciliacion conciliacion);

  @Override
  public final void aplicar(List<FilaValidada> filas, Contexto contexto, Destino destino) {
    Lote lote = new Lote();
    for (FilaValidada fila : filas) {
      if (entra(fila)) {
        registrar(fila, contexto, lote);
      }
    }
    destino.historico(lote);
  }

  @Override
  public final List<Conciliacion.Total> conciliar(List<FilaValidada> filas, Contexto contexto) {
    Conciliacion conciliacion = new Conciliacion();
    for (FilaValidada fila : filas) {
      Lote lote = new Lote();
      if (entra(fila)) {
        registrar(fila, contexto, lote);
      }
      conciliar(fila, lote, conciliacion);
    }
    return conciliacion.totales();
  }

  static boolean entra(FilaValidada fila) {
    return fila.accion() == Accion.CREAR
        && (fila.estado() == EstadoFila.VALIDA || fila.estado() == EstadoFila.CORREGIDA);
  }

  static Traza traza(FilaValidada fila) {
    return new Traza(
        fila.estado() == EstadoFila.CORREGIDA ? Calidad.CORREGIDA : Calidad.VALIDA,
        fila.numero(),
        fila.fila().hoja(),
        fila.fila().filaExcel());
  }

  static BigDecimal decimal(FilaValidada fila, String campo) {
    String valor = fila.dato(campo);
    return valor == null ? null : new BigDecimal(valor);
  }

  static LocalDate fecha(FilaValidada fila, String campo) {
    String valor = fila.dato(campo);
    return valor == null ? null : LocalDate.parse(valor);
  }

  static Integer entero(FilaValidada fila, String campo) {
    String valor = fila.dato(campo);
    return valor == null ? null : Integer.valueOf(valor);
  }

  static boolean si(FilaValidada fila, String campo) {
    return "1".equals(fila.dato(campo));
  }

  /** Lo que dice la celda, recortado al largo de la columna. */
  static String texto(String valor, int largo) {
    String limpio = Comun.limpio(valor);
    return limpio == null || limpio.length() <= largo ? limpio : limpio.substring(0, largo);
  }

  /** Total de cantidades de los registros que produjo el renglón (para conciliar). */
  static BigDecimal suma(List<BigDecimal> valores) {
    return valores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /**
   * Lo que dice el Excel de un número para conciliar: lo capturado o la fórmula simple; si la celda
   * era una fórmula con referencias (que no se lee), el valor que escribió la persona en la
   * cuarentena, guardado en {@code campo}.
   */
  static BigDecimal excel(FilaValidada fila, String columna, String campo) {
    BigDecimal valor = Resolutor.numeroExcel(fila.fila(), columna);
    return valor != null ? valor : decimal(fila, campo);
  }

  static String mes(LocalDate fecha) {
    return fecha == null ? "sin fecha" : fecha.toString().substring(0, 7);
  }
}

package mx.constructora.importacion.dominio;

import java.util.List;
import mx.constructora.importacion.dominio.Modelo.Decisiones;
import mx.constructora.importacion.dominio.Modelo.FilaCruda;
import mx.constructora.importacion.dominio.Modelo.FilaValidada;
import mx.constructora.importacion.dominio.Modelo.Lectura;
import mx.constructora.importacion.dominio.xlsx.LibroXlsx;

/**
 * Un tipo de carga: qué hojas lee (su perfil), cómo valida cada renglón y qué escribe al confirmar.
 *
 * <p>Las tres fases son deterministas: el mismo archivo, con los mismos catálogos, alias y
 * decisiones, da siempre el mismo resultado. Por eso validar se puede repetir cuantas veces haga
 * falta (después de cada decisión) y confirmar vuelve a validar antes de escribir.
 */
public interface Importador {

  TipoCarga tipo();

  /**
   * Lee del libro solo las columnas capturadas (nunca fórmulas).
   *
   * @throws mx.constructora.importacion.dominio.xlsx.ArchivoInvalido si al libro le faltan las
   *     hojas o los encabezados que espera el perfil
   */
  Lectura leer(LibroXlsx libro);

  /** Normaliza y valida todos los renglones de la carga. */
  List<FilaValidada> validar(List<FilaCruda> filas, Contexto contexto, Decisiones decisiones);

  /**
   * Escribe en el sistema los renglones válidos o corregidos con acción {@code CREAR}. Solo se
   * llama si ningún renglón está en cuarentena.
   */
  void aplicar(List<FilaValidada> filas, Contexto contexto, Destino destino);

  /**
   * Totales del Excel contra lo que se carga (H5). Solo los importadores del histórico los tienen:
   * los catálogos se concilian por renglón.
   */
  default List<Conciliacion.Total> conciliar(List<FilaValidada> filas, Contexto contexto) {
    return List.of();
  }
}

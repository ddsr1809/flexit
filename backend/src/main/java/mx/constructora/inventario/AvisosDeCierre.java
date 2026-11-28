package mx.constructora.inventario;

import java.time.LocalDate;
import java.util.List;

/**
 * Lo que otros módulos quieren que se sepa antes de cerrar un mes del inventario, sin que el
 * inventario dependa de ellos: el abastecimiento, por ejemplo, avisa de las requisiciones
 * autorizadas que siguen sin surtir. Un aviso no impide cerrar (H9, ADR-015); lo que impide cerrar
 * lo decide el inventario.
 */
public interface AvisosDeCierre {

  /**
   * Un aviso: código estable, mensaje para la persona, cuántos casos hay y los primeros folios.
   */
  record Aviso(String codigo, String mensaje, int cuantos, List<String> folios) {}

  /** Los avisos del mes que va de {@code desde} a {@code hasta}; vacío si no hay nada. */
  List<Aviso> avisos(LocalDate desde, LocalDate hasta);
}

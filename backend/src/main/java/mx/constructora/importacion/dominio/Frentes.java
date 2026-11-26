package mx.constructora.importacion.dominio;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mx.constructora.importacion.dominio.Modelo.Opcion;
import mx.constructora.importacion.dominio.Modelo.Revision;
import mx.constructora.importacion.dominio.Modelo.Severidad;

/**
 * Cómo se reconoce un frente escrito en el Excel: "PALERMO RESIDENCIAL" en el SUA, "Palermo" en el
 * almacén, "Pagos Corceles P-65" como nombre de hoja del destajo.
 */
final class Frentes {

  private Frentes() {}

  /**
   * El frente al que se refiere {@code texto}, o {@code null} si queda en cuarentena. Lo reconoce
   * por alias, por nombre o clave iguales; si solo contiene el nombre de un frente ("PALERMO
   * RESIDENCIAL" contiene "PALERMO"), lo sugiere y una persona confirma (una vez: se guarda alias).
   */
  static Contexto.Frente resolver(String texto, Revision revision, Contexto contexto) {
    String normal = Normal.texto(texto);
    if (normal == null) {
      revision.pedir(
          "SIN_FRENTE",
          Severidad.CUARENTENA,
          "Falta el frente (y con él, el cliente)",
          null,
          EnumSet.of(Opcion.DESCARTAR));
      return null;
    }
    var alias = contexto.alias("frente", normal);
    if (alias.isPresent()) {
      for (Contexto.Frente frente : contexto.frentes()) {
        if (frente.id() == alias.get()) {
          return frente;
        }
      }
    }
    String clave = Normal.clave(normal, 12);
    for (Contexto.Frente frente : contexto.frentes()) {
      if (normal.equals(frente.nombreNormal()) || frente.clave().equals(clave)) {
        return frente;
      }
    }
    Set<String> palabras = new HashSet<>(Arrays.asList(Normal.palabras(normal).split(" ")));
    List<Contexto.Frente> contenidos =
        contexto.frentes().stream()
            .filter(
                f ->
                    palabras.contains(f.clave())
                        || palabras.containsAll(
                            Arrays.asList(Normal.palabras(f.nombreNormal()).split(" "))))
            .toList();
    if (contenidos.size() == 1) {
      Contexto.Frente sugerido = contenidos.getFirst();
      revision.pedir(
          "FRENTE_POR_CONFIRMAR",
          Severidad.CUARENTENA,
          "\"" + texto.trim() + "\" parece el frente " + sugerido.nombre(),
          Comun.sugerencia("frente", normal, sugerido.id(), sugerido.nombre(), null),
          EnumSet.of(Opcion.ACEPTAR, Opcion.ELEGIR, Opcion.DESCARTAR));
      return null;
    }
    revision.pedir(
        "FRENTE_DESCONOCIDO",
        Severidad.CUARENTENA,
        "No hay un frente \"" + texto.trim() + "\". Elige cuál es o da de alta el frente antes",
        Comun.sugerencia("frente", normal, null, null, null),
        EnumSet.of(Opcion.ELEGIR, Opcion.DESCARTAR));
    return null;
  }
}

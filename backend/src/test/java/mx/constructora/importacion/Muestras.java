package mx.constructora.importacion;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Las muestras anonimizadas de {@code tools/validacion-datos/muestras/}. Se buscan hacia arriba
 * desde el directorio de trabajo, así sirven igual con Maven (backend/) que desde la raíz.
 */
public final class Muestras {

  private Muestras() {}

  public static Path ruta(String nombre) {
    Path actual = Path.of("").toAbsolutePath();
    while (actual != null) {
      Path candidata = actual.resolve("tools/validacion-datos/muestras").resolve(nombre);
      if (Files.exists(candidata)) {
        return candidata;
      }
      actual = actual.getParent();
    }
    throw new IllegalStateException("No encuentro la muestra " + nombre);
  }
}

package mx.constructora.compartido;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folios consecutivos por empresa, tipo y año, sin huecos: {@code ENT-2027-000123}.
 *
 * <p>Se piden dentro de la transacción que crea el documento ({@code MANDATORY}); si esa
 * transacción se revierte, el número queda libre para el siguiente. Dos documentos simultáneos del
 * mismo tipo esperan su turno sobre la misma fila de {@code compartido.folio}.
 */
@Service
public class Folios {

  /** Zona en la que se decide a qué año pertenece un folio. */
  public static final ZoneId ZONA = ZoneId.of("America/Mexico_City");

  private static final Pattern TIPO = Pattern.compile("[A-Z]{2,5}");

  private final JdbcClient jdbc;

  public Folios(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Siguiente folio del año en curso, ya con formato. */
  @Transactional(propagation = Propagation.MANDATORY)
  public String siguiente(String tipo) {
    int anio = LocalDate.now(ZONA).getYear();
    return formatear(tipo, anio, consecutivo(tipo, anio));
  }

  /** Siguiente consecutivo de {@code tipo} en {@code anio}. */
  @Transactional(propagation = Propagation.MANDATORY)
  public long consecutivo(String tipo, int anio) {
    if (!TIPO.matcher(tipo).matches()) {
      throw new IllegalArgumentException("Tipo de folio inválido: " + tipo);
    }
    return jdbc.sql("SELECT compartido.siguiente_folio(:tipo, CAST(:anio AS smallint))")
        .param("tipo", tipo)
        .param("anio", anio)
        .query(Long.class)
        .single();
  }

  public static String formatear(String tipo, int anio, long consecutivo) {
    return "%s-%d-%06d".formatted(tipo, anio, consecutivo);
  }
}

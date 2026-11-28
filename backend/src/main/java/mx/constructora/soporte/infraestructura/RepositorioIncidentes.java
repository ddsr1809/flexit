package mx.constructora.soporte.infraestructura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Incidentes del piloto y su seguimiento (esquema {@code soporte}, V012). */
@Repository
public class RepositorioIncidentes {

  /** Incidente tal como está en la base. */
  public record FilaIncidente(
      long id,
      String folio,
      String severidad,
      String estado,
      String titulo,
      String descripcion,
      Long almacenId,
      String pantalla,
      String peticion,
      String documento,
      Integer issue,
      long reportadoPor,
      OffsetDateTime reportadoEn,
      Long cerradoPor,
      OffsetDateTime cerradoEn,
      String resolucion,
      int reaperturas,
      int version) {}

  /** Una nota del seguimiento. */
  public record FilaNota(
      long id, String tipo, String texto, long usuarioId, OffsetDateTime creado) {}

  /** Lo nuevo de un incidente. */
  public record NuevoIncidente(
      String severidad,
      String titulo,
      String descripcion,
      Long almacenId,
      String pantalla,
      String peticion,
      String documento) {}

  /** Conteo de incidentes por estado y severidad. */
  public record Conteo(String estado, String severidad, int cuantos) {}

  /** Lo que pasó con los incidentes en un periodo. */
  public record Periodo(int reportados, int resueltos, BigDecimal medianaHorasResolucion) {}

  private static final String INCIDENTE =
      """
      SELECT id, folio, severidad, estado, titulo, descripcion, almacen_id, pantalla, peticion,
             documento, issue, reportado_por, reportado_en, cerrado_por, cerrado_en, resolucion,
             reaperturas, version
        FROM soporte.incidente
       WHERE empresa_id = :empresa
      """;

  private final JdbcClient jdbc;

  RepositorioIncidentes(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Incidentes con filtros opcionales; {@code reportadoPor} limita a los de una persona. Primero
   * los abiertos, de más a menos grave, y dentro de cada grupo los más recientes.
   */
  public List<FilaIncidente> incidentes(
      long empresa, String estado, String severidad, Long almacen, Long reportadoPor) {
    return jdbc.sql(
            INCIDENTE
                + """
                   AND (CAST(:estado AS text) IS NULL OR estado = :estado)
                   AND (CAST(:severidad AS text) IS NULL OR severidad = :severidad)
                   AND (CAST(:almacen AS bigint) IS NULL OR almacen_id = :almacen)
                   AND (CAST(:reporta AS bigint) IS NULL OR reportado_por = :reporta)
                 ORDER BY estado = 'abierto' DESC,
                          array_position(ARRAY['critica', 'mayor', 'menor'], severidad),
                          reportado_en DESC, id DESC
                 LIMIT 500
                """)
        .param("empresa", empresa)
        .param("estado", estado)
        .param("severidad", severidad)
        .param("almacen", almacen)
        .param("reporta", reportadoPor)
        .query(FilaIncidente.class)
        .list();
  }

  public Optional<FilaIncidente> incidente(long empresa, long id, boolean bloquear) {
    return jdbc.sql(INCIDENTE + " AND id = :id" + (bloquear ? " FOR UPDATE" : ""))
        .param("empresa", empresa)
        .param("id", id)
        .query(FilaIncidente.class)
        .optional();
  }

  public long insertar(long empresa, String folio, NuevoIncidente datos, long usuario) {
    return jdbc.sql(
            """
            INSERT INTO soporte.incidente
              (empresa_id, folio, severidad, titulo, descripcion, almacen_id, pantalla, peticion,
               documento, reportado_por)
            VALUES (:empresa, :folio, :severidad, :titulo, :descripcion, :almacen, :pantalla,
                    :peticion, :documento, :usuario)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("folio", folio)
        .param("severidad", datos.severidad())
        .param("titulo", datos.titulo())
        .param("descripcion", datos.descripcion())
        .param("almacen", datos.almacenId())
        .param("pantalla", datos.pantalla())
        .param("peticion", datos.peticion())
        .param("documento", datos.documento())
        .param("usuario", usuario)
        .query(Long.class)
        .single();
  }

  /** Cambia severidad e issue de un incidente abierto; {@code false} si la versión no coincide. */
  public boolean clasificar(long empresa, long id, int version, String severidad, Integer issue) {
    return jdbc.sql(
                """
                UPDATE soporte.incidente
                   SET severidad = :severidad, issue = :issue, version = version + 1
                 WHERE empresa_id = :empresa AND id = :id AND version = :version
                """)
            .param("empresa", empresa)
            .param("id", id)
            .param("version", version)
            .param("severidad", severidad)
            .param("issue", issue)
            .update()
        == 1;
  }

  /** Resuelve o descarta un incidente abierto. */
  public void cerrar(long empresa, long id, String estado, String resolucion, long usuario) {
    jdbc.sql(
            """
            UPDATE soporte.incidente
               SET estado = :estado, cerrado_por = :usuario, cerrado_en = now(),
                   resolucion = :resolucion, version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND estado = 'abierto'
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("estado", estado)
        .param("resolucion", resolucion)
        .param("usuario", usuario)
        .update();
  }

  /** Vuelve a abrir un incidente resuelto o descartado. */
  public void reabrir(long empresa, long id) {
    jdbc.sql(
            """
            UPDATE soporte.incidente
               SET estado = 'abierto', cerrado_por = NULL, cerrado_en = NULL, resolucion = NULL,
                   reaperturas = reaperturas + 1, version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND estado <> 'abierto'
            """)
        .param("empresa", empresa)
        .param("id", id)
        .update();
  }

  public void nota(long empresa, long incidente, String tipo, String texto, long usuario) {
    jdbc.sql(
            """
            INSERT INTO soporte.incidente_nota (empresa_id, incidente_id, tipo, texto, usuario_id)
            VALUES (:empresa, :incidente, :tipo, :texto, :usuario)
            """)
        .param("empresa", empresa)
        .param("incidente", incidente)
        .param("tipo", tipo)
        .param("texto", texto)
        .param("usuario", usuario)
        .update();
  }

  public List<FilaNota> notas(long empresa, long incidente) {
    return jdbc.sql(
            """
            SELECT id, tipo, texto, usuario_id, creado FROM soporte.incidente_nota
             WHERE empresa_id = :empresa AND incidente_id = :incidente ORDER BY id
            """)
        .param("empresa", empresa)
        .param("incidente", incidente)
        .query(FilaNota.class)
        .list();
  }

  /** Cuántos incidentes hay hoy por estado y severidad (de un almacén, o de todos). */
  public List<Conteo> conteo(long empresa, Long almacen) {
    return jdbc.sql(
            """
            SELECT estado, severidad, count(*)::int AS cuantos FROM soporte.incidente
             WHERE empresa_id = :empresa
               AND (CAST(:almacen AS bigint) IS NULL OR almacen_id = :almacen)
             GROUP BY estado, severidad
            """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .query(Conteo.class)
        .list();
  }

  /**
   * Reportados y resueltos entre dos días (hora de México) y la mediana de horas de reporte a
   * resolución de los resueltos en ese periodo.
   */
  public Periodo periodo(long empresa, Long almacen, LocalDate desde, LocalDate hasta) {
    return jdbc.sql(
            """
            SELECT count(*) FILTER (WHERE (reportado_en AT TIME ZONE 'America/Mexico_City')::date
                                            BETWEEN :desde AND :hasta)::int AS reportados,
                   count(*) FILTER (WHERE estado = 'resuelto'
                                      AND (cerrado_en AT TIME ZONE 'America/Mexico_City')::date
                                            BETWEEN :desde AND :hasta)::int AS resueltos,
                   round(CAST(percentile_cont(0.5) WITHIN GROUP (
                           ORDER BY extract(epoch FROM cerrado_en - reportado_en) / 3600)
                         FILTER (WHERE estado = 'resuelto'
                                   AND (cerrado_en AT TIME ZONE 'America/Mexico_City')::date
                                         BETWEEN :desde AND :hasta) AS numeric), 1)
                     AS mediana_horas_resolucion
              FROM soporte.incidente
             WHERE empresa_id = :empresa
               AND (CAST(:almacen AS bigint) IS NULL OR almacen_id = :almacen)
            """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .param("desde", desde)
        .param("hasta", hasta)
        .query(Periodo.class)
        .single();
  }
}

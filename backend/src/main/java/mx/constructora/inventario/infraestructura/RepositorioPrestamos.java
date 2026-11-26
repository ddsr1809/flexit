package mx.constructora.inventario.infraestructura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Préstamos de herramienta. */
@Repository
public class RepositorioPrestamos {

  /** Préstamo tal como está en la base. */
  public record FilaPrestamo(
      long id,
      String folio,
      long almacenId,
      long materialId,
      BigDecimal cantidad,
      long responsableId,
      Long contratoId,
      LocalDate fecha,
      LocalDate compromiso,
      String estado,
      LocalDate devueltoEn,
      long usuarioPresta,
      Long usuarioRecibe,
      String observaciones,
      int version,
      OffsetDateTime creado) {}

  private static final String PRESTAMO =
      """
      SELECT id, folio, almacen_id, material_id, cantidad, responsable_id, contrato_id, fecha,
             compromiso, estado, devuelto_en, usuario_presta, usuario_recibe, observaciones,
             version, creado
        FROM inventario.prestamo
       WHERE empresa_id = :empresa
      """;

  private final JdbcClient jdbc;

  RepositorioPrestamos(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<FilaPrestamo> prestamos(
      long empresa, Long almacen, String estado, LocalDate vencidosAl) {
    return jdbc.sql(
            PRESTAMO
                + """
                   AND (CAST(:almacen AS bigint) IS NULL OR almacen_id = :almacen)
                   AND (CAST(:estado AS text) IS NULL OR estado = :estado)
                   AND (CAST(:vencidos AS date) IS NULL
                        OR (estado = 'abierto' AND compromiso < :vencidos))
                 ORDER BY estado = 'abierto' DESC, compromiso NULLS LAST, fecha DESC, id DESC
                 LIMIT 500
                """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .param("estado", estado)
        .param("vencidos", vencidosAl)
        .query(FilaPrestamo.class)
        .list();
  }

  public Optional<FilaPrestamo> prestamo(long empresa, long id, boolean bloquear) {
    return jdbc.sql(PRESTAMO + " AND id = :id" + (bloquear ? " FOR UPDATE" : ""))
        .param("empresa", empresa)
        .param("id", id)
        .query(FilaPrestamo.class)
        .optional();
  }

  public long insertar(
      long empresa,
      String folio,
      long almacen,
      long material,
      BigDecimal cantidad,
      long responsable,
      Long contrato,
      LocalDate fecha,
      LocalDate compromiso,
      long usuario,
      String observaciones) {
    return jdbc.sql(
            """
            INSERT INTO inventario.prestamo
              (empresa_id, folio, almacen_id, material_id, cantidad, responsable_id, contrato_id,
               fecha, compromiso, usuario_presta, observaciones)
            VALUES (:empresa, :folio, :almacen, :material, :cantidad, :responsable, :contrato,
                    :fecha, :compromiso, :usuario, :observaciones)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("folio", folio)
        .param("almacen", almacen)
        .param("material", material)
        .param("cantidad", cantidad)
        .param("responsable", responsable)
        .param("contrato", contrato)
        .param("fecha", fecha)
        .param("compromiso", compromiso)
        .param("usuario", usuario)
        .param("observaciones", observaciones)
        .query(Long.class)
        .single();
  }

  public void cerrar(long empresa, long id, String estado, LocalDate devueltoEn, long usuario) {
    jdbc.sql(
            """
            UPDATE inventario.prestamo
               SET estado = :estado, devuelto_en = :devuelto, usuario_recibe = :usuario,
                   version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND estado = 'abierto'
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("estado", estado)
        .param("devuelto", devueltoEn)
        .param("usuario", usuario)
        .update();
  }
}

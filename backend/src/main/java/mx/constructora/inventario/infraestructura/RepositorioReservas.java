package mx.constructora.inventario.infraestructura;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reservas de existencia para renglones de requisición ({@code inventario.reserva}). Un trigger de
 * la base mantiene {@code saldo.reservado} igual a la suma de las reservas; quien reserva bloquea
 * antes el saldo ({@code FOR UPDATE}), y las reservas se bloquean después del saldo, nunca antes.
 */
@Repository
public class RepositorioReservas {

  /** Una reserva tal como está en la base. */
  public record FilaReserva(
      long id, long almacenId, long materialId, long requisicionLineaId, BigDecimal cantidad) {}

  private static final String RESERVA =
      """
      SELECT id, almacen_id, material_id, requisicion_linea_id, cantidad
        FROM inventario.reserva
       WHERE empresa_id = :empresa
      """;

  private final JdbcClient jdbc;

  RepositorioReservas(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Las reservas de esos renglones de requisición; con {@code bloquear}, hasta el fin. */
  public List<FilaReserva> deRenglones(
      long empresa, Collection<Long> requisicionLineas, boolean bloquear) {
    if (requisicionLineas.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            RESERVA
                + " AND requisicion_linea_id IN (:lineas) ORDER BY requisicion_linea_id"
                + (bloquear ? " FOR UPDATE" : ""))
        .param("empresa", empresa)
        .param("lineas", List.copyOf(requisicionLineas))
        .query(FilaReserva.class)
        .list();
  }

  /** Reservas con cantidad de un material en un almacén, la más reciente primero, bloqueadas. */
  public List<FilaReserva> vigentesBloqueadas(long empresa, long almacen, long material) {
    return jdbc.sql(
            RESERVA
                + """
                   AND almacen_id = :almacen AND material_id = :material AND cantidad > 0
                 ORDER BY creado DESC, id DESC
                   FOR UPDATE
                """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .param("material", material)
        .query(FilaReserva.class)
        .list();
  }

  /** Suma {@code cantidad} a la reserva del renglón (la crea si no existe). */
  public void sumar(
      long empresa, long almacen, long material, long requisicionLinea, BigDecimal cantidad) {
    jdbc.sql(
            """
            INSERT INTO inventario.reserva
              (empresa_id, almacen_id, material_id, requisicion_linea_id, cantidad)
            VALUES (:empresa, :almacen, :material, :linea, :cantidad)
            ON CONFLICT (empresa_id, requisicion_linea_id)
            DO UPDATE SET cantidad = inventario.reserva.cantidad + EXCLUDED.cantidad,
                          actualizado = now()
            """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .param("material", material)
        .param("linea", requisicionLinea)
        .param("cantidad", cantidad)
        .update();
  }

  /** Deja la reserva en {@code cantidad} (surtir o liberar la bajan; nunca se borra). */
  public void fijar(long empresa, long id, BigDecimal cantidad) {
    jdbc.sql(
            """
            UPDATE inventario.reserva SET cantidad = :cantidad, actualizado = now()
             WHERE empresa_id = :empresa AND id = :id
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("cantidad", cantidad)
        .update();
  }
}

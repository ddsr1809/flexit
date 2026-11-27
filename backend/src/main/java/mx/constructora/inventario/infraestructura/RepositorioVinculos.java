package mx.constructora.inventario.infraestructura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Lo que el abastecimiento consulta de los documentos del almacén: cuánto se ha surtido de cada
 * renglón de requisición y cuánto se ha recibido de cada renglón de pedido. No se guarda en ningún
 * lado: sale de los documentos aplicados (un documento cancelado después de aplicarse sigue
 * contando, y su inverso lo resta).
 */
@Repository
public class RepositorioVinculos {

  /**
   * Surtido de un renglón de requisición: neto de devoluciones, y lo que está en vales sin aplicar.
   */
  public record FilaSurtido(long lineaId, BigDecimal surtido, BigDecimal enProceso) {}

  /** Recibido de un renglón de pedido, neto de devoluciones al proveedor. */
  public record FilaRecibido(long lineaId, BigDecimal recibido) {}

  /** Documento ligado a un renglón de requisición o de pedido. */
  public record FilaDocumento(
      long lineaId,
      long movimientoId,
      String folio,
      String tipo,
      String estado,
      LocalDate fecha,
      BigDecimal cantidad) {}

  /** Último proveedor que surtió un material en el sistema. */
  public record FilaProveedor(long materialId, long proveedorId, LocalDate fecha) {}

  private final JdbcClient jdbc;

  RepositorioVinculos(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<FilaSurtido> surtido(long empresa, Collection<Long> requisicionLineas) {
    if (requisicionLineas.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT x.linea_id, coalesce(sum(x.surtido), 0) AS surtido,
                   coalesce(sum(x.en_proceso), 0) AS en_proceso
              FROM (SELECT l.requisicion_linea_id AS linea_id,
                           CASE WHEN m.aplicado_en IS NOT NULL THEN l.cantidad ELSE 0 END AS surtido,
                           CASE WHEN m.estado IN ('borrador', 'pendiente', 'autorizado')
                                THEN l.cantidad ELSE 0 END AS en_proceso
                      FROM inventario.movimiento_linea l
                      JOIN inventario.movimiento m
                        ON m.empresa_id = l.empresa_id AND m.id = l.movimiento_id
                     WHERE l.empresa_id = :empresa AND l.requisicion_linea_id IN (:lineas)
                    UNION ALL
                    SELECT o.requisicion_linea_id, -d.cantidad, 0
                      FROM inventario.movimiento_linea d
                      JOIN inventario.movimiento md
                        ON md.empresa_id = d.empresa_id AND md.id = d.movimiento_id
                      JOIN inventario.movimiento_linea o
                        ON o.empresa_id = d.empresa_id AND o.id = d.linea_origen_id
                     WHERE d.empresa_id = :empresa AND d.tipo = 'devolucion'
                       AND md.estado = 'aplicado' AND o.requisicion_linea_id IN (:lineas)) x
             GROUP BY x.linea_id
            """)
        .param("empresa", empresa)
        .param("lineas", List.copyOf(requisicionLineas))
        .query(FilaSurtido.class)
        .list();
  }

  public List<FilaRecibido> recibido(long empresa, Collection<Long> pedidoLineas) {
    if (pedidoLineas.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT x.linea_id, coalesce(sum(x.cantidad), 0) AS recibido
              FROM (SELECT l.pedido_linea_id AS linea_id, l.cantidad
                      FROM inventario.movimiento_linea l
                      JOIN inventario.movimiento m
                        ON m.empresa_id = l.empresa_id AND m.id = l.movimiento_id
                     WHERE l.empresa_id = :empresa AND l.pedido_linea_id IN (:lineas)
                       AND m.aplicado_en IS NOT NULL
                    UNION ALL
                    SELECT o.pedido_linea_id, -d.cantidad
                      FROM inventario.movimiento_linea d
                      JOIN inventario.movimiento md
                        ON md.empresa_id = d.empresa_id AND md.id = d.movimiento_id
                      JOIN inventario.movimiento_linea o
                        ON o.empresa_id = d.empresa_id AND o.id = d.linea_origen_id
                     WHERE d.empresa_id = :empresa AND d.tipo = 'devolucion_proveedor'
                       AND md.estado = 'aplicado' AND o.pedido_linea_id IN (:lineas)) x
             GROUP BY x.linea_id
            """)
        .param("empresa", empresa)
        .param("lineas", List.copyOf(pedidoLineas))
        .query(FilaRecibido.class)
        .list();
  }

  /** Vales de esos renglones de requisición (todas sus líneas ligadas), más recientes primero. */
  public List<FilaDocumento> vales(long empresa, Collection<Long> requisicionLineas) {
    return documentos(empresa, "requisicion_linea_id", requisicionLineas);
  }

  /** Recepciones de esos renglones de pedido, más recientes primero. */
  public List<FilaDocumento> recepciones(long empresa, Collection<Long> pedidoLineas) {
    return documentos(empresa, "pedido_linea_id", pedidoLineas);
  }

  private List<FilaDocumento> documentos(long empresa, String columna, Collection<Long> lineas) {
    if (lineas.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT l.%s AS linea_id, m.id AS movimiento_id, m.folio, m.tipo, m.estado, m.fecha,
                   l.cantidad
              FROM inventario.movimiento_linea l
              JOIN inventario.movimiento m ON m.empresa_id = l.empresa_id AND m.id = l.movimiento_id
             WHERE l.empresa_id = :empresa AND l.%s IN (:lineas)
             ORDER BY m.fecha DESC, m.id DESC, l.renglon, l.id
            """
                .formatted(columna, columna))
        .param("empresa", empresa)
        .param("lineas", List.copyOf(lineas))
        .query(FilaDocumento.class)
        .list();
  }

  /** El proveedor de la última recepción aplicada de cada material. */
  public List<FilaProveedor> ultimosProveedores(long empresa, Collection<Long> materiales) {
    if (materiales.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT DISTINCT ON (l.material_id) l.material_id, m.proveedor_id, m.fecha
              FROM inventario.movimiento_linea l
              JOIN inventario.movimiento m ON m.empresa_id = l.empresa_id AND m.id = l.movimiento_id
             WHERE l.empresa_id = :empresa AND l.tipo = 'entrada' AND m.estado = 'aplicado'
               AND l.material_id IN (:materiales)
             ORDER BY l.material_id, m.fecha DESC, m.id DESC
            """)
        .param("empresa", empresa)
        .param("materiales", List.copyOf(materiales))
        .query(FilaProveedor.class)
        .list();
  }
}

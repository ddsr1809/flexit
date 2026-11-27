package mx.constructora.abastecimiento.infraestructura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Pedidos a proveedor, sus renglones y qué renglones de requisición cubren ({@code
 * abastecimiento.pedido}, {@code pedido_linea} y {@code pedido_requisicion}).
 */
@Repository
public class RepositorioPedidos {

  /** Un pedido tal como está en la base. */
  public record FilaPedido(
      long id,
      String folio,
      long proveedorId,
      long almacenId,
      LocalDate fecha,
      LocalDate fechaEntrega,
      String estado,
      long usuarioCaptura,
      Long usuarioEnvia,
      OffsetDateTime enviadoEn,
      OffsetDateTime cerradoEn,
      String motivoCierre,
      String observaciones,
      int version,
      OffsetDateTime creado) {}

  /** Un renglón del pedido. */
  public record FilaRenglon(
      long id,
      long pedidoId,
      int renglon,
      long materialId,
      BigDecimal cantidad,
      BigDecimal cantidadCaptura,
      long unidadCapturaId,
      BigDecimal precio,
      BigDecimal tasaIva,
      String observaciones) {}

  /** Lo que un renglón del pedido cubre de un renglón de requisición. */
  public record FilaLiga(
      long pedidoLineaId, long requisicionLineaId, BigDecimal cantidad, BigDecimal recibido) {}

  /** Renglón de las listas. */
  public record FilaResumen(
      long id,
      String folio,
      String estado,
      long proveedorId,
      long almacenId,
      LocalDate fecha,
      LocalDate fechaEntrega,
      int renglones,
      BigDecimal subtotal,
      OffsetDateTime creado) {}

  /** Cabecera al crear o corregir. */
  public record Cabecera(
      long proveedorId,
      long almacenId,
      LocalDate fecha,
      LocalDate fechaEntrega,
      String observaciones) {}

  /** Renglón nuevo. */
  public record NuevoRenglon(
      int renglon,
      long materialId,
      BigDecimal cantidad,
      BigDecimal cantidadCaptura,
      long unidadCapturaId,
      BigDecimal precio,
      BigDecimal tasaIva,
      String observaciones) {}

  private static final String PEDIDO =
      """
      SELECT id, folio, proveedor_id, almacen_id, fecha, fecha_entrega, estado, usuario_captura,
             usuario_envia, enviado_en, cerrado_en, motivo_cierre, observaciones, version, creado
        FROM abastecimiento.pedido
       WHERE empresa_id = :empresa
      """;

  private static final String RENGLON =
      """
      SELECT id, pedido_id, renglon, material_id, cantidad, cantidad_captura, unidad_captura_id,
             precio, tasa_iva, observaciones
        FROM abastecimiento.pedido_linea
       WHERE empresa_id = :empresa
      """;

  private final JdbcClient jdbc;

  RepositorioPedidos(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------ Lectura

  public Optional<FilaPedido> pedido(long empresa, long id) {
    return jdbc.sql(PEDIDO + " AND id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .query(FilaPedido.class)
        .optional();
  }

  public Optional<FilaPedido> bloquear(long empresa, long id) {
    return jdbc.sql(PEDIDO + " AND id = :id FOR UPDATE")
        .param("empresa", empresa)
        .param("id", id)
        .query(FilaPedido.class)
        .optional();
  }

  public List<FilaRenglon> renglones(long empresa, long pedido) {
    return jdbc.sql(RENGLON + " AND pedido_id = :pedido ORDER BY renglon")
        .param("empresa", empresa)
        .param("pedido", pedido)
        .query(FilaRenglon.class)
        .list();
  }

  /** Ligas con requisiciones de los renglones del pedido. */
  public List<FilaLiga> ligas(long empresa, long pedido) {
    return jdbc.sql(
            """
            SELECT x.pedido_linea_id, x.requisicion_linea_id, x.cantidad, x.recibido
              FROM abastecimiento.pedido_requisicion x
              JOIN abastecimiento.pedido_linea l ON l.empresa_id = x.empresa_id AND l.id = x.pedido_linea_id
             WHERE x.empresa_id = :empresa AND l.pedido_id = :pedido
             ORDER BY x.pedido_linea_id, x.requisicion_linea_id
            """)
        .param("empresa", empresa)
        .param("pedido", pedido)
        .query(FilaLiga.class)
        .list();
  }

  /**
   * Lo que falta por llegar para cada renglón de requisición en pedidos vivos (borrador o enviado):
   * Σ (cubierto − recibido).
   */
  public List<FilaLiga> porRecibir(long empresa, Collection<Long> requisicionLineas) {
    if (requisicionLineas.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT 0::bigint AS pedido_linea_id, x.requisicion_linea_id,
                   sum(x.cantidad - x.recibido) AS cantidad, 0::numeric AS recibido
              FROM abastecimiento.pedido_requisicion x
              JOIN abastecimiento.pedido_linea l ON l.empresa_id = x.empresa_id AND l.id = x.pedido_linea_id
              JOIN abastecimiento.pedido p ON p.empresa_id = l.empresa_id AND p.id = l.pedido_id
             WHERE x.empresa_id = :empresa AND x.requisicion_linea_id IN (:lineas)
               AND p.estado IN ('borrador', 'enviado')
             GROUP BY x.requisicion_linea_id
            """)
        .param("empresa", empresa)
        .param("lineas", List.copyOf(requisicionLineas))
        .query(FilaLiga.class)
        .list();
  }

  /** Pedidos filtrados, más recientes primero. */
  public List<FilaResumen> buscar(
      long empresa,
      List<String> estados,
      Long proveedor,
      Long almacen,
      LocalDate desde,
      LocalDate hasta,
      String folio,
      int limite,
      long saltar) {
    return jdbc.sql(
            """
            SELECT p.id, p.folio, p.estado, p.proveedor_id, p.almacen_id, p.fecha, p.fecha_entrega,
                   (SELECT count(*) FROM abastecimiento.pedido_linea l
                     WHERE l.empresa_id = p.empresa_id AND l.pedido_id = p.id)::int AS renglones,
                   (SELECT coalesce(sum(round(l.cantidad_captura * l.precio, 2)), 0)
                      FROM abastecimiento.pedido_linea l
                     WHERE l.empresa_id = p.empresa_id AND l.pedido_id = p.id) AS subtotal,
                   p.creado
              FROM abastecimiento.pedido p
             WHERE p.empresa_id = :empresa
               AND (:sinEstados OR p.estado IN (:estados))
               AND (CAST(:proveedor AS bigint) IS NULL OR p.proveedor_id = :proveedor)
               AND (CAST(:almacen AS bigint) IS NULL OR p.almacen_id = :almacen)
               AND (CAST(:desde AS date) IS NULL OR p.fecha >= :desde)
               AND (CAST(:hasta AS date) IS NULL OR p.fecha <= :hasta)
               AND (CAST(:folio AS text) IS NULL OR p.folio LIKE '%' || upper(:folio) || '%')
             ORDER BY p.fecha DESC, p.id DESC
             LIMIT :limite OFFSET :saltar
            """)
        .param("empresa", empresa)
        .param("sinEstados", estados.isEmpty())
        .param("estados", estados.isEmpty() ? List.of("-") : estados)
        .param("proveedor", proveedor)
        .param("almacen", almacen)
        .param("desde", desde)
        .param("hasta", hasta)
        .param("folio", folio)
        .param("limite", limite)
        .param("saltar", saltar)
        .query(FilaResumen.class)
        .list();
  }

  // ------------------------------------------------------------------------------ Escritura

  public long insertar(long empresa, String folio, Cabecera c, long usuario) {
    return jdbc.sql(
            """
            INSERT INTO abastecimiento.pedido
              (empresa_id, folio, proveedor_id, almacen_id, fecha, fecha_entrega, observaciones,
               usuario_captura)
            VALUES (:empresa, :folio, :proveedor, :almacen, :fecha, :entrega, :observaciones,
                    :usuario)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("folio", folio)
        .param("proveedor", c.proveedorId())
        .param("almacen", c.almacenId())
        .param("fecha", c.fecha())
        .param("entrega", c.fechaEntrega())
        .param("observaciones", c.observaciones())
        .param("usuario", usuario)
        .query(Long.class)
        .single();
  }

  public int actualizarCabecera(long empresa, long id, int version, Cabecera c) {
    return jdbc.sql(
            """
            UPDATE abastecimiento.pedido
               SET proveedor_id = :proveedor, almacen_id = :almacen, fecha_entrega = :entrega,
                   observaciones = :observaciones, version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version AND estado = 'borrador'
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("proveedor", c.proveedorId())
        .param("almacen", c.almacenId())
        .param("entrega", c.fechaEntrega())
        .param("observaciones", c.observaciones())
        .update();
  }

  public long insertarRenglon(long empresa, long pedido, NuevoRenglon r) {
    return jdbc.sql(
            """
            INSERT INTO abastecimiento.pedido_linea
              (empresa_id, pedido_id, renglon, material_id, cantidad, cantidad_captura,
               unidad_captura_id, precio, tasa_iva, observaciones)
            VALUES (:empresa, :pedido, :renglon, :material, :cantidad, :captura, :unidad, :precio,
                    :iva, :observaciones)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("pedido", pedido)
        .param("renglon", r.renglon())
        .param("material", r.materialId())
        .param("cantidad", r.cantidad())
        .param("captura", r.cantidadCaptura())
        .param("unidad", r.unidadCapturaId())
        .param("precio", r.precio())
        .param("iva", r.tasaIva())
        .param("observaciones", r.observaciones())
        .query(Long.class)
        .single();
  }

  /** Borra los renglones de un borrador (sus ligas se van en cascada). */
  public void borrarRenglones(long empresa, long pedido) {
    jdbc.sql(
            "DELETE FROM abastecimiento.pedido_linea WHERE empresa_id = :empresa"
                + " AND pedido_id = :pedido")
        .param("empresa", empresa)
        .param("pedido", pedido)
        .update();
  }

  public void ligar(
      long empresa, long pedidoLinea, long requisicionLinea, long material, BigDecimal cantidad) {
    jdbc.sql(
            """
            INSERT INTO abastecimiento.pedido_requisicion
              (empresa_id, pedido_linea_id, requisicion_linea_id, material_id, cantidad)
            VALUES (:empresa, :pedidoLinea, :requisicionLinea, :material, :cantidad)
            """)
        .param("empresa", empresa)
        .param("pedidoLinea", pedidoLinea)
        .param("requisicionLinea", requisicionLinea)
        .param("material", material)
        .param("cantidad", cantidad)
        .update();
  }

  public void sumarRecibido(
      long empresa, long pedidoLinea, long requisicionLinea, BigDecimal cantidad) {
    jdbc.sql(
            """
            UPDATE abastecimiento.pedido_requisicion SET recibido = recibido + :cantidad
             WHERE empresa_id = :empresa AND pedido_linea_id = :pedidoLinea
               AND requisicion_linea_id = :requisicionLinea
            """)
        .param("empresa", empresa)
        .param("pedidoLinea", pedidoLinea)
        .param("requisicionLinea", requisicionLinea)
        .param("cantidad", cantidad)
        .update();
  }

  public void enviar(long empresa, long id, long usuario) {
    jdbc.sql(
            """
            UPDATE abastecimiento.pedido
               SET estado = 'enviado', usuario_envia = :usuario, enviado_en = now(),
                   version = version + 1
             WHERE empresa_id = :empresa AND id = :id
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("usuario", usuario)
        .update();
  }

  public void marcarRecibido(long empresa, long id) {
    jdbc.sql(
            "UPDATE abastecimiento.pedido SET estado = 'recibido', version = version + 1"
                + " WHERE empresa_id = :empresa AND id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .update();
  }

  /** Cierra ({@code cerrado}) o cancela ({@code cancelado}) con el motivo. */
  public void terminar(long empresa, long id, String estado, String motivo) {
    jdbc.sql(
            """
            UPDATE abastecimiento.pedido
               SET estado = :estado, motivo_cierre = :motivo, cerrado_en = now(),
                   version = version + 1
             WHERE empresa_id = :empresa AND id = :id
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("estado", estado)
        .param("motivo", motivo)
        .update();
  }
}

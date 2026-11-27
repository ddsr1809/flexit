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
 * Requisiciones y sus renglones ({@code abastecimiento.requisicion} y {@code requisicion_linea}).
 * Doble filtro por empresa: condición explícita y RLS.
 */
@Repository
public class RepositorioRequisiciones {

  /** Una requisición tal como está en la base. */
  public record FilaRequisicion(
      long id,
      String folio,
      long contratoId,
      long frenteId,
      long almacenId,
      LocalDate fecha,
      LocalDate fechaRequerida,
      boolean urgente,
      String estado,
      long usuarioCaptura,
      Long usuarioAutoriza,
      OffsetDateTime autorizadaEn,
      OffsetDateTime cerradaEn,
      String notaRechazo,
      String motivoCierre,
      String observaciones,
      int version,
      OffsetDateTime creado) {}

  /** Un renglón (un material para un lote). */
  public record FilaRenglon(
      long id,
      long requisicionId,
      int renglon,
      long materialId,
      BigDecimal cantidad,
      BigDecimal cantidadCaptura,
      long unidadCapturaId,
      long contratoId,
      long unidadObraId,
      long paqueteId,
      int clasificacionId,
      String observaciones) {}

  /** Renglón con los datos de su requisición: para faltantes, pedidos y surtido. */
  public record RenglonConRequisicion(
      long id,
      long requisicionId,
      String folio,
      String estado,
      long almacenId,
      LocalDate fechaRequerida,
      boolean urgente,
      int renglon,
      long materialId,
      BigDecimal cantidad,
      long contratoId,
      long unidadObraId,
      long paqueteId,
      int clasificacionId) {}

  /** Renglón de las listas. */
  public record FilaResumen(
      long id,
      String folio,
      String estado,
      long contratoId,
      long almacenId,
      LocalDate fecha,
      LocalDate fechaRequerida,
      boolean urgente,
      long usuarioCaptura,
      Long usuarioAutoriza,
      int renglones,
      OffsetDateTime creado) {}

  /** Lo pedido al proveedor para un renglón de requisición, por pedido. */
  public record FilaCobertura(
      long requisicionLineaId,
      long pedidoId,
      String folio,
      String estado,
      long pedidoLineaId,
      BigDecimal cantidad,
      BigDecimal recibido) {}

  /** Datos de la cabecera al crear o corregir. */
  public record Cabecera(
      long contratoId,
      long frenteId,
      long almacenId,
      LocalDate fecha,
      LocalDate fechaRequerida,
      boolean urgente,
      String observaciones) {}

  /** Renglón nuevo. */
  public record NuevoRenglon(
      int renglon,
      long materialId,
      BigDecimal cantidad,
      BigDecimal cantidadCaptura,
      long unidadCapturaId,
      long unidadObraId,
      long paqueteId,
      int clasificacionId,
      String observaciones) {}

  private static final String REQUISICION =
      """
      SELECT id, folio, contrato_id, frente_id, almacen_id, fecha, fecha_requerida, urgente, estado,
             usuario_captura, usuario_autoriza, autorizada_en, cerrada_en, nota_rechazo,
             motivo_cierre, observaciones, version, creado
        FROM abastecimiento.requisicion
       WHERE empresa_id = :empresa
      """;

  private static final String RENGLON =
      """
      SELECT id, requisicion_id, renglon, material_id, cantidad, cantidad_captura,
             unidad_captura_id, contrato_id, unidad_obra_id, paquete_id, clasificacion_id,
             observaciones
        FROM abastecimiento.requisicion_linea
       WHERE empresa_id = :empresa
      """;

  private static final String CON_REQUISICION =
      """
      SELECT l.id, l.requisicion_id, r.folio, r.estado, r.almacen_id, r.fecha_requerida,
             r.urgente, l.renglon, l.material_id, l.cantidad, l.contrato_id, l.unidad_obra_id,
             l.paquete_id, l.clasificacion_id
        FROM abastecimiento.requisicion_linea l
        JOIN abastecimiento.requisicion r ON r.empresa_id = l.empresa_id AND r.id = l.requisicion_id
       WHERE l.empresa_id = :empresa
      """;

  private final JdbcClient jdbc;

  RepositorioRequisiciones(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------ Lectura

  public Optional<FilaRequisicion> requisicion(long empresa, long id) {
    return jdbc.sql(REQUISICION + " AND id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .query(FilaRequisicion.class)
        .optional();
  }

  /** La requisición con su fila bloqueada hasta el fin de la transacción. */
  public Optional<FilaRequisicion> bloquear(long empresa, long id) {
    return jdbc.sql(REQUISICION + " AND id = :id FOR UPDATE")
        .param("empresa", empresa)
        .param("id", id)
        .query(FilaRequisicion.class)
        .optional();
  }

  public List<FilaRenglon> renglones(long empresa, long requisicion) {
    return jdbc.sql(RENGLON + " AND requisicion_id = :requisicion ORDER BY renglon, id")
        .param("empresa", empresa)
        .param("requisicion", requisicion)
        .query(FilaRenglon.class)
        .list();
  }

  public List<FilaRenglon> renglonesDe(long empresa, Collection<Long> requisiciones) {
    if (requisiciones.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(RENGLON + " AND requisicion_id IN (:ids) ORDER BY requisicion_id, renglon, id")
        .param("empresa", empresa)
        .param("ids", List.copyOf(requisiciones))
        .query(FilaRenglon.class)
        .list();
  }

  /** Renglones por id, con su requisición. */
  public List<RenglonConRequisicion> conRequisicion(long empresa, Collection<Long> lineas) {
    if (lineas.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(CON_REQUISICION + " AND l.id IN (:lineas)" + ORDEN_URGENCIA)
        .param("empresa", empresa)
        .param("lineas", List.copyOf(lineas))
        .query(RenglonConRequisicion.class)
        .list();
  }

  /**
   * Renglones de requisiciones autorizadas, la más urgente primero (urgentes, fecha requerida,
   * folio, renglón). {@code contratos} nulo: todos.
   */
  public List<RenglonConRequisicion> autorizados(long empresa, Collection<Long> contratos) {
    return jdbc.sql(
            CON_REQUISICION
                + """
                   AND r.estado = 'autorizada'
                   AND (:todos OR r.contrato_id IN (:contratos))
                """
                + ORDEN_URGENCIA)
        .param("empresa", empresa)
        .param("todos", contratos == null)
        .param(
            "contratos",
            contratos == null || contratos.isEmpty() ? List.of(-1L) : List.copyOf(contratos))
        .query(RenglonConRequisicion.class)
        .list();
  }

  private static final String ORDEN_URGENCIA =
      " ORDER BY r.urgente DESC, r.fecha_requerida, r.folio, l.renglon, l.id";

  /**
   * Lo pedido a proveedores para esos renglones, en pedidos vivos (borrador o enviado) y en los que
   * ya terminaron, para mostrar.
   */
  public List<FilaCobertura> coberturas(long empresa, Collection<Long> lineas) {
    if (lineas.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT x.requisicion_linea_id, p.id AS pedido_id, p.folio, p.estado,
                   x.pedido_linea_id, x.cantidad, x.recibido
              FROM abastecimiento.pedido_requisicion x
              JOIN abastecimiento.pedido_linea pl
                ON pl.empresa_id = x.empresa_id AND pl.id = x.pedido_linea_id
              JOIN abastecimiento.pedido p ON p.empresa_id = pl.empresa_id AND p.id = pl.pedido_id
             WHERE x.empresa_id = :empresa AND x.requisicion_linea_id IN (:lineas)
             ORDER BY p.fecha, p.id
            """)
        .param("empresa", empresa)
        .param("lineas", List.copyOf(lineas))
        .query(FilaCobertura.class)
        .list();
  }

  /** Requisiciones filtradas, más recientes primero. {@code contratos} nulo: todos. */
  public List<FilaResumen> buscar(
      long empresa,
      List<String> estados,
      Long contrato,
      LocalDate desde,
      LocalDate hasta,
      String folio,
      Collection<Long> contratos,
      Long excluirCapturadosPor,
      int limite,
      long saltar) {
    return jdbc.sql(
            """
            SELECT r.id, r.folio, r.estado, r.contrato_id, r.almacen_id, r.fecha, r.fecha_requerida,
                   r.urgente, r.usuario_captura, r.usuario_autoriza,
                   (SELECT count(DISTINCT l.renglon) FROM abastecimiento.requisicion_linea l
                     WHERE l.empresa_id = r.empresa_id AND l.requisicion_id = r.id)::int AS renglones,
                   r.creado
              FROM abastecimiento.requisicion r
             WHERE r.empresa_id = :empresa
               AND (:sinEstados OR r.estado IN (:estados))
               AND (CAST(:contrato AS bigint) IS NULL OR r.contrato_id = :contrato)
               AND (CAST(:desde AS date) IS NULL OR r.fecha >= :desde)
               AND (CAST(:hasta AS date) IS NULL OR r.fecha <= :hasta)
               AND (CAST(:folio AS text) IS NULL OR r.folio LIKE '%' || upper(:folio) || '%')
               AND (:todos OR r.contrato_id IN (:contratos))
               AND (CAST(:excluir AS bigint) IS NULL OR r.usuario_captura <> :excluir)
             ORDER BY r.fecha DESC, r.id DESC
             LIMIT :limite OFFSET :saltar
            """)
        .param("empresa", empresa)
        .param("sinEstados", estados.isEmpty())
        .param("estados", estados.isEmpty() ? List.of("-") : estados)
        .param("contrato", contrato)
        .param("desde", desde)
        .param("hasta", hasta)
        .param("folio", folio)
        .param("todos", contratos == null)
        .param(
            "contratos",
            contratos == null || contratos.isEmpty() ? List.of(-1L) : List.copyOf(contratos))
        .param("excluir", excluirCapturadosPor)
        .param("limite", limite)
        .param("saltar", saltar)
        .query(FilaResumen.class)
        .list();
  }

  // ------------------------------------------------------------------------------ Escritura

  public long insertar(long empresa, String folio, Cabecera c, long usuario) {
    return jdbc.sql(
            """
            INSERT INTO abastecimiento.requisicion
              (empresa_id, folio, contrato_id, frente_id, almacen_id, fecha, fecha_requerida,
               urgente, observaciones, usuario_captura)
            VALUES (:empresa, :folio, :contrato, :frente, :almacen, :fecha, :requerida, :urgente,
                    :observaciones, :usuario)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("folio", folio)
        .param("contrato", c.contratoId())
        .param("frente", c.frenteId())
        .param("almacen", c.almacenId())
        .param("fecha", c.fecha())
        .param("requerida", c.fechaRequerida())
        .param("urgente", c.urgente())
        .param("observaciones", c.observaciones())
        .param("usuario", usuario)
        .query(Long.class)
        .single();
  }

  /** Corrige la cabecera de un borrador; 0 si la versión ya cambió. El contrato no cambia. */
  public int actualizarCabecera(long empresa, long id, int version, Cabecera c) {
    return jdbc.sql(
            """
            UPDATE abastecimiento.requisicion
               SET fecha_requerida = :requerida, urgente = :urgente, observaciones = :observaciones,
                   nota_rechazo = NULL, version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version
               AND estado = 'borrador'
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("requerida", c.fechaRequerida())
        .param("urgente", c.urgente())
        .param("observaciones", c.observaciones())
        .update();
  }

  public void insertarRenglon(long empresa, long requisicion, long contrato, NuevoRenglon r) {
    jdbc.sql(
            """
            INSERT INTO abastecimiento.requisicion_linea
              (empresa_id, requisicion_id, renglon, material_id, cantidad, cantidad_captura,
               unidad_captura_id, contrato_id, unidad_obra_id, paquete_id, clasificacion_id,
               observaciones)
            VALUES (:empresa, :requisicion, :renglon, :material, :cantidad, :captura, :unidad,
                    :contrato, :unidadObra, :paquete, :clasificacion, :observaciones)
            """)
        .param("empresa", empresa)
        .param("requisicion", requisicion)
        .param("renglon", r.renglon())
        .param("material", r.materialId())
        .param("cantidad", r.cantidad())
        .param("captura", r.cantidadCaptura())
        .param("unidad", r.unidadCapturaId())
        .param("contrato", contrato)
        .param("unidadObra", r.unidadObraId())
        .param("paquete", r.paqueteId())
        .param("clasificacion", r.clasificacionId())
        .param("observaciones", r.observaciones())
        .update();
  }

  public void borrarRenglones(long empresa, long requisicion) {
    jdbc.sql(
            "DELETE FROM abastecimiento.requisicion_linea WHERE empresa_id = :empresa"
                + " AND requisicion_id = :requisicion")
        .param("empresa", empresa)
        .param("requisicion", requisicion)
        .update();
  }

  public void enviar(long empresa, long id) {
    cambiar(
        "UPDATE abastecimiento.requisicion SET estado = 'pendiente', nota_rechazo = NULL,"
            + " version = version + 1 WHERE empresa_id = :empresa AND id = :id",
        empresa,
        id,
        null,
        null);
  }

  public void autorizar(long empresa, long id, long usuario) {
    cambiar(
        "UPDATE abastecimiento.requisicion SET estado = 'autorizada', usuario_autoriza = :usuario,"
            + " autorizada_en = now(), version = version + 1"
            + " WHERE empresa_id = :empresa AND id = :id",
        empresa,
        id,
        usuario,
        null);
  }

  public void rechazar(long empresa, long id, String nota) {
    cambiar(
        "UPDATE abastecimiento.requisicion SET estado = 'borrador', nota_rechazo = :texto,"
            + " version = version + 1 WHERE empresa_id = :empresa AND id = :id",
        empresa,
        id,
        null,
        nota);
  }

  /** Cierra ({@code cerrada}) o cancela ({@code cancelada}) con el motivo. */
  public void terminar(long empresa, long id, String estado, String motivo) {
    jdbc.sql(
            """
            UPDATE abastecimiento.requisicion
               SET estado = :estado, motivo_cierre = :motivo, cerrada_en = now(),
                   version = version + 1
             WHERE empresa_id = :empresa AND id = :id
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("estado", estado)
        .param("motivo", motivo)
        .update();
  }

  private void cambiar(String sql, long empresa, long id, Long usuario, String texto) {
    var consulta = jdbc.sql(sql).param("empresa", empresa).param("id", id);
    if (sql.contains(":usuario")) {
      consulta = consulta.param("usuario", usuario);
    }
    if (sql.contains(":texto")) {
      consulta = consulta.param("texto", texto);
    }
    if (consulta.update() != 1) {
      throw new IllegalStateException("La requisición " + id + " no se actualizó");
    }
  }
}

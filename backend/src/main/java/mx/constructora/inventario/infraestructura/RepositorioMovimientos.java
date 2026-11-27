package mx.constructora.inventario.infraestructura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Documentos del almacén y sus líneas ({@code inventario.movimiento} y {@code movimiento_linea}).
 * Doble filtro por empresa: condición explícita y RLS.
 */
@Repository
public class RepositorioMovimientos {

  /** Un documento tal como está en la base. */
  public record FilaMovimiento(
      long id,
      String folio,
      String tipo,
      String estado,
      LocalDate fecha,
      long almacenId,
      Long almacenDestinoId,
      Long proveedorId,
      String folioFactura,
      BigDecimal flete,
      Long solicitanteId,
      Long movimientoOrigenId,
      Long conteoId,
      Long prestamoId,
      Long archivoId,
      long usuarioCaptura,
      Long usuarioAutoriza,
      Long usuarioAplica,
      OffsetDateTime autorizadoEn,
      OffsetDateTime aplicadoEn,
      OffsetDateTime canceladoEn,
      String motivo,
      String notaRechazo,
      String motivoCancelacion,
      String observaciones,
      int version,
      OffsetDateTime creado) {}

  /** Una línea tal como está en la base. */
  public record FilaLinea(
      long id,
      long movimientoId,
      String tipo,
      int renglon,
      long materialId,
      BigDecimal cantidad,
      BigDecimal cantidadCaptura,
      long unidadCapturaId,
      BigDecimal precioCaptura,
      BigDecimal descuento,
      BigDecimal tasaIva,
      BigDecimal costoNuevo,
      BigDecimal costoUnitario,
      BigDecimal importe,
      Long contratoId,
      Long unidadObraId,
      Long paqueteId,
      Integer clasificacionId,
      Long lineaOrigenId,
      Long requisicionLineaId,
      Long pedidoLineaId) {}

  /** Línea con datos de su documento: para validar devoluciones, reasignaciones y ajustes. */
  public record LineaConDocumento(
      long id,
      long movimientoId,
      String folio,
      String tipo,
      String estado,
      long almacenId,
      Long proveedorId,
      long materialId,
      BigDecimal cantidad,
      BigDecimal costoUnitario,
      BigDecimal importe,
      Long contratoId,
      Long unidadObraId,
      Long paqueteId,
      Integer clasificacionId) {}

  /** Lo que conserva una línea base (vale o reasignación): suma de sus cargos. */
  public record Restante(BigDecimal cantidad, BigDecimal importe) {}

  /** Lo que conserva un vale posterior a una recepción, con su destino. */
  public record Consumo(
      long lineaBaseId,
      BigDecimal cantidad,
      long contratoId,
      long unidadObraId,
      long paqueteId,
      int clasificacionId) {}

  /** Renglón de las listas de documentos; {@code contratos}: ids separados por coma. */
  public record FilaResumen(
      long id,
      String folio,
      String tipo,
      String estado,
      LocalDate fecha,
      long almacenId,
      Long almacenDestinoId,
      long usuarioCaptura,
      Long usuarioAutoriza,
      Long solicitanteId,
      int lineas,
      BigDecimal importe,
      String contratos,
      OffsetDateTime creado) {}

  private static final String MOVIMIENTO =
      """
      SELECT id, folio, tipo, estado, fecha, almacen_id, almacen_destino_id, proveedor_id,
             folio_factura, flete, solicitante_id, movimiento_origen_id, conteo_id, prestamo_id,
             archivo_id, usuario_captura, usuario_autoriza, usuario_aplica, autorizado_en,
             aplicado_en, cancelado_en, motivo, nota_rechazo, motivo_cancelacion, observaciones,
             version, creado
        FROM inventario.movimiento
       WHERE empresa_id = :empresa
      """;

  private static final String LINEA =
      """
      SELECT id, movimiento_id, tipo, renglon, material_id, cantidad, cantidad_captura,
             unidad_captura_id, precio_captura, descuento, tasa_iva, costo_nuevo, costo_unitario,
             importe, contrato_id, unidad_obra_id, paquete_id, clasificacion_id, linea_origen_id,
             requisicion_linea_id, pedido_linea_id
        FROM inventario.movimiento_linea
       WHERE empresa_id = :empresa
      """;

  private final JdbcClient jdbc;

  RepositorioMovimientos(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------ Lectura

  public Optional<FilaMovimiento> movimiento(long empresa, long id) {
    return jdbc.sql(MOVIMIENTO + " AND id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .query(FilaMovimiento.class)
        .optional();
  }

  /** El documento con su fila bloqueada hasta el fin de la transacción. */
  public Optional<FilaMovimiento> bloquear(long empresa, long id) {
    return jdbc.sql(MOVIMIENTO + " AND id = :id FOR UPDATE")
        .param("empresa", empresa)
        .param("id", id)
        .query(FilaMovimiento.class)
        .optional();
  }

  public Optional<Long> porIdempotencia(long empresa, UUID clave) {
    return jdbc.sql(
            "SELECT id FROM inventario.movimiento WHERE empresa_id = :empresa AND idempotencia ="
                + " :clave")
        .param("empresa", empresa)
        .param("clave", clave)
        .query(Long.class)
        .optional();
  }

  public List<FilaLinea> lineas(long empresa, long movimiento) {
    return jdbc.sql(LINEA + " AND movimiento_id = :movimiento ORDER BY renglon, id")
        .param("empresa", empresa)
        .param("movimiento", movimiento)
        .query(FilaLinea.class)
        .list();
  }

  public Optional<LineaConDocumento> lineaConDocumento(long empresa, long linea, boolean bloquear) {
    return jdbc.sql(
            """
            SELECT l.id, l.movimiento_id, m.folio, m.tipo, m.estado, m.almacen_id, m.proveedor_id,
                   l.material_id, l.cantidad, l.costo_unitario, l.importe, l.contrato_id,
                   l.unidad_obra_id, l.paquete_id, l.clasificacion_id
              FROM inventario.movimiento_linea l
              JOIN inventario.movimiento m ON m.empresa_id = l.empresa_id AND m.id = l.movimiento_id
             WHERE l.empresa_id = :empresa AND l.id = :linea
            """
                + (bloquear ? " FOR UPDATE OF l" : ""))
        .param("empresa", empresa)
        .param("linea", linea)
        .query(LineaConDocumento.class)
        .optional();
  }

  /** Suma de los cargos de una línea base: lo que queda de ella en obra. */
  public Restante restante(long empresa, long lineaBase) {
    return jdbc.sql(
            """
            SELECT coalesce(sum(cantidad), 0) AS cantidad, coalesce(sum(importe), 0) AS importe
              FROM inventario.cargo
             WHERE empresa_id = :empresa AND linea_base_id = :base
            """)
        .param("empresa", empresa)
        .param("base", lineaBase)
        .query(Restante.class)
        .single();
  }

  /** Restante de varias líneas base a la vez (las que no tienen cargos no aparecen). */
  public List<RestanteDeLinea> restantes(long empresa, Collection<Long> lineasBase) {
    if (lineasBase.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT linea_base_id AS linea_id, sum(cantidad) AS cantidad, sum(importe) AS importe
              FROM inventario.cargo
             WHERE empresa_id = :empresa AND linea_base_id IN (:bases)
             GROUP BY linea_base_id
            """)
        .param("empresa", empresa)
        .param("bases", List.copyOf(lineasBase))
        .query(RestanteDeLinea.class)
        .list();
  }

  /** Restante de una línea base. */
  public record RestanteDeLinea(long lineaId, BigDecimal cantidad, BigDecimal importe) {}

  /** Cantidad ya devuelta al proveedor de una línea de recepción (documentos aplicados). */
  public BigDecimal devueltoAProveedor(long empresa, long lineaEntrada) {
    return jdbc.sql(
            """
            SELECT coalesce(sum(l.cantidad), 0)
              FROM inventario.movimiento_linea l
              JOIN inventario.movimiento m ON m.empresa_id = l.empresa_id AND m.id = l.movimiento_id
             WHERE l.empresa_id = :empresa AND l.linea_origen_id = :linea
               AND l.tipo = 'devolucion_proveedor' AND m.estado = 'aplicado'
            """)
        .param("empresa", empresa)
        .param("linea", lineaEntrada)
        .query(BigDecimal.class)
        .single();
  }

  /** Costo vigente de una línea de recepción: el del último ajuste de costo aplicado, si hay. */
  public Optional<BigDecimal> ultimoCostoAjustado(long empresa, long lineaEntrada) {
    return jdbc.sql(
            """
            SELECT l.costo_nuevo
              FROM inventario.movimiento_linea l
              JOIN inventario.movimiento m ON m.empresa_id = l.empresa_id AND m.id = l.movimiento_id
             WHERE l.empresa_id = :empresa AND l.linea_origen_id = :linea
               AND l.tipo = 'ajuste_costo' AND m.estado = 'aplicado'
             ORDER BY m.aplicado_en DESC, l.id DESC
             LIMIT 1
            """)
        .param("empresa", empresa)
        .param("linea", lineaEntrada)
        .query(BigDecimal.class)
        .optional();
  }

  /**
   * Vales del material que salieron del almacén después del renglón de kardex {@code desde} (la
   * recepción) y que todavía conservan cantidad en obra.
   */
  public List<Consumo> consumosPosteriores(long empresa, long almacen, long material, long desde) {
    return jdbc.sql(
            """
            SELECT l.id AS linea_base_id, sum(c.cantidad) AS cantidad, l.contrato_id,
                   l.unidad_obra_id, l.paquete_id, l.clasificacion_id
              FROM inventario.kardex k
              JOIN inventario.movimiento_linea l ON l.empresa_id = k.empresa_id AND l.id = k.linea_id
              JOIN inventario.cargo c ON c.empresa_id = l.empresa_id AND c.linea_base_id = l.id
             WHERE k.empresa_id = :empresa AND k.almacen_id = :almacen AND k.material_id = :material
               AND k.id > :desde AND l.tipo = 'salida'
             GROUP BY l.id
            HAVING sum(c.cantidad) > 0
             ORDER BY l.id
            """)
        .param("empresa", empresa)
        .param("almacen", almacen)
        .param("material", material)
        .param("desde", desde)
        .query(Consumo.class)
        .list();
  }

  /** Renglón de kardex con que entró una línea de recepción. */
  public Optional<Long> kardexDeLinea(long empresa, long linea) {
    return jdbc
        .sql(
            "SELECT min(id) FROM inventario.kardex WHERE empresa_id = :empresa AND linea_id ="
                + " :linea")
        .param("empresa", empresa)
        .param("linea", linea)
        .query(Long.class)
        .list()
        .stream()
        .filter(java.util.Objects::nonNull)
        .findFirst();
  }

  /**
   * Documentos filtrados, más recientes primero. {@code contratos}: si no es nulo, solo los que
   * tienen alguna línea en esos contratos (alcance del residente).
   */
  public List<FilaResumen> buscar(
      long empresa,
      String tipo,
      List<String> estados,
      Long almacen,
      LocalDate desde,
      LocalDate hasta,
      String folio,
      Collection<Long> contratos,
      Long excluirCapturadosPor,
      int limite,
      long saltar) {
    return jdbc.sql(
            """
            SELECT m.id, m.folio, m.tipo, m.estado, m.fecha, m.almacen_id, m.almacen_destino_id,
                   m.usuario_captura, m.usuario_autoriza, m.solicitante_id,
                   (SELECT count(*) FROM inventario.movimiento_linea l
                     WHERE l.empresa_id = m.empresa_id AND l.movimiento_id = m.id)::int AS lineas,
                   (SELECT sum(l.importe) FROM inventario.movimiento_linea l
                     WHERE l.empresa_id = m.empresa_id AND l.movimiento_id = m.id) AS importe,
                   (SELECT string_agg(DISTINCT l.contrato_id::text, ',')
                      FROM inventario.movimiento_linea l
                     WHERE l.empresa_id = m.empresa_id AND l.movimiento_id = m.id) AS contratos,
                   m.creado
              FROM inventario.movimiento m
             WHERE m.empresa_id = :empresa
               AND (CAST(:tipo AS text) IS NULL OR m.tipo = :tipo)
               AND (:sinEstados OR m.estado IN (:estados))
               AND (CAST(:almacen AS bigint) IS NULL OR m.almacen_id = :almacen
                    OR m.almacen_destino_id = :almacen)
               AND (CAST(:desde AS date) IS NULL OR m.fecha >= :desde)
               AND (CAST(:hasta AS date) IS NULL OR m.fecha <= :hasta)
               AND (CAST(:folio AS text) IS NULL OR m.folio LIKE '%' || upper(:folio) || '%')
               AND (:todos OR EXISTS (SELECT 1 FROM inventario.movimiento_linea l
                                        WHERE l.empresa_id = m.empresa_id AND l.movimiento_id = m.id
                                          AND l.contrato_id IN (:contratos)))
               AND (CAST(:excluir AS bigint) IS NULL OR m.usuario_captura <> :excluir)
             ORDER BY m.fecha DESC, m.id DESC
             LIMIT :limite OFFSET :saltar
            """)
        .param("empresa", empresa)
        .param("tipo", tipo)
        .param("sinEstados", estados.isEmpty())
        .param("estados", estados.isEmpty() ? List.of("-") : estados)
        .param("almacen", almacen)
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

  /** Datos de la cabecera al crear o reemplazar un borrador. */
  public record Cabecera(
      String tipo,
      LocalDate fecha,
      long almacenId,
      Long almacenDestinoId,
      Long proveedorId,
      String folioFactura,
      BigDecimal flete,
      Long solicitanteId,
      Long movimientoOrigenId,
      Long conteoId,
      Long prestamoId,
      Long archivoId,
      String motivo,
      String observaciones) {}

  /**
   * Datos de una línea nueva. {@code requisicionLineaId} (vale) y {@code pedidoLineaId} (recepción)
   * los pone solo el abastecimiento, por {@code InventarioApi}.
   */
  public record NuevaLinea(
      int renglon,
      long materialId,
      BigDecimal cantidad,
      BigDecimal cantidadCaptura,
      long unidadCapturaId,
      BigDecimal precioCaptura,
      BigDecimal descuento,
      BigDecimal tasaIva,
      BigDecimal costoNuevo,
      Long contratoId,
      Long unidadObraId,
      Long paqueteId,
      Integer clasificacionId,
      Long lineaOrigenId,
      Long requisicionLineaId,
      Long pedidoLineaId) {

    /** Línea sin vínculo con el abastecimiento. */
    public NuevaLinea(
        int renglon,
        long materialId,
        BigDecimal cantidad,
        BigDecimal cantidadCaptura,
        long unidadCapturaId,
        BigDecimal precioCaptura,
        BigDecimal descuento,
        BigDecimal tasaIva,
        BigDecimal costoNuevo,
        Long contratoId,
        Long unidadObraId,
        Long paqueteId,
        Integer clasificacionId,
        Long lineaOrigenId) {
      this(
          renglon,
          materialId,
          cantidad,
          cantidadCaptura,
          unidadCapturaId,
          precioCaptura,
          descuento,
          tasaIva,
          costoNuevo,
          contratoId,
          unidadObraId,
          paqueteId,
          clasificacionId,
          lineaOrigenId,
          null,
          null);
    }
  }

  public long insertar(long empresa, String folio, Cabecera c, long usuario, UUID idempotencia) {
    return jdbc.sql(
            """
            INSERT INTO inventario.movimiento
              (empresa_id, folio, tipo, fecha, almacen_id, almacen_destino_id, proveedor_id,
               folio_factura, flete, solicitante_id, movimiento_origen_id, conteo_id, prestamo_id,
               archivo_id, motivo, observaciones, usuario_captura, idempotencia)
            VALUES (:empresa, :folio, :tipo, :fecha, :almacen, :destino, :proveedor, :factura,
                    :flete, :solicitante, :origen, :conteo, :prestamo, :archivo, :motivo,
                    :observaciones, :usuario, :idempotencia)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("folio", folio)
        .param("tipo", c.tipo())
        .param("fecha", c.fecha())
        .param("almacen", c.almacenId())
        .param("destino", c.almacenDestinoId())
        .param("proveedor", c.proveedorId())
        .param("factura", c.folioFactura())
        .param("flete", c.flete())
        .param("solicitante", c.solicitanteId())
        .param("origen", c.movimientoOrigenId())
        .param("conteo", c.conteoId())
        .param("prestamo", c.prestamoId())
        .param("archivo", c.archivoId())
        .param("motivo", c.motivo())
        .param("observaciones", c.observaciones())
        .param("usuario", usuario)
        .param("idempotencia", idempotencia)
        .query(Long.class)
        .single();
  }

  /** Reemplaza la cabecera de un borrador; 0 si la versión ya cambió. */
  public int actualizarCabecera(long empresa, long id, int version, Cabecera c) {
    return jdbc.sql(
            """
            UPDATE inventario.movimiento
               SET fecha = :fecha, almacen_id = :almacen, almacen_destino_id = :destino,
                   proveedor_id = :proveedor, folio_factura = :factura, flete = :flete,
                   solicitante_id = :solicitante, movimiento_origen_id = :origen,
                   archivo_id = :archivo, motivo = :motivo, observaciones = :observaciones,
                   nota_rechazo = NULL, version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version AND estado = 'borrador'
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("fecha", c.fecha())
        .param("almacen", c.almacenId())
        .param("destino", c.almacenDestinoId())
        .param("proveedor", c.proveedorId())
        .param("factura", c.folioFactura())
        .param("flete", c.flete())
        .param("solicitante", c.solicitanteId())
        .param("origen", c.movimientoOrigenId())
        .param("archivo", c.archivoId())
        .param("motivo", c.motivo())
        .param("observaciones", c.observaciones())
        .update();
  }

  public void insertarLinea(long empresa, long movimiento, String tipo, NuevaLinea l) {
    jdbc.sql(
            """
            INSERT INTO inventario.movimiento_linea
              (empresa_id, movimiento_id, tipo, renglon, material_id, cantidad, cantidad_captura,
               unidad_captura_id, precio_captura, descuento, tasa_iva, costo_nuevo, contrato_id,
               unidad_obra_id, paquete_id, clasificacion_id, linea_origen_id, requisicion_linea_id,
               pedido_linea_id)
            VALUES (:empresa, :movimiento, :tipo, :renglon, :material, :cantidad, :captura,
                    :unidad, :precio, :descuento, :iva, :costoNuevo, :contrato, :unidadObra,
                    :paquete, :clasificacion, :origen, :requisicionLinea, :pedidoLinea)
            """)
        .param("empresa", empresa)
        .param("movimiento", movimiento)
        .param("tipo", tipo)
        .param("renglon", l.renglon())
        .param("material", l.materialId())
        .param("cantidad", l.cantidad())
        .param("captura", l.cantidadCaptura())
        .param("unidad", l.unidadCapturaId())
        .param("precio", l.precioCaptura())
        .param("descuento", l.descuento())
        .param("iva", l.tasaIva())
        .param("costoNuevo", l.costoNuevo())
        .param("contrato", l.contratoId())
        .param("unidadObra", l.unidadObraId())
        .param("paquete", l.paqueteId())
        .param("clasificacion", l.clasificacionId())
        .param("origen", l.lineaOrigenId())
        .param("requisicionLinea", l.requisicionLineaId())
        .param("pedidoLinea", l.pedidoLineaId())
        .update();
  }

  public void borrarLineas(long empresa, long movimiento) {
    jdbc.sql(
            "DELETE FROM inventario.movimiento_linea WHERE empresa_id = :empresa AND movimiento_id"
                + " = :movimiento")
        .param("empresa", empresa)
        .param("movimiento", movimiento)
        .update();
  }

  /** Congela costo e importe de una línea al aplicar. */
  public void congelarCosto(long empresa, long linea, BigDecimal costo, BigDecimal importe) {
    jdbc.sql(
            """
            UPDATE inventario.movimiento_linea SET costo_unitario = :costo, importe = :importe
             WHERE empresa_id = :empresa AND id = :linea
            """)
        .param("empresa", empresa)
        .param("linea", linea)
        .param("costo", costo)
        .param("importe", importe)
        .update();
  }

  public void enviar(long empresa, long id) {
    cambiar(
        "UPDATE inventario.movimiento SET estado = 'pendiente', nota_rechazo = NULL,"
            + " version = version + 1 WHERE empresa_id = :empresa AND id = :id",
        empresa,
        id,
        null,
        null);
  }

  public void autorizar(long empresa, long id, long usuario) {
    cambiar(
        "UPDATE inventario.movimiento SET estado = 'autorizado', usuario_autoriza = :usuario,"
            + " autorizado_en = now(), version = version + 1"
            + " WHERE empresa_id = :empresa AND id = :id",
        empresa,
        id,
        usuario,
        null);
  }

  public void rechazar(long empresa, long id, String nota) {
    cambiar(
        "UPDATE inventario.movimiento SET estado = 'borrador', nota_rechazo = :texto,"
            + " version = version + 1 WHERE empresa_id = :empresa AND id = :id",
        empresa,
        id,
        null,
        nota);
  }

  public void aplicar(long empresa, long id, long usuario) {
    cambiar(
        "UPDATE inventario.movimiento SET estado = 'aplicado', usuario_aplica = :usuario,"
            + " aplicado_en = now(), version = version + 1"
            + " WHERE empresa_id = :empresa AND id = :id",
        empresa,
        id,
        usuario,
        null);
  }

  public void cancelar(long empresa, long id, String motivo) {
    cambiar(
        "UPDATE inventario.movimiento SET estado = 'cancelado', motivo_cancelacion = :texto,"
            + " cancelado_en = now(), version = version + 1"
            + " WHERE empresa_id = :empresa AND id = :id",
        empresa,
        id,
        null,
        motivo);
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
      throw new IllegalStateException("El movimiento " + id + " no se actualizó");
    }
  }
}

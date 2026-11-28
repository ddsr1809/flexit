package mx.constructora.inventario.infraestructura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Aperturas de almacén (H8): el documento, sus préstamos, sus costos capturados y lo firmado. */
@Repository
public class RepositorioAperturas {

  /** Apertura tal como está en la base. */
  public record FilaApertura(
      long id,
      String folio,
      long almacenId,
      long conteoId,
      LocalDate fecha,
      String estado,
      long usuarioCrea,
      Long firmaCoordinacion,
      OffsetDateTime firmaCoordinacionEn,
      Long firmaAdministracion,
      OffsetDateTime firmaAdministracionEn,
      Long movimientoId,
      BigDecimal importe,
      OffsetDateTime canceladoEn,
      String motivoCancelacion,
      String observaciones,
      int version,
      OffsetDateTime creado) {}

  /** Préstamo abierto al corte. */
  public record FilaPrestamoApertura(
      long id,
      long aperturaId,
      Long origenId,
      Integer filaExcel,
      String articulo,
      LocalDate fecha,
      BigDecimal cantidad,
      String responsableTexto,
      Long materialId,
      Long responsableId,
      String estado,
      String motivo,
      Long prestamoId,
      int version) {}

  /** Costo capturado por administración. */
  public record FilaCosto(
      long materialId,
      BigDecimal costoUnitario,
      String nota,
      long usuarioId,
      OffsetDateTime capturadoEn) {}

  /** Lo que se firmó de un material. */
  public record FilaLinea(
      long materialId,
      BigDecimal contado,
      BigDecimal prestado,
      BigDecimal costoUnitario,
      String origenCosto,
      BigDecimal importe,
      BigDecimal existenciaExcel) {}

  private static final String APERTURA =
      """
      SELECT id, folio, almacen_id, conteo_id, fecha, estado, usuario_crea, firma_coordinacion,
             firma_coordinacion_en, firma_administracion, firma_administracion_en, movimiento_id,
             importe, cancelado_en, motivo_cancelacion, observaciones, version, creado
        FROM inventario.apertura
       WHERE empresa_id = :empresa
      """;

  private static final String PRESTAMO =
      """
      SELECT id, apertura_id, origen_id, fila_excel, articulo, fecha, cantidad, responsable_texto,
             material_id, responsable_id, estado, motivo, prestamo_id, version
        FROM inventario.apertura_prestamo
       WHERE empresa_id = :empresa
      """;

  private final JdbcClient jdbc;

  RepositorioAperturas(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // -------------------------------------------------------------------------------- apertura

  public List<FilaApertura> aperturas(long empresa) {
    return jdbc.sql(
            APERTURA + " ORDER BY estado IN ('abierta', 'en_firma') DESC, fecha DESC, id DESC")
        .param("empresa", empresa)
        .query(FilaApertura.class)
        .list();
  }

  public Optional<FilaApertura> apertura(long empresa, long id, boolean bloquear) {
    return jdbc.sql(APERTURA + " AND id = :id" + (bloquear ? " FOR UPDATE" : ""))
        .param("empresa", empresa)
        .param("id", id)
        .query(FilaApertura.class)
        .optional();
  }

  /** La apertura a la que pertenece un conteo, si es de una. */
  public Optional<FilaApertura> deConteo(long empresa, long conteo) {
    return jdbc.sql(APERTURA + " AND conteo_id = :conteo")
        .param("empresa", empresa)
        .param("conteo", conteo)
        .query(FilaApertura.class)
        .optional();
  }

  /** La apertura vigente (no cancelada) del almacén. */
  public Optional<FilaApertura> vigente(long empresa, long almacen) {
    return jdbc.sql(APERTURA + " AND almacen_id = :almacen AND estado <> 'cancelada'")
        .param("empresa", empresa)
        .param("almacen", almacen)
        .query(FilaApertura.class)
        .optional();
  }

  /** {@code true} si el almacén ya movió algo (tiene kardex). */
  public boolean tieneKardex(long empresa, long almacen) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM inventario.kardex"
                + " WHERE empresa_id = :empresa AND almacen_id = :almacen)")
        .param("empresa", empresa)
        .param("almacen", almacen)
        .query(Boolean.class)
        .single();
  }

  public long insertar(
      long empresa,
      String folio,
      long almacen,
      long conteo,
      LocalDate fecha,
      long usuario,
      String observaciones) {
    return jdbc.sql(
            """
            INSERT INTO inventario.apertura
              (empresa_id, folio, almacen_id, conteo_id, fecha, usuario_crea, observaciones)
            VALUES (:empresa, :folio, :almacen, :conteo, :fecha, :usuario, :observaciones)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("folio", folio)
        .param("almacen", almacen)
        .param("conteo", conteo)
        .param("fecha", fecha)
        .param("usuario", usuario)
        .param("observaciones", observaciones)
        .query(Long.class)
        .single();
  }

  /** Pone una firma; con la primera, la apertura pasa a firma con el importe congelado. */
  public void firmar(
      long empresa, long id, boolean coordinacion, long usuario, BigDecimal importe) {
    String columna = coordinacion ? "firma_coordinacion" : "firma_administracion";
    jdbc.sql(
            """
            UPDATE inventario.apertura
               SET estado = 'en_firma', %1$s = :usuario, %1$s_en = now(),
                   importe = coalesce(importe, :importe), version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND estado IN ('abierta', 'en_firma')
               AND %1$s IS NULL
            """
                .formatted(columna))
        .param("empresa", empresa)
        .param("id", id)
        .param("usuario", usuario)
        .param("importe", importe)
        .update();
  }

  public void retirarFirmas(long empresa, long id) {
    jdbc.sql(
            """
            UPDATE inventario.apertura
               SET estado = 'abierta', firma_coordinacion = NULL, firma_coordinacion_en = NULL,
                   firma_administracion = NULL, firma_administracion_en = NULL, importe = NULL,
                   version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND estado = 'en_firma'
            """)
        .param("empresa", empresa)
        .param("id", id)
        .update();
    jdbc.sql(
            "DELETE FROM inventario.apertura_linea WHERE empresa_id = :empresa"
                + " AND apertura_id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .update();
  }

  public void aplicar(long empresa, long id, long movimiento) {
    jdbc.sql(
            """
            UPDATE inventario.apertura
               SET estado = 'aplicada', movimiento_id = :movimiento, version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND estado = 'en_firma'
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("movimiento", movimiento)
        .update();
  }

  public void cancelar(long empresa, long id, String motivo) {
    jdbc.sql(
            """
            UPDATE inventario.apertura
               SET estado = 'cancelada', cancelado_en = now(), motivo_cancelacion = :motivo,
                   version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND estado IN ('abierta', 'en_firma')
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("motivo", motivo)
        .update();
  }

  // ------------------------------------------------------------------------------- préstamos

  public List<FilaPrestamoApertura> prestamos(long empresa, long apertura) {
    return jdbc.sql(PRESTAMO + " AND apertura_id = :apertura ORDER BY fila_excel NULLS LAST, id")
        .param("empresa", empresa)
        .param("apertura", apertura)
        .query(FilaPrestamoApertura.class)
        .list();
  }

  public Optional<FilaPrestamoApertura> prestamo(long empresa, long apertura, long id) {
    return jdbc.sql(PRESTAMO + " AND apertura_id = :apertura AND id = :id FOR UPDATE")
        .param("empresa", empresa)
        .param("apertura", apertura)
        .param("id", id)
        .query(FilaPrestamoApertura.class)
        .optional();
  }

  /** Agrega un préstamo; uno del Excel que ya estaba no se repite. */
  public void insertarPrestamo(
      long empresa,
      long apertura,
      Long origen,
      Integer filaExcel,
      String articulo,
      LocalDate fecha,
      BigDecimal cantidad,
      String responsableTexto,
      Long material,
      Long responsable) {
    jdbc.sql(
            """
            INSERT INTO inventario.apertura_prestamo
              (empresa_id, apertura_id, origen_id, fila_excel, articulo, fecha, cantidad,
               responsable_texto, material_id, responsable_id)
            VALUES (:empresa, :apertura, :origen, :fila, :articulo, :fecha, :cantidad,
                    :responsableTexto, :material, :responsable)
            ON CONFLICT (empresa_id, apertura_id, origen_id) WHERE origen_id IS NOT NULL DO NOTHING
            """)
        .param("empresa", empresa)
        .param("apertura", apertura)
        .param("origen", origen)
        .param("fila", filaExcel)
        .param("articulo", articulo)
        .param("fecha", fecha)
        .param("cantidad", cantidad)
        .param("responsableTexto", responsableTexto)
        .param("material", material)
        .param("responsable", responsable)
        .update();
  }

  public int cambiarPrestamo(
      long empresa,
      long id,
      int version,
      Long material,
      Long responsable,
      BigDecimal cantidad,
      String estado,
      String motivo) {
    return jdbc.sql(
            """
            UPDATE inventario.apertura_prestamo
               SET material_id = :material, responsable_id = :responsable, cantidad = :cantidad,
                   estado = :estado, motivo = :motivo, version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("material", material)
        .param("responsable", responsable)
        .param("cantidad", cantidad)
        .param("estado", estado)
        .param("motivo", motivo)
        .update();
  }

  public void ligarPrestamo(long empresa, long id, long prestamo) {
    jdbc.sql(
            """
            UPDATE inventario.apertura_prestamo SET prestamo_id = :prestamo, version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND prestamo_id IS NULL
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("prestamo", prestamo)
        .update();
  }

  // ---------------------------------------------------------------------------------- costos

  public List<FilaCosto> costos(long empresa, long apertura) {
    return jdbc.sql(
            """
            SELECT material_id, costo_unitario, nota, usuario_id, capturado_en
              FROM inventario.apertura_costo
             WHERE empresa_id = :empresa AND apertura_id = :apertura
            """)
        .param("empresa", empresa)
        .param("apertura", apertura)
        .query(FilaCosto.class)
        .list();
  }

  public void fijarCosto(
      long empresa, long apertura, long material, BigDecimal costo, String nota, long usuario) {
    jdbc.sql(
            """
            INSERT INTO inventario.apertura_costo
              (empresa_id, apertura_id, material_id, costo_unitario, nota, usuario_id)
            VALUES (:empresa, :apertura, :material, :costo, :nota, :usuario)
            ON CONFLICT (apertura_id, material_id) DO UPDATE
               SET costo_unitario = EXCLUDED.costo_unitario, nota = EXCLUDED.nota,
                   usuario_id = EXCLUDED.usuario_id, capturado_en = now()
            """)
        .param("empresa", empresa)
        .param("apertura", apertura)
        .param("material", material)
        .param("costo", costo)
        .param("nota", nota)
        .param("usuario", usuario)
        .update();
  }

  public void quitarCosto(long empresa, long apertura, long material) {
    jdbc.sql(
            """
            DELETE FROM inventario.apertura_costo
             WHERE empresa_id = :empresa AND apertura_id = :apertura AND material_id = :material
            """)
        .param("empresa", empresa)
        .param("apertura", apertura)
        .param("material", material)
        .update();
  }

  // -------------------------------------------------------------------------------- firmado

  public List<FilaLinea> lineas(long empresa, long apertura) {
    return jdbc.sql(
            """
            SELECT material_id, contado, prestado, costo_unitario, origen_costo, importe,
                   existencia_excel
              FROM inventario.apertura_linea
             WHERE empresa_id = :empresa AND apertura_id = :apertura
             ORDER BY material_id
            """)
        .param("empresa", empresa)
        .param("apertura", apertura)
        .query(FilaLinea.class)
        .list();
  }

  public void insertarLineas(long empresa, long apertura, List<FilaLinea> lineas) {
    for (FilaLinea l : lineas) {
      jdbc.sql(
              """
              INSERT INTO inventario.apertura_linea
                (empresa_id, apertura_id, material_id, contado, prestado, costo_unitario,
                 origen_costo, importe, existencia_excel)
              VALUES (:empresa, :apertura, :material, :contado, :prestado, :costo, :origen,
                      :importe, :excel)
              """)
          .param("empresa", empresa)
          .param("apertura", apertura)
          .param("material", l.materialId())
          .param("contado", l.contado())
          .param("prestado", l.prestado())
          .param("costo", l.costoUnitario())
          .param("origen", l.origenCosto())
          .param("importe", l.importe())
          .param("excel", l.existenciaExcel())
          .update();
    }
  }
}

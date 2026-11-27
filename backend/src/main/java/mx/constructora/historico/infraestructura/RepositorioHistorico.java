package mx.constructora.historico.infraestructura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import mx.constructora.historico.Registros.AltaImss;
import mx.constructora.historico.Registros.Asignacion;
import mx.constructora.historico.Registros.AsistenciaDia;
import mx.constructora.historico.Registros.Entrada;
import mx.constructora.historico.Registros.EntradaValorada;
import mx.constructora.historico.Registros.Lote;
import mx.constructora.historico.Registros.PagoDestajo;
import mx.constructora.historico.Registros.Prestamo;
import mx.constructora.historico.Registros.RequisicionLinea;
import mx.constructora.historico.Registros.Salida;
import mx.constructora.historico.Registros.Traza;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Tablas del esquema {@code historico}. Se escriben por lotes (una carga de salidas son más de
 * 4,000 renglones); los pagos de destajo se leen de vuelta por carga, número y parcial para ligar a
 * sus trabajadores.
 */
@Repository
public class RepositorioHistorico {

  private static final String TRAZA = "calidad, carga_id, numero, hoja, fila_excel";
  private static final String VALORES_TRAZA = ":calidad, :carga, :numero, :hoja, :filaExcel";

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate lotes;

  RepositorioHistorico(JdbcClient jdbc, NamedParameterJdbcTemplate lotes) {
    this.jdbc = jdbc;
    this.lotes = lotes;
  }

  public Map<String, Long> guardar(long empresa, long carga, Lote lote) {
    Map<String, Long> escritos = new LinkedHashMap<>();
    insertar(
        escritos,
        "entrada",
        """
        INSERT INTO historico.entrada (empresa_id, fecha, semana_texto, material_id, cantidad,
          precio_unitario, descuento, importe, tasa_iva, precio_estimado, proveedor_id,
          proveedor_texto, folio, recibe_id, recibe_texto, observaciones, %s)
        VALUES (:empresa, :fecha, :semanaTexto, :materialId, :cantidad, :precioUnitario,
          :descuento, :importe, :tasaIva, :precioEstimado, :proveedorId, :proveedorTexto, :folio,
          :recibeId, :recibeTexto, :observaciones, %s)
        """,
        empresa,
        carga,
        lote.entradas,
        Entrada::traza,
        (e, p) ->
            p.addValue("fecha", e.fecha())
                .addValue("semanaTexto", e.semanaTexto())
                .addValue("materialId", e.materialId())
                .addValue("cantidad", e.cantidad())
                .addValue("precioUnitario", e.precioUnitario())
                .addValue("descuento", e.descuento())
                .addValue("importe", e.importe())
                .addValue("tasaIva", e.tasaIva())
                .addValue("precioEstimado", e.precioEstimado())
                .addValue("proveedorId", e.proveedorId())
                .addValue("proveedorTexto", e.proveedorTexto())
                .addValue("folio", e.folio())
                .addValue("recibeId", e.recibeId())
                .addValue("recibeTexto", e.recibeTexto())
                .addValue("observaciones", e.observaciones()));
    insertar(
        escritos,
        "salida",
        """
        INSERT INTO historico.salida (empresa_id, fecha, tipo, documento, folio_excel, material_id,
          cantidad, frente_id, contrato_id, unidad_obra_id, lotes_texto, paquete_id, paquete_texto,
          clasificacion_id, solicitante_id, solicitante_texto, entregar_a_texto, entregado,
          costo_unitario, importe, costo_estimado, observaciones, %s)
        VALUES (:empresa, :fecha, :tipo, :documento, :folioExcel, :materialId, :cantidad,
          :frenteId, :contratoId, :unidadObraId, :lotesTexto, :paqueteId, :paqueteTexto,
          :clasificacionId, :solicitanteId, :solicitanteTexto, :entregarATexto, :entregado,
          :costoUnitario, :importe, :costoEstimado, :observaciones, %s)
        """,
        empresa,
        carga,
        lote.salidas,
        Salida::traza,
        (s, p) ->
            p.addValue("fecha", s.fecha())
                .addValue("tipo", s.tipo())
                .addValue("documento", s.documento())
                .addValue("folioExcel", s.folioExcel())
                .addValue("materialId", s.materialId())
                .addValue("cantidad", s.cantidad())
                .addValue("frenteId", s.frenteId())
                .addValue("contratoId", s.contratoId())
                .addValue("unidadObraId", s.unidadObraId())
                .addValue("lotesTexto", s.lotesTexto())
                .addValue("paqueteId", s.paqueteId())
                .addValue("paqueteTexto", s.paqueteTexto())
                .addValue("clasificacionId", s.clasificacionId())
                .addValue("solicitanteId", s.solicitanteId())
                .addValue("solicitanteTexto", s.solicitanteTexto())
                .addValue("entregarATexto", s.entregarATexto())
                .addValue("entregado", s.entregado())
                .addValue("costoUnitario", s.costoUnitario())
                .addValue("importe", s.importe())
                .addValue("costoEstimado", s.costoEstimado())
                .addValue("observaciones", s.observaciones()));
    insertar(
        escritos,
        "requisicion_linea",
        """
        INSERT INTO historico.requisicion_linea (empresa_id, origen, fecha, semana, semana_texto,
          urgencia, frente_id, prototipo_texto, contrato_id, unidad_obra_id, lotes_texto,
          material_id, cantidad, unidad_texto, cantidad_pedida, cantidad_cancelada, suministrado,
          paquete_id, paquete_texto, solicitante_id, solicitante_texto, autorizacion, estado,
          observaciones, %s)
        VALUES (:empresa, :origen, :fecha, :semana, :semanaTexto, :urgencia, :frenteId,
          :prototipoTexto, :contratoId, :unidadObraId, :lotesTexto, :materialId, :cantidad,
          :unidadTexto, :cantidadPedida, :cantidadCancelada, :suministrado, :paqueteId,
          :paqueteTexto, :solicitanteId, :solicitanteTexto, :autorizacion, :estado,
          :observaciones, %s)
        """,
        empresa,
        carga,
        lote.requisiciones,
        RequisicionLinea::traza,
        (r, p) ->
            p.addValue("origen", r.origen())
                .addValue("fecha", r.fecha())
                .addValue("semana", r.semana())
                .addValue("semanaTexto", r.semanaTexto())
                .addValue("urgencia", r.urgencia())
                .addValue("frenteId", r.frenteId())
                .addValue("prototipoTexto", r.prototipoTexto())
                .addValue("contratoId", r.contratoId())
                .addValue("unidadObraId", r.unidadObraId())
                .addValue("lotesTexto", r.lotesTexto())
                .addValue("materialId", r.materialId())
                .addValue("cantidad", r.cantidad())
                .addValue("unidadTexto", r.unidadTexto())
                .addValue("cantidadPedida", r.cantidadPedida())
                .addValue("cantidadCancelada", r.cantidadCancelada())
                .addValue("suministrado", r.suministrado())
                .addValue("paqueteId", r.paqueteId())
                .addValue("paqueteTexto", r.paqueteTexto())
                .addValue("solicitanteId", r.solicitanteId())
                .addValue("solicitanteTexto", r.solicitanteTexto())
                .addValue("autorizacion", r.autorizacion())
                .addValue("estado", r.estado())
                .addValue("observaciones", r.observaciones()));
    insertar(
        escritos,
        "prestamo",
        """
        INSERT INTO historico.prestamo (empresa_id, fecha, material_id, articulo, cantidad,
          unidad_texto, frente_id, lotes_texto, solicitante_id, solicitante_texto, entregar_a_id,
          entregar_a_texto, devuelto, devuelto_en, observaciones, %s)
        VALUES (:empresa, :fecha, :materialId, :articulo, :cantidad, :unidadTexto, :frenteId,
          :lotesTexto, :solicitanteId, :solicitanteTexto, :entregarAId, :entregarATexto,
          :devuelto, :devueltoEn, :observaciones, %s)
        """,
        empresa,
        carga,
        lote.prestamos,
        Prestamo::traza,
        (r, p) ->
            p.addValue("fecha", r.fecha())
                .addValue("materialId", r.materialId())
                .addValue("articulo", r.articulo())
                .addValue("cantidad", r.cantidad())
                .addValue("unidadTexto", r.unidadTexto())
                .addValue("frenteId", r.frenteId())
                .addValue("lotesTexto", r.lotesTexto())
                .addValue("solicitanteId", r.solicitanteId())
                .addValue("solicitanteTexto", r.solicitanteTexto())
                .addValue("entregarAId", r.entregarAId())
                .addValue("entregarATexto", r.entregarATexto())
                .addValue("devuelto", r.devuelto())
                .addValue("devueltoEn", r.devueltoEn())
                .addValue("observaciones", r.observaciones()));
    insertar(
        escritos,
        "alta_imss",
        """
        INSERT INTO historico.alta_imss (empresa_id, mes, origen, persona_id, estatus, dias,
          frente_texto, contrato_id, contrato_texto, numero_obra, %s)
        VALUES (:empresa, :mes, :origen, :personaId, :estatus, :dias, :frenteTexto, :contratoId,
          :contratoTexto, :numeroObra, %s)
        """,
        empresa,
        carga,
        lote.altas,
        AltaImss::traza,
        (a, p) ->
            p.addValue("mes", a.mes())
                .addValue("origen", a.origen())
                .addValue("personaId", a.personaId())
                .addValue("estatus", a.estatus())
                .addValue("dias", a.dias())
                .addValue("frenteTexto", a.frenteTexto())
                .addValue("contratoId", a.contratoId())
                .addValue("contratoTexto", a.contratoTexto())
                .addValue("numeroObra", a.numeroObra()));
    insertar(
        escritos,
        "asistencia_dia",
        """
        INSERT INTO historico.asistencia_dia (empresa_id, fecha, persona_id, frente_id,
          frente_texto, tipo_pago, ocupacion, valor, doble_asistencia, %s)
        VALUES (:empresa, :fecha, :personaId, :frenteId, :frenteTexto, :tipoPago, :ocupacion,
          :valor, :doble, %s)
        """,
        empresa,
        carga,
        lote.asistencias,
        AsistenciaDia::traza,
        (a, p) ->
            p.addValue("fecha", a.fecha())
                .addValue("personaId", a.personaId())
                .addValue("frenteId", a.frenteId())
                .addValue("frenteTexto", a.frenteTexto())
                .addValue("tipoPago", a.tipoPago())
                .addValue("ocupacion", a.ocupacion())
                .addValue("valor", a.valor())
                .addValue("doble", a.dobleAsistencia()));
    insertar(
        escritos,
        "asignacion",
        """
        INSERT INTO historico.asignacion (empresa_id, mes, persona_id, frente_id, frente_texto,
          asignacion_texto, %s)
        VALUES (:empresa, :mes, :personaId, :frenteId, :frenteTexto, :asignacionTexto, %s)
        """,
        empresa,
        carga,
        lote.asignaciones,
        Asignacion::traza,
        (a, p) ->
            p.addValue("mes", a.mes())
                .addValue("personaId", a.personaId())
                .addValue("frenteId", a.frenteId())
                .addValue("frenteTexto", a.frenteTexto())
                .addValue("asignacionTexto", a.asignacionTexto()));
    insertar(
        escritos,
        "pago_destajo",
        """
        INSERT INTO historico.pago_destajo (empresa_id, anio, semanas, semana_texto, frente_id,
          contrato_id, unidad_obra_id, prototipo_id, paquete_id, seccion_texto, concepto_texto,
          importe_concepto, monto, parcial, monto_compartido, sobre_importe, trabajador_texto,
          celda, %s)
        VALUES (:empresa, :anio, CAST(:semanas AS smallint[]), :semanaTexto, :frenteId,
          :contratoId, :unidadObraId, :prototipoId, :paqueteId, :seccionTexto, :conceptoTexto,
          :importeConcepto, :monto, :parcial, :compartido, :sobreImporte, :trabajadorTexto,
          :celda, %s)
        """,
        empresa,
        carga,
        lote.pagos,
        PagoDestajo::traza,
        (g, p) ->
            p.addValue("anio", g.anio())
                .addValue(
                    "semanas",
                    g.semanas().stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(",", "{", "}")))
                .addValue("semanaTexto", g.semanaTexto())
                .addValue("frenteId", g.frenteId())
                .addValue("contratoId", g.contratoId())
                .addValue("unidadObraId", g.unidadObraId())
                .addValue("prototipoId", g.prototipoId())
                .addValue("paqueteId", g.paqueteId())
                .addValue("seccionTexto", g.seccionTexto())
                .addValue("conceptoTexto", g.conceptoTexto())
                .addValue("importeConcepto", g.importeConcepto())
                .addValue("monto", g.monto())
                .addValue("parcial", g.parcial())
                .addValue("compartido", g.montoCompartido())
                .addValue("sobreImporte", g.sobreImporte())
                .addValue("trabajadorTexto", g.trabajadorTexto())
                .addValue("celda", g.celda()));
    trabajadores(empresa, carga, lote.pagos, escritos);
    return escritos;
  }

  private <T> void insertar(
      Map<String, Long> escritos,
      String tabla,
      String sql,
      long empresa,
      long carga,
      List<T> registros,
      Function<T, Traza> traza,
      BiConsumer<T, MapSqlParameterSource> llenar) {
    if (registros.isEmpty()) {
      return;
    }
    MapSqlParameterSource[] parametros = new MapSqlParameterSource[registros.size()];
    for (int i = 0; i < registros.size(); i++) {
      T registro = registros.get(i);
      Traza t = traza.apply(registro);
      MapSqlParameterSource p =
          new MapSqlParameterSource()
              .addValue("empresa", empresa)
              .addValue("carga", carga)
              .addValue("calidad", t.calidad().clave())
              .addValue("numero", t.numero())
              .addValue("hoja", t.hoja())
              .addValue("filaExcel", t.filaExcel());
      llenar.accept(registro, p);
      parametros[i] = p;
    }
    lotes.batchUpdate(sql.formatted(TRAZA, VALORES_TRAZA), parametros);
    escritos.put(tabla, (long) registros.size());
  }

  /** Liga cada pago con quienes lo cobraron: se buscan por carga, renglón y parcial. */
  private void trabajadores(
      long empresa, long carga, List<PagoDestajo> pagos, Map<String, Long> escritos) {
    if (pagos.stream().allMatch(p -> p.trabajadores().isEmpty())) {
      return;
    }
    Map<String, Long> ids = new HashMap<>();
    jdbc.sql(
            """
            SELECT id, numero, parcial FROM historico.pago_destajo
             WHERE empresa_id = :empresa AND carga_id = :carga
            """)
        .param("empresa", empresa)
        .param("carga", carga)
        .query(
            (rs, n) -> {
              ids.put(rs.getInt("numero") + "|" + rs.getInt("parcial"), rs.getLong("id"));
              return null;
            })
        .list();
    List<MapSqlParameterSource> filas = new ArrayList<>();
    for (PagoDestajo pago : pagos) {
      Long id = ids.get(pago.traza().numero() + "|" + pago.parcial());
      List<Long> personas = pago.trabajadores().stream().distinct().toList();
      for (int i = 0; i < personas.size(); i++) {
        filas.add(
            new MapSqlParameterSource()
                .addValue("empresa", empresa)
                .addValue("pago", id)
                .addValue("persona", personas.get(i))
                .addValue("orden", i + 1));
      }
    }
    lotes.batchUpdate(
        """
        INSERT INTO historico.pago_trabajador (empresa_id, pago_id, persona_id, orden)
        VALUES (:empresa, :pago, :persona, :orden)
        """,
        filas.toArray(MapSqlParameterSource[]::new));
    escritos.put("pago_trabajador", (long) filas.size());
  }

  public List<EntradaValorada> entradasValoradas(long empresa) {
    return jdbc.sql(
            """
            SELECT material_id, fecha, cantidad, importe FROM historico.entrada
             WHERE empresa_id = :empresa AND importe IS NOT NULL
             ORDER BY material_id, fecha, id
            """)
        .param("empresa", empresa)
        .query(EntradaValorada.class)
        .list();
  }

  public int revertir(long carga) {
    return jdbc.sql("SELECT historico.revertir_carga(:carga)")
        .param("carga", carga)
        .query(Integer.class)
        .single();
  }

  // ------------------------------------------------------------------------------- consultas

  /** Una salida del histórico, para la historia de un lote. */
  public record FilaSalida(
      long id,
      LocalDate fecha,
      String tipo,
      String documento,
      String folioExcel,
      long materialId,
      BigDecimal cantidad,
      Long paqueteId,
      String paqueteTexto,
      Integer clasificacionId,
      Long solicitanteId,
      String solicitanteTexto,
      boolean entregado,
      BigDecimal costoUnitario,
      BigDecimal importe,
      boolean costoEstimado,
      String lotesTexto,
      String observaciones,
      String hoja,
      Integer filaExcel) {}

  /** Un pago de destajo con quienes lo cobraron. */
  public record FilaPago(
      long id,
      int anio,
      String semanas,
      String semanaTexto,
      Long paqueteId,
      String seccionTexto,
      String conceptoTexto,
      BigDecimal importeConcepto,
      BigDecimal monto,
      int parcial,
      boolean montoCompartido,
      boolean sobreImporte,
      String trabajadorTexto,
      String trabajadores,
      String hoja,
      String celda) {}

  /** Un alta del IMSS en un contrato. */
  public record FilaAlta(
      long id,
      LocalDate mes,
      String origen,
      long personaId,
      String estatus,
      Integer dias,
      String numeroObra) {}

  public List<FilaSalida> salidasDeUnidad(long empresa, long unidad) {
    return jdbc.sql(
            """
            SELECT id, fecha, tipo, documento, folio_excel, material_id, cantidad, paquete_id,
                   paquete_texto, clasificacion_id, solicitante_id, solicitante_texto, entregado,
                   costo_unitario, importe, costo_estimado, lotes_texto, observaciones, hoja,
                   fila_excel
              FROM historico.salida
             WHERE empresa_id = :empresa AND unidad_obra_id = :unidad
             ORDER BY fecha, id
            """)
        .param("empresa", empresa)
        .param("unidad", unidad)
        .query(FilaSalida.class)
        .list();
  }

  public List<FilaPago> pagosDeUnidad(long empresa, long unidad) {
    return jdbc.sql(
            """
            SELECT p.id, p.anio, array_to_string(p.semanas, ',') AS semanas, p.semana_texto,
                   p.paquete_id, p.seccion_texto, p.concepto_texto, p.importe_concepto, p.monto,
                   p.parcial, p.monto_compartido, p.sobre_importe, p.trabajador_texto,
                   (SELECT string_agg(t.persona_id::text, ',' ORDER BY t.orden)
                      FROM historico.pago_trabajador t WHERE t.pago_id = p.id) AS trabajadores,
                   p.hoja, p.celda
              FROM historico.pago_destajo p
             WHERE p.empresa_id = :empresa AND p.unidad_obra_id = :unidad
             ORDER BY p.semanas[1] NULLS LAST, p.id
            """)
        .param("empresa", empresa)
        .param("unidad", unidad)
        .query(FilaPago.class)
        .list();
  }

  public List<FilaAlta> altasDeContrato(long empresa, long contrato) {
    return jdbc.sql(
            """
            SELECT id, mes, origen, persona_id, estatus, dias, numero_obra
              FROM historico.alta_imss
             WHERE empresa_id = :empresa AND contrato_id = :contrato
             ORDER BY mes DESC, origen, id
            """)
        .param("empresa", empresa)
        .param("contrato", contrato)
        .query(FilaAlta.class)
        .list();
  }

  // ------------------------------------------------------------------------------ Consultas de H6

  /** Último proveedor de cada material en la hoja Entradas. */
  public record FilaProveedor(long materialId, long proveedorId, java.time.LocalDate fecha) {}

  public List<FilaProveedor> ultimosProveedores(
      long empresa, java.util.Collection<Long> materiales) {
    if (materiales.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT DISTINCT ON (material_id) material_id, proveedor_id, fecha
              FROM historico.entrada
             WHERE empresa_id = :empresa AND proveedor_id IS NOT NULL
               AND material_id IN (:materiales)
             ORDER BY material_id, fecha DESC, id DESC
            """)
        .param("empresa", empresa)
        .param("materiales", List.copyOf(materiales))
        .query(FilaProveedor.class)
        .list();
  }

  /** Salida del histórico con su renglón del Excel, para conciliar contra la hoja del frente. */
  public record FilaSalidaCarga(
      long id,
      int filaExcel,
      String tipo,
      long materialId,
      java.math.BigDecimal cantidad,
      Long frenteId,
      Long contratoId,
      Long unidadObraId,
      String lotesTexto,
      Integer clasificacionId,
      boolean entregado,
      java.math.BigDecimal importe,
      boolean costoEstimado) {}

  /** Las salidas de una carga en un frente, en el orden de la hoja. */
  public List<FilaSalidaCarga> salidasDeCarga(long empresa, long carga, long frente) {
    return jdbc.sql(
            """
            SELECT id, fila_excel, tipo, material_id, cantidad, frente_id, contrato_id,
                   unidad_obra_id, lotes_texto, clasificacion_id, entregado, importe, costo_estimado
              FROM historico.salida
             WHERE empresa_id = :empresa AND carga_id = :carga AND frente_id = :frente
               AND fila_excel IS NOT NULL
             ORDER BY fila_excel, id
            """)
        .param("empresa", empresa)
        .param("carga", carga)
        .param("frente", frente)
        .query(FilaSalidaCarga.class)
        .list();
  }
}

package mx.constructora.importacion.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mx.constructora.importacion.dominio.Json;
import mx.constructora.importacion.dominio.Modelo.Accion;
import mx.constructora.importacion.dominio.Modelo.Decision;
import mx.constructora.importacion.dominio.Modelo.EstadoFila;
import mx.constructora.importacion.dominio.Modelo.FilaCruda;
import mx.constructora.importacion.dominio.Modelo.FilaValidada;
import mx.constructora.importacion.dominio.Modelo.Incidencia;
import mx.constructora.importacion.dominio.Modelo.Opcion;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Tablas del esquema {@code migracion}. Las columnas jsonb viajan como texto con {@code CAST(… AS
 * jsonb)}; el JSON lo arma y lo lee {@link Json}. Filas e incidencias se escriben por lotes.
 */
@Repository
public class RepositorioMigracion {

  /** Una carga, sin sus renglones. */
  public record FilaCarga(
      long id,
      String tipo,
      String archivo,
      String sha256,
      String hojas,
      String parametros,
      String estado,
      String versionReglas,
      long usuarioId,
      OffsetDateTime creada,
      OffsetDateTime validada,
      OffsetDateTime confirmada,
      Long confirmadaPor,
      OffsetDateTime revertida,
      Long revertidaPor,
      String resumen) {}

  /** Conteo de renglones por estado, acción y hoja. */
  public record Conteo(String hoja, String estado, String accion, long renglones) {}

  /** Conteo de incidencias por regla y severidad. */
  public record ConteoRegla(
      String regla, String severidad, long renglones, long pendientes, String ejemplo) {}

  /** Un renglón con sus incidencias, como se guardó. */
  public record FilaGuardada(
      int numero,
      String hoja,
      Integer filaExcel,
      Map<String, String> crudo,
      Map<String, String> normal,
      String estado,
      String accion,
      List<IncidenciaGuardada> incidencias) {}

  public record IncidenciaGuardada(
      String regla,
      String severidad,
      String detalle,
      Map<String, Object> sugerencia,
      Map<String, Object> resolucion,
      Long resueltaPor,
      OffsetDateTime resueltaEn) {}

  /** Alias con la carga en la que se decidió. */
  public record FilaAlias(
      long id,
      String entidad,
      String texto,
      long destinoId,
      String origen,
      Long cargaId,
      Long usuarioId,
      OffsetDateTime creado,
      int version) {}

  private static final String CARGA =
      """
      SELECT id, tipo, archivo, sha256, hojas, parametros::text AS parametros, estado, version_reglas,
             usuario_id, creada, validada, confirmada, confirmada_por, revertida, revertida_por,
             resumen::text AS resumen
        FROM migracion.carga
      """;

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate lotes;

  RepositorioMigracion(JdbcClient jdbc, NamedParameterJdbcTemplate lotes) {
    this.jdbc = jdbc;
    this.lotes = lotes;
  }

  // ---------------------------------------------------------------------------------- archivos

  public boolean existeArchivo(long empresa, String sha256) {
    return jdbc.sql(
                "SELECT count(*) FROM migracion.archivo WHERE empresa_id = :empresa AND sha256 ="
                    + " :sha")
            .param("empresa", empresa)
            .param("sha", sha256)
            .query(Long.class)
            .single()
        > 0;
  }

  public void guardarArchivo(
      long empresa, String sha256, String nombre, byte[] contenido, long usuario) {
    jdbc.sql(
            """
            INSERT INTO migracion.archivo (empresa_id, sha256, nombre, tamano, contenido, usuario_id)
            VALUES (:empresa, :sha, :nombre, :tamano, :contenido, :usuario)
            ON CONFLICT (empresa_id, sha256) DO NOTHING
            """)
        .param("empresa", empresa)
        .param("sha", sha256)
        .param("nombre", nombre)
        .param("tamano", contenido.length)
        .param("contenido", contenido)
        .param("usuario", usuario)
        .update();
  }

  // ------------------------------------------------------------------------------------ cargas

  public long crearCarga(
      long empresa,
      String tipo,
      String archivo,
      String sha256,
      String hojas,
      Map<String, String> parametros,
      String versionReglas,
      long usuario) {
    return jdbc.sql(
            """
            INSERT INTO migracion.carga
              (empresa_id, tipo, archivo, sha256, hojas, parametros, version_reglas, usuario_id)
            VALUES (:empresa, :tipo, :archivo, :sha, :hojas, CAST(:parametros AS jsonb), :version,
                    :usuario)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("tipo", tipo)
        .param("archivo", archivo)
        .param("sha", sha256)
        .param("hojas", hojas)
        .param("parametros", Json.escribir(parametros))
        .param("version", versionReglas)
        .param("usuario", usuario)
        .query(Long.class)
        .single();
  }

  /** La carga vigente (no revertida) del mismo archivo, tipo y versión de reglas. */
  public Optional<FilaCarga> vigente(long empresa, String sha256, String tipo, String version) {
    return jdbc.sql(
            CARGA
                + """
                 WHERE empresa_id = :empresa AND sha256 = :sha AND tipo = :tipo
                   AND version_reglas = :version AND estado <> 'revertida'
                """)
        .param("empresa", empresa)
        .param("sha", sha256)
        .param("tipo", tipo)
        .param("version", version)
        .query(FilaCarga.class)
        .optional();
  }

  public Optional<FilaCarga> carga(long empresa, long id) {
    return jdbc.sql(CARGA + " WHERE empresa_id = :empresa AND id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .query(FilaCarga.class)
        .optional();
  }

  /** Bloquea la carga hasta el fin de la transacción: dos confirmaciones no corren a la vez. */
  public Optional<FilaCarga> cargaParaCambiar(long empresa, long id) {
    return jdbc.sql(CARGA + " WHERE empresa_id = :empresa AND id = :id FOR UPDATE")
        .param("empresa", empresa)
        .param("id", id)
        .query(FilaCarga.class)
        .optional();
  }

  public List<FilaCarga> cargas(long empresa) {
    return jdbc.sql(CARGA + " WHERE empresa_id = :empresa ORDER BY id DESC")
        .param("empresa", empresa)
        .query(FilaCarga.class)
        .list();
  }

  /** Tipos con al menos una carga confirmada. */
  public List<String> tiposConfirmados(long empresa) {
    return jdbc.sql(
            """
            SELECT DISTINCT tipo FROM migracion.carga
             WHERE empresa_id = :empresa AND estado = 'confirmada'
            """)
        .param("empresa", empresa)
        .query(String.class)
        .list();
  }

  public void marcarValidada(long empresa, long id) {
    jdbc.sql(
            """
            UPDATE migracion.carga SET validada = now(), version = version + 1
             WHERE empresa_id = :empresa AND id = :id
            """)
        .param("empresa", empresa)
        .param("id", id)
        .update();
  }

  public void marcarConfirmada(long empresa, long id, long usuario, Map<String, Object> resumen) {
    jdbc.sql(
            """
            UPDATE migracion.carga
               SET estado = 'confirmada', confirmada = now(), confirmada_por = :usuario,
                   resumen = CAST(:resumen AS jsonb), version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND estado = 'validada'
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("usuario", usuario)
        .param("resumen", Json.escribir(resumen))
        .update();
  }

  public void marcarRevertida(long empresa, long id, long usuario) {
    jdbc.sql(
            """
            UPDATE migracion.carga
               SET estado = 'revertida', revertida = now(), revertida_por = :usuario,
                   version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND estado <> 'revertida'
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("usuario", usuario)
        .update();
  }

  // ------------------------------------------------------------------------------------- filas

  /** Reemplaza los renglones y sus incidencias por los de la última validación. */
  public void reemplazarFilas(long empresa, long carga, List<FilaValidada> filas) {
    jdbc.sql("DELETE FROM migracion.fila WHERE empresa_id = :empresa AND carga_id = :carga")
        .param("empresa", empresa)
        .param("carga", carga)
        .update();
    List<MapSqlParameterSource> renglones = new ArrayList<>();
    List<MapSqlParameterSource> incidencias = new ArrayList<>();
    for (FilaValidada fila : filas) {
      renglones.add(
          new MapSqlParameterSource()
              .addValue("empresa", empresa)
              .addValue("carga", carga)
              .addValue("numero", fila.numero())
              .addValue("hoja", fila.fila().hoja())
              .addValue("filaExcel", fila.fila().filaExcel())
              .addValue("crudo", Json.escribir(fila.fila().crudo()))
              .addValue("normal", Json.escribir(fila.normal()))
              .addValue("estado", fila.estado().clave())
              .addValue("accion", fila.accion().clave()));
      for (Incidencia incidencia : fila.incidencias()) {
        Decision resolucion = incidencia.resolucion();
        incidencias.add(
            new MapSqlParameterSource()
                .addValue("empresa", empresa)
                .addValue("carga", carga)
                .addValue("numero", fila.numero())
                .addValue("regla", incidencia.regla())
                .addValue("severidad", incidencia.severidad().clave())
                .addValue("detalle", recortar(incidencia.detalle(), 500))
                .addValue(
                    "sugerencia",
                    incidencia.sugerencia() == null && incidencia.opciones().isEmpty()
                        ? null
                        : Json.escribir(sugerencia(incidencia)))
                .addValue(
                    "resolucion", resolucion == null ? null : Json.escribir(aMapa(resolucion))));
      }
    }
    lotes.batchUpdate(
        """
        INSERT INTO migracion.fila (empresa_id, carga_id, numero, hoja, fila_excel, crudo, normal,
                                    estado, accion)
        VALUES (:empresa, :carga, :numero, :hoja, :filaExcel, CAST(:crudo AS jsonb),
                CAST(:normal AS jsonb), :estado, :accion)
        """,
        renglones.toArray(MapSqlParameterSource[]::new));
    // Una incidencia por regla y renglón: si una regla se repite en el renglón, gana la primera.
    Map<String, MapSqlParameterSource> unicas = new LinkedHashMap<>();
    for (MapSqlParameterSource incidencia : incidencias) {
      unicas.putIfAbsent(
          incidencia.getValue("numero") + "|" + incidencia.getValue("regla"), incidencia);
    }
    lotes.batchUpdate(
        """
        INSERT INTO migracion.incidencia (empresa_id, carga_id, numero, regla, severidad, detalle,
                                          sugerencia, resolucion, resuelta_por, resuelta_en)
        SELECT :empresa, :carga, :numero, :regla, :severidad, :detalle,
               CAST(:sugerencia AS jsonb), CAST(:resolucion AS jsonb), d.usuario_id, d.fecha
          FROM (SELECT 1) uno
          LEFT JOIN migracion.decision d
            ON d.carga_id = :carga AND d.numero = :numero AND d.regla = :regla
               AND CAST(:resolucion AS jsonb) IS NOT NULL
        """,
        unicas.values().toArray(MapSqlParameterSource[]::new));
  }

  /** La sugerencia con las opciones que tiene la persona, para la pantalla. */
  private static Map<String, Object> sugerencia(Incidencia incidencia) {
    Map<String, Object> mapa =
        incidencia.sugerencia() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(incidencia.sugerencia());
    mapa.put("opciones", incidencia.opciones().stream().map(Enum::name).sorted().toList());
    return mapa;
  }

  private static Map<String, Object> aMapa(Decision decision) {
    Map<String, Object> mapa = new LinkedHashMap<>();
    mapa.put("opcion", decision.opcion().name());
    if (decision.destinoId() != null) {
      mapa.put("destinoId", decision.destinoId());
    }
    if (decision.valor() != null) {
      mapa.put("valor", decision.valor());
    }
    if (decision.motivo() != null) {
      mapa.put("motivo", decision.motivo());
    }
    return mapa;
  }

  private static String recortar(String texto, int largo) {
    return texto.length() <= largo ? texto : texto.substring(0, largo - 1) + "…";
  }

  /** Los renglones como se leyeron, para volver a validar sin abrir el archivo. */
  public List<FilaCruda> crudas(long empresa, long carga) {
    return jdbc.sql(
            """
            SELECT numero, hoja, fila_excel, crudo::text AS crudo FROM migracion.fila
             WHERE empresa_id = :empresa AND carga_id = :carga ORDER BY numero
            """)
        .param("empresa", empresa)
        .param("carga", carga)
        .query(
            (rs, n) ->
                new FilaCruda(
                    rs.getInt("numero"),
                    rs.getString("hoja"),
                    (Integer) rs.getObject("fila_excel"),
                    Json.textos(rs.getString("crudo"))))
        .list();
  }

  public List<Conteo> conteos(long empresa, long carga) {
    return jdbc.sql(
            """
            SELECT hoja, estado, accion, count(*) AS renglones FROM migracion.fila
             WHERE empresa_id = :empresa AND carga_id = :carga
             GROUP BY hoja, estado, accion ORDER BY min(numero), estado, accion
            """)
        .param("empresa", empresa)
        .param("carga", carga)
        .query(Conteo.class)
        .list();
  }

  public List<ConteoRegla> conteosPorRegla(long empresa, long carga) {
    return jdbc.sql(
            """
            SELECT i.regla, i.severidad, count(*) AS renglones,
                   count(*) FILTER (WHERE i.severidad IN ('cuarentena', 'bloqueo')
                                      AND i.resolucion IS NULL) AS pendientes,
                   min(i.detalle) AS ejemplo
              FROM migracion.incidencia i
             WHERE i.empresa_id = :empresa AND i.carga_id = :carga
             GROUP BY i.regla, i.severidad
             ORDER BY CASE i.severidad WHEN 'bloqueo' THEN 0 WHEN 'cuarentena' THEN 1
                                       WHEN 'advertencia' THEN 2 ELSE 3 END, i.regla
            """)
        .param("empresa", empresa)
        .param("carga", carga)
        .query(ConteoRegla.class)
        .list();
  }

  /**
   * Renglones filtrados y paginados.
   *
   * @param regla solo los que tienen esa incidencia
   * @param texto en el contenido crudo, sin importar acentos ni mayúsculas
   */
  public List<FilaGuardada> filas(
      long empresa,
      long carga,
      String estado,
      String regla,
      String texto,
      boolean soloPendientes,
      int limite,
      long desde) {
    List<FilaGuardada> filas =
        jdbc.sql(
                """
                SELECT f.numero, f.hoja, f.fila_excel, f.crudo::text AS crudo,
                       f.normal::text AS normal, f.estado, f.accion
                  FROM migracion.fila f
                """
                    + FILTRO_FILAS
                    + " ORDER BY f.numero LIMIT :limite OFFSET :desde")
            .param("empresa", empresa)
            .param("carga", carga)
            .param("estado", estado)
            .param("regla", regla)
            .param("texto", texto)
            .param("pendientes", soloPendientes)
            .param("limite", limite)
            .param("desde", desde)
            .query(
                (rs, n) ->
                    new FilaGuardada(
                        rs.getInt("numero"),
                        rs.getString("hoja"),
                        (Integer) rs.getObject("fila_excel"),
                        Json.textos(rs.getString("crudo")),
                        Json.textos(rs.getString("normal")),
                        rs.getString("estado"),
                        rs.getString("accion"),
                        new ArrayList<>()))
            .list();
    if (filas.isEmpty()) {
      return filas;
    }
    Map<Integer, FilaGuardada> porNumero = new LinkedHashMap<>();
    filas.forEach(f -> porNumero.put(f.numero(), f));
    jdbc.sql(
            """
            SELECT numero, regla, severidad, detalle, sugerencia::text AS sugerencia,
                   resolucion::text AS resolucion, resuelta_por, resuelta_en
              FROM migracion.incidencia
             WHERE empresa_id = :empresa AND carga_id = :carga AND numero IN (:numeros)
             ORDER BY numero, CASE severidad WHEN 'bloqueo' THEN 0 WHEN 'cuarentena' THEN 1
                                             WHEN 'advertencia' THEN 2 ELSE 3 END, regla
            """)
        .param("empresa", empresa)
        .param("carga", carga)
        .param("numeros", List.copyOf(porNumero.keySet()))
        .query(
            (rs, n) -> {
              porNumero.get(rs.getInt("numero")).incidencias().add(incidencia(rs));
              return 1;
            })
        .list();
    return filas;
  }

  private static final String FILTRO_FILAS =
      """
       WHERE f.empresa_id = :empresa AND f.carga_id = :carga
         AND (CAST(:estado AS text) IS NULL OR f.estado = :estado)
         AND (CAST(:regla AS text) IS NULL OR EXISTS (
               SELECT 1 FROM migracion.incidencia i
                WHERE i.carga_id = f.carga_id AND i.numero = f.numero AND i.regla = :regla))
         AND (NOT :pendientes OR EXISTS (
               SELECT 1 FROM migracion.incidencia i
                WHERE i.carga_id = f.carga_id AND i.numero = f.numero
                  AND i.severidad IN ('cuarentena', 'bloqueo') AND i.resolucion IS NULL))
         AND (CAST(:texto AS text) IS NULL
              OR strpos(compartido.normalizar(f.crudo::text), compartido.normalizar(:texto)) > 0)
      """;

  public long contarFilas(
      long empresa, long carga, String estado, String regla, String texto, boolean soloPendientes) {
    return jdbc.sql("SELECT count(*) FROM migracion.fila f" + FILTRO_FILAS)
        .param("empresa", empresa)
        .param("carga", carga)
        .param("estado", estado)
        .param("regla", regla)
        .param("texto", texto)
        .param("pendientes", soloPendientes)
        .query(Long.class)
        .single();
  }

  private static IncidenciaGuardada incidencia(ResultSet rs) throws SQLException {
    String sugerencia = rs.getString("sugerencia");
    String resolucion = rs.getString("resolucion");
    return new IncidenciaGuardada(
        rs.getString("regla"),
        rs.getString("severidad"),
        rs.getString("detalle"),
        sugerencia == null ? null : Json.objeto(sugerencia),
        resolucion == null ? null : Json.objeto(resolucion),
        (Long) rs.getObject("resuelta_por"),
        rs.getObject("resuelta_en", OffsetDateTime.class));
  }

  /** La incidencia de un renglón, para validar una decisión. */
  public Optional<IncidenciaGuardada> incidencia(
      long empresa, long carga, int numero, String regla) {
    return jdbc.sql(
            """
            SELECT regla, severidad, detalle, sugerencia::text AS sugerencia,
                   resolucion::text AS resolucion, resuelta_por, resuelta_en
              FROM migracion.incidencia
             WHERE empresa_id = :empresa AND carga_id = :carga AND numero = :numero
               AND regla = :regla
            """)
        .param("empresa", empresa)
        .param("carga", carga)
        .param("numero", numero)
        .param("regla", regla)
        .query((rs, n) -> incidencia(rs))
        .optional();
  }

  // ------------------------------------------------------------------------------- decisiones

  public List<Decision> decisiones(long empresa, long carga) {
    return jdbc.sql(
            """
            SELECT numero, regla, resolucion::text AS resolucion FROM migracion.decision
             WHERE empresa_id = :empresa AND carga_id = :carga ORDER BY numero, regla
            """)
        .param("empresa", empresa)
        .param("carga", carga)
        .query(
            (rs, n) -> {
              Map<String, Object> r = Json.objeto(rs.getString("resolucion"));
              Object destino = r.get("destinoId");
              return new Decision(
                  rs.getInt("numero"),
                  rs.getString("regla"),
                  Opcion.valueOf((String) r.get("opcion")),
                  destino == null ? null : ((Number) destino).longValue(),
                  (String) r.get("valor"),
                  (String) r.get("motivo"));
            })
        .list();
  }

  public void guardarDecision(long empresa, long carga, Decision decision, long usuario) {
    jdbc.sql(
            """
            INSERT INTO migracion.decision (empresa_id, carga_id, numero, regla, resolucion, usuario_id)
            VALUES (:empresa, :carga, :numero, :regla, CAST(:resolucion AS jsonb), :usuario)
            ON CONFLICT (carga_id, numero, regla) DO UPDATE
               SET resolucion = EXCLUDED.resolucion, usuario_id = EXCLUDED.usuario_id, fecha = now()
            """)
        .param("empresa", empresa)
        .param("carga", carga)
        .param("numero", decision.numero())
        .param("regla", decision.regla())
        .param("resolucion", Json.escribir(aMapa(decision)))
        .param("usuario", usuario)
        .update();
  }

  public int quitarDecision(long empresa, long carga, int numero, String regla) {
    return jdbc.sql(
            """
            DELETE FROM migracion.decision
             WHERE empresa_id = :empresa AND carga_id = :carga AND numero = :numero AND regla = :regla
            """)
        .param("empresa", empresa)
        .param("carga", carga)
        .param("numero", numero)
        .param("regla", regla)
        .update();
  }

  // ------------------------------------------------------------------------------------ alias

  private static final String ALIAS =
      """
      SELECT id, entidad, texto, destino_id, origen, carga_id, usuario_id, creado, version
        FROM migracion.alias
      """;

  /** entidad|texto → destino, para la validación. */
  public Map<String, Long> mapaAlias(long empresa) {
    Map<String, Long> mapa = new LinkedHashMap<>();
    jdbc.sql("SELECT entidad, texto, destino_id FROM migracion.alias WHERE empresa_id = :empresa")
        .param("empresa", empresa)
        .query(
            (rs, n) -> {
              mapa.put(
                  rs.getString("entidad") + "|" + rs.getString("texto"), rs.getLong("destino_id"));
              return 1;
            })
        .list();
    return mapa;
  }

  public List<FilaAlias> alias(long empresa, String entidad, String texto, int limite) {
    return jdbc.sql(
            ALIAS
                + """
                 WHERE empresa_id = :empresa
                   AND (CAST(:entidad AS text) IS NULL OR entidad = :entidad)
                   AND (CAST(:texto AS text) IS NULL
                        OR strpos(texto, compartido.normalizar(:texto)) > 0)
                 ORDER BY entidad, texto LIMIT :limite
                """)
        .param("empresa", empresa)
        .param("entidad", entidad)
        .param("texto", texto)
        .param("limite", limite)
        .query(FilaAlias.class)
        .list();
  }

  public Optional<FilaAlias> unAlias(long empresa, long id) {
    return jdbc.sql(ALIAS + " WHERE empresa_id = :empresa AND id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .query(FilaAlias.class)
        .optional();
  }

  /** Alta o cambio del alias de {@code texto}: la última decisión manda. */
  public void guardarAlias(
      long empresa,
      String entidad,
      String texto,
      long destino,
      String origen,
      Long carga,
      Long usuario) {
    jdbc.sql(
            """
            INSERT INTO migracion.alias (empresa_id, entidad, texto, destino_id, origen, carga_id, usuario_id)
            VALUES (:empresa, :entidad, :texto, :destino, :origen, :carga, :usuario)
            ON CONFLICT (empresa_id, entidad, texto) DO UPDATE
               SET destino_id = EXCLUDED.destino_id, origen = EXCLUDED.origen,
                   carga_id = EXCLUDED.carga_id, usuario_id = EXCLUDED.usuario_id,
                   version = migracion.alias.version + 1
            """)
        .param("empresa", empresa)
        .param("entidad", entidad)
        .param("texto", texto)
        .param("destino", destino)
        .param("origen", origen)
        .param("carga", carga)
        .param("usuario", usuario)
        .update();
  }

  public int cambiarAlias(long empresa, long id, int version, long destino, long usuario) {
    return jdbc.sql(
            """
            UPDATE migracion.alias
               SET destino_id = :destino, origen = 'manual', usuario_id = :usuario,
                   version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("destino", destino)
        .param("usuario", usuario)
        .update();
  }

  public int borrarAlias(long empresa, long id) {
    return jdbc.sql("DELETE FROM migracion.alias WHERE empresa_id = :empresa AND id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .update();
  }

  /**
   * Borra los alias decididos en la carga y los que apuntan a registros que la carga creó (al
   * revertirla dejan de existir).
   */
  public int borrarAliasDeCarga(long empresa, long carga) {
    return jdbc.sql(
            """
            DELETE FROM migracion.alias a
             WHERE a.empresa_id = :empresa
               AND (a.carga_id = :carga
                    OR EXISTS (SELECT 1 FROM migracion.registro r
                                WHERE r.empresa_id = a.empresa_id AND r.carga_id = :carga
                                  AND r.entidad = a.entidad AND r.registro_id = a.destino_id))
            """)
        .param("empresa", empresa)
        .param("carga", carga)
        .update();
  }

  // --------------------------------------------------------------------------------- registro

  public void registrar(long empresa, long carga, String entidad, long registro) {
    jdbc.sql(
            """
            INSERT INTO migracion.registro (empresa_id, carga_id, entidad, registro_id)
            VALUES (:empresa, :carga, :entidad, :registro)
            ON CONFLICT DO NOTHING
            """)
        .param("empresa", empresa)
        .param("carga", carga)
        .param("entidad", entidad)
        .param("registro", registro)
        .update();
  }

  /** Todos los renglones de la carga como quedaron en la última validación (para conciliar). */
  public List<FilaValidada> validadas(long empresa, long carga) {
    return jdbc.sql(
            """
            SELECT numero, hoja, fila_excel, crudo::text AS crudo, normal::text AS normal, estado,
                   accion
              FROM migracion.fila
             WHERE empresa_id = :empresa AND carga_id = :carga ORDER BY numero
            """)
        .param("empresa", empresa)
        .param("carga", carga)
        .query(
            (rs, n) ->
                new FilaValidada(
                    new FilaCruda(
                        rs.getInt("numero"),
                        rs.getString("hoja"),
                        (Integer) rs.getObject("fila_excel"),
                        Json.textos(rs.getString("crudo"))),
                    EstadoFila.deClave(rs.getString("estado")),
                    Accion.deClave(rs.getString("accion")),
                    rs.getString("normal") == null ? Map.of() : Json.textos(rs.getString("normal")),
                    List.of()))
        .list();
  }

  /** Cargas confirmadas de un tipo, la más reciente primero. */
  public List<FilaCarga> confirmadas(long empresa, String tipo) {
    return jdbc.sql(
            CARGA
                + " WHERE empresa_id = :empresa AND tipo = :tipo AND estado = 'confirmada'"
                + " ORDER BY id DESC")
        .param("empresa", empresa)
        .param("tipo", tipo)
        .query(FilaCarga.class)
        .list();
  }

  /** Cuántos registros de cada entidad creó la carga. */
  public Map<String, Long> creados(long empresa, long carga) {
    Map<String, Long> creados = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT entidad, count(*) AS n FROM migracion.registro
             WHERE empresa_id = :empresa AND carga_id = :carga GROUP BY entidad ORDER BY entidad
            """)
        .param("empresa", empresa)
        .param("carga", carga)
        .query(
            (rs, n) -> {
              creados.put(rs.getString("entidad"), rs.getLong("n"));
              return 1;
            })
        .list();
    return creados;
  }
}

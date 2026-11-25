package mx.constructora.estructura.infraestructura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import mx.constructora.estructura.aplicacion.ServicioContratos.Contrato;
import mx.constructora.estructura.aplicacion.ServicioContratos.PrecioPaquete;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Contratos, residentes asignados y precios por paquete. {@code restringidoA} es el usuario cuyo
 * alcance limita la consulta, o {@code null} si ve todos los contratos de la empresa.
 */
@Repository
public class RepositorioContratos {

  private static final String CONTRATO =
      """
      SELECT c.id, c.cliente_id, cl.nombre AS cliente, c.frente_id, f.nombre AS frente, c.codigo,
             c.numero_obra, c.descripcion, c.inicio, c.fin, c.monto, c.estado,
             (SELECT count(*) FROM estructura.unidad_obra u WHERE u.contrato_id = c.id)::int AS unidades,
             c.version
        FROM estructura.contrato c
        JOIN estructura.cliente cl ON cl.id = c.cliente_id
        LEFT JOIN estructura.frente f ON f.id = c.frente_id
       WHERE c.empresa_id = :empresa
         AND (CAST(:usuario AS bigint) IS NULL
              OR EXISTS (SELECT 1 FROM estructura.contrato_usuario cu
                          WHERE cu.contrato_id = c.id AND cu.usuario_id = :usuario))
      """;

  private final JdbcClient jdbc;

  RepositorioContratos(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<Contrato> contratos(long empresa, Long restringidoA, String estado, Long frente) {
    return jdbc.sql(
            CONTRATO
                + """
                   AND (CAST(:estado AS text) IS NULL OR c.estado = :estado)
                   AND (CAST(:frente AS bigint) IS NULL OR c.frente_id = :frente)
                 ORDER BY c.estado = 'activo' DESC, c.inicio DESC NULLS LAST, c.codigo
                """)
        .param("empresa", empresa)
        .param("usuario", restringidoA)
        .param("estado", estado)
        .param("frente", frente)
        .query(Contrato.class)
        .list();
  }

  public Optional<Contrato> contrato(long empresa, Long restringidoA, long id) {
    return jdbc.sql(CONTRATO + " AND c.id = :id")
        .param("empresa", empresa)
        .param("usuario", restringidoA)
        .param("id", id)
        .query(Contrato.class)
        .optional();
  }

  public long insertar(
      long empresa,
      long cliente,
      Long frente,
      String codigo,
      String numeroObra,
      String descripcion,
      LocalDate inicio,
      LocalDate fin,
      BigDecimal monto,
      String estado) {
    return jdbc.sql(
            """
            INSERT INTO estructura.contrato
              (empresa_id, cliente_id, frente_id, codigo, numero_obra, descripcion, inicio, fin,
               monto, estado)
            VALUES (:empresa, :cliente, :frente, :codigo, :numeroObra, :descripcion, :inicio, :fin,
                    :monto, :estado)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("cliente", cliente)
        .param("frente", frente)
        .param("codigo", codigo)
        .param("numeroObra", numeroObra)
        .param("descripcion", descripcion)
        .param("inicio", inicio)
        .param("fin", fin)
        .param("monto", monto)
        .param("estado", estado)
        .query(Long.class)
        .single();
  }

  public int actualizar(
      long empresa,
      long id,
      int version,
      long cliente,
      Long frente,
      String codigo,
      String numeroObra,
      String descripcion,
      LocalDate inicio,
      LocalDate fin,
      BigDecimal monto,
      String estado) {
    return jdbc.sql(
            """
            UPDATE estructura.contrato
               SET cliente_id = :cliente, frente_id = :frente, codigo = :codigo,
                   numero_obra = :numeroObra, descripcion = :descripcion, inicio = :inicio,
                   fin = :fin, monto = :monto, estado = :estado, version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("cliente", cliente)
        .param("frente", frente)
        .param("codigo", codigo)
        .param("numeroObra", numeroObra)
        .param("descripcion", descripcion)
        .param("inicio", inicio)
        .param("fin", fin)
        .param("monto", monto)
        .param("estado", estado)
        .update();
  }

  // ------------------------------------------------------------------------------- Residentes

  public List<Long> residentes(long empresa, long contrato) {
    return jdbc.sql(
            """
            SELECT usuario_id FROM estructura.contrato_usuario
             WHERE empresa_id = :empresa AND contrato_id = :contrato ORDER BY usuario_id
            """)
        .param("empresa", empresa)
        .param("contrato", contrato)
        .query(Long.class)
        .list();
  }

  public void agregarResidente(long empresa, long contrato, long usuario) {
    jdbc.sql(
            """
            INSERT INTO estructura.contrato_usuario (empresa_id, contrato_id, usuario_id)
            VALUES (:empresa, :contrato, :usuario)
            """)
        .param("empresa", empresa)
        .param("contrato", contrato)
        .param("usuario", usuario)
        .update();
  }

  public void quitarResidente(long empresa, long contrato, long usuario) {
    jdbc.sql(
            """
            DELETE FROM estructura.contrato_usuario
             WHERE empresa_id = :empresa AND contrato_id = :contrato AND usuario_id = :usuario
            """)
        .param("empresa", empresa)
        .param("contrato", contrato)
        .param("usuario", usuario)
        .update();
  }

  // ------------------------------------------------------------------------------- Precios

  public List<PrecioPaquete> precios(long empresa, long contrato) {
    return jdbc.sql(
            """
            SELECT cp.id, cp.paquete_id, p.clave AS paquete_clave, p.nombre AS paquete,
                   p.es_cliente AS de_cliente, cp.prototipo_id, pr.clave AS prototipo,
                   cp.precio_cobro, cp.precio_destajo, cp.version
              FROM estructura.contrato_paquete cp
              JOIN estructura.paquete p ON p.id = cp.paquete_id
              LEFT JOIN estructura.prototipo pr ON pr.id = cp.prototipo_id
             WHERE cp.empresa_id = :empresa AND cp.contrato_id = :contrato
             ORDER BY p.es_cliente DESC, p.orden, p.clave, pr.clave NULLS FIRST
            """)
        .param("empresa", empresa)
        .param("contrato", contrato)
        .query(PrecioPaquete.class)
        .list();
  }

  /** Alta o cambio del precio de un paquete (y prototipo) en el contrato; devuelve su id. */
  public long fijarPrecio(
      long empresa,
      long contrato,
      long paquete,
      Long prototipo,
      BigDecimal precioCobro,
      BigDecimal precioDestajo) {
    return jdbc.sql(
            """
            INSERT INTO estructura.contrato_paquete
              (empresa_id, contrato_id, paquete_id, prototipo_id, precio_cobro, precio_destajo)
            VALUES (:empresa, :contrato, :paquete, :prototipo, :cobro, :destajo)
            ON CONFLICT (contrato_id, paquete_id, prototipo_id) DO UPDATE
               SET precio_cobro = EXCLUDED.precio_cobro,
                   precio_destajo = EXCLUDED.precio_destajo,
                   version = estructura.contrato_paquete.version + 1
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("contrato", contrato)
        .param("paquete", paquete)
        .param("prototipo", prototipo)
        .param("cobro", precioCobro)
        .param("destajo", precioDestajo)
        .query(Long.class)
        .single();
  }
}

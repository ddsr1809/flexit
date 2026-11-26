package mx.constructora.estructura.infraestructura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import mx.constructora.estructura.ImportacionEstructura.ContratoDato;
import mx.constructora.estructura.ImportacionEstructura.EquivalenciaDato;
import mx.constructora.estructura.ImportacionEstructura.FrenteDato;
import mx.constructora.estructura.ImportacionEstructura.PaqueteDato;
import mx.constructora.estructura.ImportacionEstructura.PrecioDato;
import mx.constructora.estructura.ImportacionEstructura.PrototipoDato;
import mx.constructora.estructura.ImportacionEstructura.UnidadDato;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Lecturas completas y altas marcadas con la carga, para la migración desde Excel. */
@Repository
public class RepositorioImportacionEstructura {

  private static final Set<String> TABLAS = Set.of("cliente", "frente", "contrato", "prototipo");

  private final JdbcClient jdbc;

  RepositorioImportacionEstructura(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public boolean existe(long empresa, String tabla, long id) {
    if (!TABLAS.contains(tabla)) {
      throw new IllegalArgumentException(tabla);
    }
    return jdbc.sql(
                "SELECT count(*) FROM estructura."
                    + tabla
                    + " WHERE empresa_id = :empresa AND id = :id")
            .param("empresa", empresa)
            .param("id", id)
            .query(Long.class)
            .single()
        > 0;
  }

  public List<FrenteDato> frentes(long empresa) {
    return jdbc.sql(
            """
            SELECT id, cliente_id, clave, nombre, compartido.normalizar(nombre) AS nombre_normal
              FROM estructura.frente WHERE empresa_id = :empresa ORDER BY id
            """)
        .param("empresa", empresa)
        .query(FrenteDato.class)
        .list();
  }

  public List<ContratoDato> contratos(long empresa) {
    return jdbc.sql(
            """
            SELECT id, codigo, frente_id, cliente_id
              FROM estructura.contrato WHERE empresa_id = :empresa ORDER BY id
            """)
        .param("empresa", empresa)
        .query(ContratoDato.class)
        .list();
  }

  public List<PrototipoDato> prototipos(long empresa) {
    return jdbc.sql(
            "SELECT id, clave FROM estructura.prototipo WHERE empresa_id = :empresa ORDER BY id")
        .param("empresa", empresa)
        .query(PrototipoDato.class)
        .list();
  }

  public List<UnidadDato> unidades(long empresa) {
    return jdbc.sql(
            """
            SELECT id, frente_id, contrato_id, clave, prototipo_id
              FROM estructura.unidad_obra WHERE empresa_id = :empresa ORDER BY id
            """)
        .param("empresa", empresa)
        .query(UnidadDato.class)
        .list();
  }

  public List<PaqueteDato> paquetes(long empresa) {
    return jdbc.sql(
            """
            SELECT id, cliente_id, padre_id, clave, nombre, nombre_normal, orden
              FROM estructura.paquete WHERE empresa_id = :empresa ORDER BY id
            """)
        .param("empresa", empresa)
        .query(PaqueteDato.class)
        .list();
  }

  public List<EquivalenciaDato> equivalencias(long empresa) {
    return jdbc.sql(
            """
            SELECT paquete_cliente_id, paquete_propio_id
              FROM estructura.paquete_equivalencia WHERE empresa_id = :empresa
            """)
        .param("empresa", empresa)
        .query(EquivalenciaDato.class)
        .list();
  }

  public List<PrecioDato> precios(long empresa) {
    return jdbc.sql(
            """
            SELECT contrato_id, paquete_id, prototipo_id
              FROM estructura.contrato_paquete WHERE empresa_id = :empresa
            """)
        .param("empresa", empresa)
        .query(PrecioDato.class)
        .list();
  }

  public long insertarFrente(long empresa, long carga, long cliente, String clave, String nombre) {
    return jdbc.sql(
            """
            INSERT INTO estructura.frente (empresa_id, cliente_id, clave, nombre, origen_carga)
            VALUES (:empresa, :cliente, :clave, :nombre, :carga)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("cliente", cliente)
        .param("clave", clave)
        .param("nombre", nombre)
        .param("carga", carga)
        .query(Long.class)
        .single();
  }

  public long insertarContrato(
      long empresa,
      long carga,
      long cliente,
      Long frente,
      String codigo,
      String numeroObra,
      LocalDate inicio,
      LocalDate fin,
      BigDecimal monto) {
    return jdbc.sql(
            """
            INSERT INTO estructura.contrato
              (empresa_id, cliente_id, frente_id, codigo, numero_obra, inicio, fin, monto, origen_carga)
            VALUES (:empresa, :cliente, :frente, :codigo, :numeroObra, :inicio, :fin, :monto, :carga)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("cliente", cliente)
        .param("frente", frente)
        .param("codigo", codigo)
        .param("numeroObra", numeroObra)
        .param("inicio", inicio)
        .param("fin", fin)
        .param("monto", monto)
        .param("carga", carga)
        .query(Long.class)
        .single();
  }

  public long insertarPrototipo(long empresa, long carga, String clave, String nombre) {
    return jdbc.sql(
            """
            INSERT INTO estructura.prototipo (empresa_id, clave, nombre, origen_carga)
            VALUES (:empresa, :clave, :nombre, :carga)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("clave", clave)
        .param("nombre", nombre)
        .param("carga", carga)
        .query(Long.class)
        .single();
  }

  public long insertarLote(
      long empresa,
      long carga,
      long contrato,
      Long frente,
      String clave,
      String manzana,
      String lote,
      Long prototipo) {
    return jdbc.sql(
            """
            INSERT INTO estructura.unidad_obra
              (empresa_id, contrato_id, frente_id, tipo_id, clave, manzana, lote, prototipo_id, origen_carga)
            VALUES (:empresa, :contrato, :frente, 1, :clave, :manzana, :lote, :prototipo, :carga)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("contrato", contrato)
        .param("frente", frente)
        .param("clave", clave)
        .param("manzana", manzana)
        .param("lote", lote)
        .param("prototipo", prototipo)
        .param("carga", carga)
        .query(Long.class)
        .single();
  }

  public long insertarPaquete(
      long empresa,
      long carga,
      Long cliente,
      Long padre,
      String clave,
      String nombre,
      String unidad,
      int orden) {
    return jdbc.sql(
            """
            INSERT INTO estructura.paquete
              (empresa_id, cliente_id, padre_id, clave, nombre, unidad, orden, origen_carga)
            VALUES (:empresa, :cliente, :padre, :clave, :nombre, :unidad, :orden, :carga)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("cliente", cliente)
        .param("padre", padre)
        .param("clave", clave)
        .param("nombre", nombre)
        .param("unidad", unidad)
        .param("orden", orden)
        .param("carga", carga)
        .query(Long.class)
        .single();
  }

  public void insertarEquivalencia(long empresa, long carga, long cliente, long propio) {
    jdbc.sql(
            """
            INSERT INTO estructura.paquete_equivalencia
              (empresa_id, paquete_cliente_id, paquete_propio_id, origen_carga)
            VALUES (:empresa, :cliente, :propio, :carga)
            ON CONFLICT DO NOTHING
            """)
        .param("empresa", empresa)
        .param("cliente", cliente)
        .param("propio", propio)
        .param("carga", carga)
        .update();
  }

  public void insertarPrecio(
      long empresa,
      long carga,
      long contrato,
      long paquete,
      Long prototipo,
      BigDecimal cobro,
      BigDecimal destajo) {
    jdbc.sql(
            """
            INSERT INTO estructura.contrato_paquete
              (empresa_id, contrato_id, paquete_id, prototipo_id, precio_cobro, precio_destajo,
               origen_carga)
            VALUES (:empresa, :contrato, :paquete, :prototipo, :cobro, :destajo, :carga)
            ON CONFLICT (contrato_id, paquete_id, prototipo_id) DO NOTHING
            """)
        .param("empresa", empresa)
        .param("contrato", contrato)
        .param("paquete", paquete)
        .param("prototipo", prototipo)
        .param("cobro", cobro)
        .param("destajo", destajo)
        .param("carga", carga)
        .update();
  }

  public int revertir(long carga) {
    return jdbc.sql("SELECT estructura.revertir_carga(:carga)")
        .param("carga", carga)
        .query(Integer.class)
        .single();
  }
}

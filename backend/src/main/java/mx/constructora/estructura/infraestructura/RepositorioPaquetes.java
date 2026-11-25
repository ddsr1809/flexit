package mx.constructora.estructura.infraestructura;

import java.util.List;
import java.util.Optional;
import mx.constructora.estructura.aplicacion.ServicioPaquetes.Equivalencia;
import mx.constructora.estructura.aplicacion.ServicioPaquetes.Paquete;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Paquetes (árbol por padre_id) y sus equivalencias cliente ↔ propio. */
@Repository
public class RepositorioPaquetes {

  private static final String PAQUETE =
      """
      SELECT p.id, p.cliente_id, c.nombre AS cliente, p.padre_id, p.clave, p.nombre, p.unidad,
             p.orden, p.activo, p.version
        FROM estructura.paquete p
        LEFT JOIN estructura.cliente c ON c.id = p.cliente_id
       WHERE p.empresa_id = :empresa
      """;

  private final JdbcClient jdbc;

  RepositorioPaquetes(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Paquetes de la empresa en orden de árbol: cada padre seguido de sus hijos, por orden y clave.
   */
  public List<Paquete> paquetes(long empresa, Long cliente, boolean soloPropios) {
    return jdbc.sql(
            """
            WITH RECURSIVE arbol AS (
              SELECT p.id, ARRAY[lpad(p.orden::text, 6, '0') || '|' || p.clave] AS ruta
                FROM estructura.paquete p
               WHERE p.empresa_id = :empresa AND p.padre_id IS NULL
              UNION ALL
              SELECT h.id, a.ruta || (lpad(h.orden::text, 6, '0') || '|' || h.clave)
                FROM estructura.paquete h
                JOIN arbol a ON h.padre_id = a.id
               WHERE h.empresa_id = :empresa
            )
            SELECT p.id, p.cliente_id, c.nombre AS cliente, p.padre_id, p.clave, p.nombre,
                   p.unidad, p.orden, p.activo, p.version
              FROM estructura.paquete p
              JOIN arbol a ON a.id = p.id
              LEFT JOIN estructura.cliente c ON c.id = p.cliente_id
             WHERE (CAST(:cliente AS bigint) IS NULL OR p.cliente_id = :cliente)
               AND (NOT :soloPropios OR p.cliente_id IS NULL)
             ORDER BY c.nombre NULLS FIRST, a.ruta
            """)
        .param("empresa", empresa)
        .param("cliente", cliente)
        .param("soloPropios", soloPropios)
        .query(Paquete.class)
        .list();
  }

  public Optional<Paquete> paquete(long empresa, long id) {
    return jdbc.sql(PAQUETE + " AND p.id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .query(Paquete.class)
        .optional();
  }

  /** Ids de todos los paquetes que cuelgan de {@code id}, a cualquier profundidad. */
  public List<Long> descendientes(long empresa, long id) {
    return jdbc.sql(
            """
            WITH RECURSIVE hijos AS (
              SELECT id FROM estructura.paquete WHERE empresa_id = :empresa AND padre_id = :id
              UNION
              SELECT p.id FROM estructura.paquete p JOIN hijos h ON p.padre_id = h.id
               WHERE p.empresa_id = :empresa
            )
            SELECT id FROM hijos
            """)
        .param("empresa", empresa)
        .param("id", id)
        .query(Long.class)
        .list();
  }

  public long insertar(
      long empresa,
      Long cliente,
      Long padre,
      String clave,
      String nombre,
      String unidad,
      int orden) {
    return jdbc.sql(
            """
            INSERT INTO estructura.paquete (empresa_id, cliente_id, padre_id, clave, nombre, unidad, orden)
            VALUES (:empresa, :cliente, :padre, :clave, :nombre, :unidad, :orden)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("cliente", cliente)
        .param("padre", padre)
        .param("clave", clave)
        .param("nombre", nombre)
        .param("unidad", unidad)
        .param("orden", orden)
        .query(Long.class)
        .single();
  }

  public int actualizar(
      long empresa,
      long id,
      int version,
      Long padre,
      String clave,
      String nombre,
      String unidad,
      int orden,
      boolean activo) {
    return jdbc.sql(
            """
            UPDATE estructura.paquete
               SET padre_id = :padre, clave = :clave, nombre = :nombre, unidad = :unidad,
                   orden = :orden, activo = :activo, version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("padre", padre)
        .param("clave", clave)
        .param("nombre", nombre)
        .param("unidad", unidad)
        .param("orden", orden)
        .param("activo", activo)
        .update();
  }

  // ------------------------------------------------------------------------------- Equivalencias

  public List<Equivalencia> equivalencias(long empresa, Long cliente) {
    return jdbc.sql(
            """
            SELECT e.paquete_cliente_id, e.paquete_propio_id
              FROM estructura.paquete_equivalencia e
              JOIN estructura.paquete p ON p.id = e.paquete_cliente_id
             WHERE e.empresa_id = :empresa
               AND (CAST(:cliente AS bigint) IS NULL OR p.cliente_id = :cliente)
             ORDER BY e.paquete_cliente_id, e.paquete_propio_id
            """)
        .param("empresa", empresa)
        .param("cliente", cliente)
        .query(Equivalencia.class)
        .list();
  }

  public List<Long> propiosEquivalentes(long empresa, long paqueteCliente) {
    return jdbc.sql(
            """
            SELECT paquete_propio_id FROM estructura.paquete_equivalencia
             WHERE empresa_id = :empresa AND paquete_cliente_id = :paquete
            """)
        .param("empresa", empresa)
        .param("paquete", paqueteCliente)
        .query(Long.class)
        .list();
  }

  public void agregarEquivalencia(long empresa, long paqueteCliente, long paquetePropio) {
    jdbc.sql(
            """
            INSERT INTO estructura.paquete_equivalencia (empresa_id, paquete_cliente_id, paquete_propio_id)
            VALUES (:empresa, :cliente, :propio)
            """)
        .param("empresa", empresa)
        .param("cliente", paqueteCliente)
        .param("propio", paquetePropio)
        .update();
  }

  public void quitarEquivalencia(long empresa, long paqueteCliente, long paquetePropio) {
    jdbc.sql(
            """
            DELETE FROM estructura.paquete_equivalencia
             WHERE empresa_id = :empresa AND paquete_cliente_id = :cliente
               AND paquete_propio_id = :propio
            """)
        .param("empresa", empresa)
        .param("cliente", paqueteCliente)
        .param("propio", paquetePropio)
        .update();
  }
}

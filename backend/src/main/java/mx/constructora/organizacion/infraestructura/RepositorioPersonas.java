package mx.constructora.organizacion.infraestructura;

import java.util.List;
import java.util.Optional;
import mx.constructora.organizacion.Personas.PersonaDato;
import mx.constructora.organizacion.aplicacion.ServicioPersonas.Persona;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Acceso a {@code organizacion.persona}. Doble filtro por empresa: condición explícita y RLS. */
@Repository
public class RepositorioPersonas {

  private static final String COLUMNAS =
      """
      SELECT id, nombre, nss, curp, revisar, nota, activa, origen_carga IS NOT NULL AS importada,
             version
        FROM organizacion.persona
      """;

  private final JdbcClient jdbc;

  RepositorioPersonas(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<PersonaDato> activas(long empresa) {
    return jdbc.sql(
            """
            SELECT id, nombre, nombre_normal FROM organizacion.persona
             WHERE empresa_id = :empresa AND activa ORDER BY id
            """)
        .param("empresa", empresa)
        .query(PersonaDato.class)
        .list();
  }

  /** Por palabras sueltas del nombre, NSS o CURP; las marcadas para revisión primero. */
  public List<Persona> buscar(long empresa, String texto, boolean soloRevisar, int limite) {
    return jdbc.sql(
            COLUMNAS
                + """
                 WHERE empresa_id = :empresa
                   AND (NOT :soloRevisar OR revisar)
                   AND (CAST(:texto AS text) IS NULL
                        OR nss = :texto OR curp = upper(:texto)
                        OR NOT EXISTS (
                             SELECT 1
                               FROM unnest(string_to_array(compartido.normalizar(:texto), ' ')) AS p(palabra)
                              WHERE strpos(nombre_normal, p.palabra) = 0))
                 ORDER BY revisar DESC, nombre_normal
                 LIMIT :limite
                """)
        .param("empresa", empresa)
        .param("texto", texto)
        .param("soloRevisar", soloRevisar)
        .param("limite", limite)
        .query(Persona.class)
        .list();
  }

  public Optional<Persona> persona(long empresa, long id) {
    return jdbc.sql(COLUMNAS + " WHERE empresa_id = :empresa AND id = :id")
        .param("empresa", empresa)
        .param("id", id)
        .query(Persona.class)
        .optional();
  }

  public long insertar(long empresa, Long carga, String nombre, boolean revisar, String nota) {
    return jdbc.sql(
            """
            INSERT INTO organizacion.persona (empresa_id, nombre, revisar, nota, origen_carga)
            VALUES (:empresa, :nombre, :revisar, :nota, :carga)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("nombre", nombre)
        .param("revisar", revisar)
        .param("nota", nota)
        .param("carga", carga)
        .query(Long.class)
        .single();
  }

  public int actualizar(
      long empresa,
      long id,
      int version,
      String nombre,
      String nss,
      String curp,
      boolean revisar,
      String nota,
      boolean activa) {
    return jdbc.sql(
            """
            UPDATE organizacion.persona
               SET nombre = :nombre, nss = :nss, curp = :curp, revisar = :revisar, nota = :nota,
                   activa = :activa, version = version + 1
             WHERE empresa_id = :empresa AND id = :id AND version = :version
            """)
        .param("empresa", empresa)
        .param("id", id)
        .param("version", version)
        .param("nombre", nombre)
        .param("nss", nss)
        .param("curp", curp)
        .param("revisar", revisar)
        .param("nota", nota)
        .param("activa", activa)
        .update();
  }

  public int revertir(long carga) {
    return jdbc.sql("SELECT organizacion.revertir_carga_personas(:carga)")
        .param("carga", carga)
        .query(Integer.class)
        .single();
  }
}

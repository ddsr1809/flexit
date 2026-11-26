package mx.constructora.compartido.infraestructura;

import java.util.Optional;
import mx.constructora.compartido.Almacenamiento.Contenido;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Acceso a {@code compartido.archivo}. */
@Repository
public class RepositorioArchivos {

  private final JdbcClient jdbc;

  RepositorioArchivos(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public long insertar(
      long empresa, String nombre, String tipo, byte[] contenido, String sha256, long usuario) {
    return jdbc.sql(
            """
            INSERT INTO compartido.archivo (empresa_id, nombre, tipo, tamano, sha256, contenido, usuario_id)
            VALUES (:empresa, :nombre, :tipo, :tamano, :sha256, :contenido, :usuario)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("nombre", nombre)
        .param("tipo", tipo)
        .param("tamano", contenido.length)
        .param("sha256", sha256)
        .param("contenido", contenido)
        .param("usuario", usuario)
        .query(Long.class)
        .single();
  }

  public boolean existe(long empresa, long id) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM compartido.archivo WHERE empresa_id = :empresa AND id ="
                + " :id)")
        .param("empresa", empresa)
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  public Optional<Contenido> leer(long empresa, long id) {
    return jdbc.sql(
            """
            SELECT nombre, tipo, contenido AS bytes FROM compartido.archivo
             WHERE empresa_id = :empresa AND id = :id
            """)
        .param("empresa", empresa)
        .param("id", id)
        .query(Contenido.class)
        .optional();
  }
}

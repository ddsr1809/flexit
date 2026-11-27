package mx.constructora.organizacion.infraestructura;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Acceso a {@code organizacion.segundo_factor}. Filtra por la empresa dos veces: la condición
 * explícita y la política de RLS. Las actualizaciones de {@code ultimo_paso} son condicionales: dos
 * peticiones con el mismo código no pasan las dos.
 */
@Repository
public class RepositorioSegundoFactor {

  /** El secreto cifrado y si ya se confirmó con un primer código. */
  public record FilaSegundoFactor(byte[] secreto, boolean confirmado, long ultimoPaso) {}

  private final JdbcClient jdbc;

  RepositorioSegundoFactor(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<FilaSegundoFactor> buscar(long empresaId, long usuarioId) {
    return jdbc.sql(
            """
            SELECT secreto, confirmado IS NOT NULL AS confirmado, ultimo_paso
              FROM organizacion.segundo_factor
             WHERE empresa_id = :empresa AND usuario_id = :usuario
            """)
        .param("empresa", empresaId)
        .param("usuario", usuarioId)
        .query(
            (rs, n) ->
                new FilaSegundoFactor(
                    rs.getBytes("secreto"), rs.getBoolean("confirmado"), rs.getLong("ultimo_paso")))
        .optional();
  }

  /** Empieza (o reinicia) el alta. {@code false} si el usuario ya tiene uno confirmado. */
  public boolean iniciarAlta(long empresaId, long usuarioId, byte[] secreto) {
    return jdbc.sql(
                """
                INSERT INTO organizacion.segundo_factor (empresa_id, usuario_id, secreto)
                VALUES (:empresa, :usuario, :secreto)
                ON CONFLICT (usuario_id) DO UPDATE
                   SET secreto = EXCLUDED.secreto, ultimo_paso = 0, creado = now()
                 WHERE organizacion.segundo_factor.confirmado IS NULL
                """)
            .param("empresa", empresaId)
            .param("usuario", usuarioId)
            .param("secreto", secreto)
            .update()
        == 1;
  }

  /** Confirma el alta con el primer código válido. */
  public boolean confirmar(long empresaId, long usuarioId, long paso) {
    return jdbc.sql(
                """
                UPDATE organizacion.segundo_factor
                   SET confirmado = now(), ultimo_paso = :paso
                 WHERE empresa_id = :empresa AND usuario_id = :usuario
                   AND confirmado IS NULL AND ultimo_paso < :paso
                """)
            .param("empresa", empresaId)
            .param("usuario", usuarioId)
            .param("paso", paso)
            .update()
        == 1;
  }

  /** Marca la ventana como usada. {@code false} si ese código (o uno posterior) ya se usó. */
  public boolean usarPaso(long empresaId, long usuarioId, long paso) {
    return jdbc.sql(
                """
                UPDATE organizacion.segundo_factor
                   SET ultimo_paso = :paso
                 WHERE empresa_id = :empresa AND usuario_id = :usuario
                   AND confirmado IS NOT NULL AND ultimo_paso < :paso
                """)
            .param("empresa", empresaId)
            .param("usuario", usuarioId)
            .param("paso", paso)
            .update()
        == 1;
  }

  /**
   * Borra el segundo factor del usuario: en su siguiente inicio de sesión lo vuelve a dar de alta.
   */
  public boolean borrar(long empresaId, long usuarioId) {
    return jdbc.sql(
                "DELETE FROM organizacion.segundo_factor"
                    + " WHERE empresa_id = :empresa AND usuario_id = :usuario")
            .param("empresa", empresaId)
            .param("usuario", usuarioId)
            .update()
        == 1;
  }

  /** Usuarios de la empresa con segundo factor confirmado. */
  public List<Long> conSegundoFactor(long empresaId) {
    return jdbc.sql(
            "SELECT usuario_id FROM organizacion.segundo_factor"
                + " WHERE empresa_id = :empresa AND confirmado IS NOT NULL")
        .param("empresa", empresaId)
        .query(Long.class)
        .list();
  }
}

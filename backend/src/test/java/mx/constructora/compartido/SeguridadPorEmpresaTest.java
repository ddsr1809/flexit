package mx.constructora.compartido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import mx.constructora.PruebaIntegracion;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La empresa se filtra en PostgreSQL además de en la aplicación: aunque una consulta olvide el
 * {@code WHERE empresa_id}, la política de RLS no deja ver ni escribir filas de otra empresa.
 *
 * <p>El motivo se revisa en la causa raíz (el mensaje de PostgreSQL): desde Spring 7 la excepción
 * traducida ya no lo copia en su propio mensaje.
 */
class SeguridadPorEmpresaTest extends PruebaIntegracion {

  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate transaccion;

  private Empresa empresaA;
  private Empresa empresaB;
  private long usuarioA;

  @BeforeEach
  void preparar() {
    empresaA = crearEmpresa("RLS A");
    empresaB = crearEmpresa("RLS B");
    usuarioA = crearUsuario(empresaA, unico("rls.a"), "contrasena-rls-1", "admin");
    crearUsuario(empresaA, unico("rls.a2"), "contrasena-rls-1", "almacenista");
    crearUsuario(empresaB, unico("rls.b"), "contrasena-rls-1", "admin");
  }

  private long contarUsuariosSinFiltro() {
    // Sin WHERE a propósito: lo que se ve depende solo de la política.
    return jdbc.sql("SELECT count(*) FROM organizacion.usuario").query(Long.class).single();
  }

  @Test
  void sinEmpresaEnElContextoNoSeVeNingunaFila() {
    assertThat(contarUsuariosSinFiltro()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM organizacion.empresa").query(Long.class).single())
        .isZero();
  }

  @Test
  void cadaEmpresaSoloVeSusFilas() {
    long vistosPorA = ContextoEmpresa.como(empresaA.id(), usuarioA, this::contarUsuariosSinFiltro);
    long vistosPorB = ContextoEmpresa.como(empresaB.id(), 0, this::contarUsuariosSinFiltro);

    assertThat(vistosPorA).isEqualTo(2);
    assertThat(vistosPorB).isEqualTo(1);
  }

  @Test
  void nadiePuedeEscribirEnOtraEmpresa() {
    assertThatThrownBy(
            () ->
                ContextoEmpresa.ejecutarComo(
                    empresaA.id(),
                    usuarioA,
                    () ->
                        jdbc.sql(
                                """
                                INSERT INTO organizacion.usuario
                                  (empresa_id, usuario, nombre, hash_contrasena)
                                VALUES (:empresa, :usuario, 'Intruso', 'x')
                                """)
                            .param("empresa", empresaB.id())
                            .param("usuario", unico("intruso"))
                            .update()))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("row-level security");
  }

  @Test
  void nadiePuedeModificarFilasDeOtraEmpresa() {
    int modificadas =
        ContextoEmpresa.como(
            empresaA.id(),
            usuarioA,
            () ->
                jdbc.sql(
                        "UPDATE organizacion.usuario SET nombre = 'cambiado' WHERE empresa_id = :b")
                    .param("b", empresaB.id())
                    .update());
    assertThat(modificadas).isZero();
  }

  @Test
  void unaConexionRecicladaNoConservaLaEmpresaAnterior() {
    // Con el pool, la siguiente operación puede recibir la misma conexión física.
    for (int i = 0; i < 30; i++) {
      ContextoEmpresa.como(empresaA.id(), usuarioA, this::contarUsuariosSinFiltro);
      assertThat(contarUsuariosSinFiltro()).isZero();
    }
  }

  @Test
  void elContextoSeFijaAlTomarLaConexionNoDentroDeLaTransaccion() {
    long vistos =
        ContextoEmpresa.como(
            empresaA.id(), usuarioA, () -> transaccion.execute(s -> contarUsuariosSinFiltro()));
    assertThat(vistos).isEqualTo(2);
  }

  @Test
  void laAppNoPuedeBorrarNiCambiarLaBitacora() {
    ContextoEmpresa.ejecutarComo(
        empresaA.id(),
        usuarioA,
        () -> {
          assertThatThrownBy(() -> jdbc.sql("DELETE FROM compartido.bitacora").update())
              .isInstanceOf(DataAccessException.class)
              .rootCause()
              .hasMessageContaining("permission denied");
          assertThatThrownBy(
                  () -> jdbc.sql("UPDATE compartido.bitacora SET operacion = 'INSERT'").update())
              .isInstanceOf(DataAccessException.class)
              .rootCause()
              .hasMessageContaining("permission denied");
        });
  }

  @Test
  void laAppNoPuedeCrearTablas() {
    assertThatThrownBy(() -> jdbc.sql("CREATE TABLE organizacion.atajo (x int)").update())
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
  }

  @Test
  void laAppPuedeValidarElEsquemaConSuPropioRol() {
    var resultado =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), "app", "app-prueba")
            .locations("classpath:db/migration")
            .load()
            .validateWithResult();
    assertThat(resultado.validationSuccessful).as(resultado.getAllErrorMessages()).isTrue();
  }
}

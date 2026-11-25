package mx.constructora.compartido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import mx.constructora.PruebaIntegracion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class BitacoraYFoliosTest extends PruebaIntegracion {

  @Autowired Folios folios;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate transaccion;

  private Empresa empresa;
  private long usuario;

  @BeforeEach
  void preparar() {
    empresa = crearEmpresa("Folios");
    usuario = crearUsuario(empresa, unico("folios"), "contrasena-folios", "almacenista");
  }

  private long siguiente(String tipo, int anio) {
    return ContextoEmpresa.como(
        empresa.id(), usuario, () -> transaccion.execute(s -> folios.consecutivo(tipo, anio)));
  }

  @Test
  void cincuentaDocumentosSimultaneosRecibenFoliosDistintosYSinHuecos() throws Exception {
    int hilos = 50;
    CountDownLatch salida = new CountDownLatch(1);
    List<Future<Long>> resultados = new ArrayList<>();
    try (var ejecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < hilos; i++) {
        resultados.add(
            ejecutor.submit(
                () -> {
                  salida.await();
                  return siguiente("VAL", 2026);
                }));
      }
      salida.countDown();
    }
    Set<Long> obtenidos = new TreeSet<>();
    for (Future<Long> resultado : resultados) {
      obtenidos.add(resultado.get());
    }
    assertThat(obtenidos)
        .containsExactlyElementsOf(LongStream.rangeClosed(1, hilos).boxed().toList());
  }

  @Test
  void unFolioDeUnaTransaccionRevertidaSeReutiliza() {
    assertThat(siguiente("ENT", 2026)).isEqualTo(1);
    ContextoEmpresa.ejecutarComo(
        empresa.id(),
        usuario,
        () ->
            transaccion.executeWithoutResult(
                estado -> {
                  folios.consecutivo("ENT", 2026);
                  estado.setRollbackOnly();
                }));
    assertThat(siguiente("ENT", 2026)).isEqualTo(2);
  }

  @Test
  void cadaTipoYCadaAnioLlevanSuConsecutivo() {
    assertThat(siguiente("ENT", 2026)).isEqualTo(1);
    assertThat(siguiente("ENT", 2026)).isEqualTo(2);
    assertThat(siguiente("SAL", 2026)).isEqualTo(1);
    assertThat(siguiente("ENT", 2027)).isEqualTo(1);
  }

  @Test
  void elFolioTieneFormatoFijo() {
    assertThat(Folios.formatear("ENT", 2027, 123)).isEqualTo("ENT-2027-000123");
  }

  @Test
  void unFolioFueraDeTransaccionSeRechaza() {
    assertThatThrownBy(
            () -> ContextoEmpresa.como(empresa.id(), usuario, () -> folios.siguiente("ENT")))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void laBitacoraGuardaAntesDespuesYQuienHizoElCambio() {
    long otro = crearUsuario(empresa, unico("cambio"), "contrasena-cambio", "residente");
    ContextoEmpresa.ejecutarComo(
        empresa.id(),
        usuario,
        () ->
            transaccion.executeWithoutResult(
                s ->
                    jdbc.sql(
                            "UPDATE organizacion.usuario SET nombre = 'Nombre nuevo' WHERE id ="
                                + " :id")
                        .param("id", otro)
                        .update()));

    List<Map<String, Object>> renglones =
        comoMigrador()
            .sql(
                """
                SELECT operacion, antes ->> 'nombre' AS antes, despues ->> 'nombre' AS despues,
                       usuario_id, (despues -> 'hash_contrasena') IS NOT NULL AS con_hash
                  FROM compartido.bitacora
                 WHERE tabla = 'usuario' AND registro_id = :id
                 ORDER BY id
                """)
            .param("id", otro)
            .query()
            .listOfRows();

    assertThat(renglones).extracting(r -> r.get("operacion")).containsExactly("INSERT", "UPDATE");
    Map<String, Object> cambio = renglones.get(1);
    assertThat(cambio.get("despues")).isEqualTo("Nombre nuevo");
    assertThat(((Number) cambio.get("usuario_id")).longValue()).isEqualTo(usuario);
    assertThat(renglones).allSatisfy(r -> assertThat(r.get("con_hash")).isEqualTo(false));
  }

  @Test
  void losRolesDeLaBaseCoincidenConElEnum() {
    Set<String> enBase =
        new TreeSet<>(
            comoMigrador().sql("SELECT clave FROM organizacion.rol").query(String.class).list());
    Set<String> enCodigo =
        java.util.Arrays.stream(mx.constructora.organizacion.Rol.values())
            .map(mx.constructora.organizacion.Rol::clave)
            .collect(Collectors.toCollection(TreeSet::new));
    assertThat(enBase).isEqualTo(enCodigo);
  }
}

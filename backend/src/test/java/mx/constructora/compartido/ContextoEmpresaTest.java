package mx.constructora.compartido;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * El contexto de seguridad se carga de forma diferida y, en una petición web, cargarlo lee la
 * sesión de la base. Esa lectura pide una conexión, y la conexión pregunta por la empresa. La
 * pregunta anidada debe responder "sin empresa" en lugar de volver a cargar el contexto: si no,
 * cada vuelta toma otra conexión hasta agotar el pool.
 */
class ContextoEmpresaTest {

  private static final UsuarioAutenticado USUARIO =
      new UsuarioAutenticado() {
        @Override
        public long usuarioId() {
          return 7;
        }

        @Override
        public long empresaId() {
          return 3;
        }
      };

  @AfterEach
  void limpiar() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void cargarElContextoNoVuelveAPreguntarPorLaEmpresa() {
    AtomicReference<Object> anidada = new AtomicReference<>();
    SecurityContextHolder.setDeferredContext(
        () -> {
          // Así lo hace Spring Session: pide una conexión y DataSourceConEmpresa pregunta aquí.
          anidada.set(ContextoEmpresa.actual());
          SecurityContext contexto = SecurityContextHolder.createEmptyContext();
          contexto.setAuthentication(new TestingAuthenticationToken(USUARIO, null, "x"));
          return contexto;
        });

    assertThat(ContextoEmpresa.actual()).contains(new ContextoEmpresa.Identidad(3, 7));
    assertThat(anidada.get()).isEqualTo(Optional.empty());
    // Terminada la carga, se puede volver a preguntar con normalidad.
    assertThat(ContextoEmpresa.actual()).contains(new ContextoEmpresa.Identidad(3, 7));
  }

  @Test
  void laEmpresaForzadaGanaSobreLaSesion() {
    SecurityContext contexto = SecurityContextHolder.createEmptyContext();
    contexto.setAuthentication(new TestingAuthenticationToken(USUARIO, null, "x"));
    SecurityContextHolder.setContext(contexto);

    assertThat(ContextoEmpresa.como(9, 1, ContextoEmpresa::actual))
        .contains(new ContextoEmpresa.Identidad(9, 1));
    assertThat(ContextoEmpresa.actual()).contains(new ContextoEmpresa.Identidad(3, 7));
  }
}

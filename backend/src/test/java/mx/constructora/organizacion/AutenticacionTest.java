package mx.constructora.organizacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import mx.constructora.ClienteHttp;
import mx.constructora.PruebaIntegracion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Sesión, CSRF, bloqueo por intentos, roles y separación entre empresas, por HTTP real. */
class AutenticacionTest extends PruebaIntegracion {

  private static final String CONTRASENA = "contrasena-de-prueba";

  private Empresa empresaA;
  private Empresa empresaB;
  private String adminA;
  private String almacenA;
  private String adminB;

  @BeforeEach
  void prepararDosEmpresas() {
    empresaA = crearEmpresa("Empresa A");
    empresaB = crearEmpresa("Empresa B");
    adminA = unico("admin.a");
    almacenA = unico("almacen.a");
    adminB = unico("admin.b");
    crearUsuario(empresaA, adminA, CONTRASENA, "admin");
    crearUsuario(empresaA, almacenA, CONTRASENA, "almacenista");
    crearUsuario(empresaB, adminB, CONTRASENA, "admin");
  }

  @Test
  void sinSesionLaApiResponde401() {
    assertThat(cliente().get("/api/v1/auth/yo").estado()).isEqualTo(401);
    assertThat(cliente().get("/api/v1/usuarios").estado()).isEqualTo(401);
  }

  @Test
  void laVersionYLaSaludSonPublicas() {
    assertThat(cliente().get("/api/v1/version").estado()).isEqualTo(200);
    assertThat(cliente().get("/actuator/health/readiness").estado()).isEqualTo(200);
  }

  @Test
  void conUsuarioYContrasenaCorrectosHaySesion() {
    ClienteHttp navegador = cliente();
    ClienteHttp.Respuesta login = navegador.iniciarSesion(adminA, CONTRASENA);

    assertThat(login.estado()).isEqualTo(200);
    assertThat(login.<String>json("$.empresa")).isEqualTo(empresaA.nombre());
    assertThat(login.<List<String>>json("$.roles[*].clave")).containsExactly("admin");
    assertThat(login.<List<String>>json("$.permisos")).contains("usuarios:administrar");
    assertThat(navegador.tieneCookie("SESION")).isTrue();

    ClienteHttp.Respuesta yo = navegador.get("/api/v1/auth/yo");
    assertThat(yo.estado()).isEqualTo(200);
    assertThat(yo.<String>json("$.usuario")).isEqualTo(adminA);
  }

  @Test
  void elUsuarioNoDistingueMayusculas() {
    assertThat(cliente().iniciarSesion(adminA.toUpperCase(), CONTRASENA).estado()).isEqualTo(200);
  }

  @Test
  void sinTokenCsrfUnPostEsRechazado() {
    ClienteHttp navegador = cliente().sinCsrf();
    navegador.get("/api/v1/auth/csrf");
    ClienteHttp.Respuesta login =
        navegador.post(
            "/api/v1/auth/login",
            "{\"usuario\":\"%s\",\"contrasena\":\"%s\"}".formatted(adminA, CONTRASENA));
    assertThat(login.estado()).isEqualTo(403);
  }

  @Test
  void contrasenaIncorrectaNoDiceSiElUsuarioExiste() {
    ClienteHttp.Respuesta malaContrasena = cliente().iniciarSesion(adminA, "no-es-esta-1234");
    ClienteHttp.Respuesta noExiste = cliente().iniciarSesion("nadie-" + adminA, CONTRASENA);

    assertThat(malaContrasena.estado()).isEqualTo(401);
    assertThat(malaContrasena.<String>json("$.codigo")).isEqualTo("CREDENCIALES_INVALIDAS");
    assertThat(noExiste.<String>json("$.codigo")).isEqualTo("CREDENCIALES_INVALIDAS");
  }

  @Test
  void cincoIntentosFallidosBloqueanLaCuenta() {
    for (int i = 0; i < 5; i++) {
      assertThat(cliente().iniciarSesion(almacenA, "equivocada-" + i).<String>json("$.codigo"))
          .isEqualTo("CREDENCIALES_INVALIDAS");
    }
    ClienteHttp.Respuesta conLaCorrecta = cliente().iniciarSesion(almacenA, CONTRASENA);
    assertThat(conLaCorrecta.estado()).isEqualTo(401);
    assertThat(conLaCorrecta.<String>json("$.codigo")).isEqualTo("CUENTA_BLOQUEADA");
  }

  @Test
  void unAlmacenistaNoAdministraUsuarios() {
    ClienteHttp navegador = cliente();
    navegador.iniciarSesion(almacenA, CONTRASENA);

    ClienteHttp.Respuesta usuarios = navegador.get("/api/v1/usuarios");
    assertThat(usuarios.estado()).isEqualTo(403);
    assertThat(usuarios.<String>json("$.codigo")).isEqualTo("SIN_PERMISO");
  }

  @Test
  void cadaAdministradorSoloVeLosUsuariosDeSuEmpresa() {
    ClienteHttp navegadorA = cliente();
    navegadorA.iniciarSesion(adminA, CONTRASENA);
    ClienteHttp navegadorB = cliente();
    navegadorB.iniciarSesion(adminB, CONTRASENA);

    List<String> deA = navegadorA.get("/api/v1/usuarios").json("$[*].usuario");
    List<String> deB = navegadorB.get("/api/v1/usuarios").json("$[*].usuario");

    assertThat(deA).containsExactlyInAnyOrder(adminA, almacenA);
    assertThat(deB).containsExactly(adminB);
  }

  @Test
  void elAdministradorDaDeAltaUnUsuarioQueYaPuedeEntrar() {
    ClienteHttp navegador = cliente();
    navegador.iniciarSesion(adminA, CONTRASENA);
    String nuevo = unico("residente");

    ClienteHttp.Respuesta alta =
        navegador.post(
            "/api/v1/usuarios",
            """
            {"usuario":"%s","nombre":"Residente de prueba","contrasena":"%s","roles":["residente"]}
            """
                .formatted(nuevo, CONTRASENA));
    assertThat(alta.estado()).isEqualTo(201);
    assertThat(alta.<List<String>>json("$.roles[*].clave")).containsExactly("residente");

    ClienteHttp.Respuesta repetido =
        navegador.post(
            "/api/v1/usuarios",
            """
            {"usuario":"%s","nombre":"Otra vez","contrasena":"%s","roles":["residente"]}
            """
                .formatted(nuevo, CONTRASENA));
    assertThat(repetido.estado()).isEqualTo(409);
    assertThat(repetido.<String>json("$.codigo")).isEqualTo("USUARIO_DUPLICADO");

    ClienteHttp.Respuesta entra = cliente().iniciarSesion(nuevo, CONTRASENA);
    assertThat(entra.estado()).isEqualTo(200);
    assertThat(entra.<String>json("$.empresa")).isEqualTo(empresaA.nombre());
  }

  @Test
  void unaAltaConDatosInvalidosExplicaCadaCampo() {
    ClienteHttp navegador = cliente();
    navegador.iniciarSesion(adminA, CONTRASENA);

    ClienteHttp.Respuesta alta =
        navegador.post(
            "/api/v1/usuarios",
            "{\"usuario\":\"x\",\"nombre\":\"\",\"contrasena\":\"corta\",\"roles\":[]}");

    assertThat(alta.estado()).isEqualTo(400);
    assertThat(alta.<String>json("$.codigo")).isEqualTo("VALIDACION");
    assertThat(alta.<java.util.Map<String, String>>json("$.campos"))
        .containsKeys("usuario", "nombre", "contrasena", "roles");
  }

  @Test
  void cerrarSesionLaTermina() {
    ClienteHttp navegador = cliente();
    navegador.iniciarSesion(adminA, CONTRASENA);
    assertThat(navegador.get("/api/v1/auth/yo").estado()).isEqualTo(200);

    assertThat(navegador.post("/api/v1/auth/logout", null).estado()).isEqualTo(204);
    assertThat(navegador.get("/api/v1/auth/yo").estado()).isEqualTo(401);
  }

  @Test
  void laContrasenaSeGuardaConArgon2() {
    String hash =
        comoMigrador()
            .sql("SELECT hash_contrasena FROM organizacion.usuario WHERE usuario = :u")
            .param("u", adminA)
            .query(String.class)
            .single();
    assertThat(hash).startsWith("{argon2}$argon2id$");
  }
}

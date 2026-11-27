package mx.constructora.organizacion.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import mx.constructora.ClienteHttp;
import mx.constructora.PruebaIntegracion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Segundo factor obligatorio para administración y dirección (H7), por HTTP real: alta con el
 * primer código, login en dos pasos, código que no se reutiliza, restablecimiento y bloqueo.
 */
@TestPropertySource(
    properties = {
      "app.segundo-factor.exigir=true",
      // 32 bytes de prueba en Base64; en producción sale de APP_LLAVE_SEGUNDO_FACTOR.
      "app.segundo-factor.llave=cHJ1ZWJhcy1zZWd1bmRvLWZhY3Rvci0zMi1ieXRlcyE="
    })
class SegundoFactorTest extends PruebaIntegracion {

  private static final String CONTRASENA = "contrasena-de-prueba";

  private Empresa empresa;
  private String admin;
  private String otroAdmin;
  private String almacen;

  @BeforeEach
  void preparar() {
    empresa = crearEmpresa("Empresa 2FA");
    admin = unico("admin.2fa");
    otroAdmin = unico("admin2.2fa");
    almacen = unico("almacen.2fa");
    crearUsuario(empresa, admin, CONTRASENA, "admin");
    crearUsuario(empresa, otroAdmin, CONTRASENA, "admin");
    crearUsuario(empresa, almacen, CONTRASENA, "almacenista");
  }

  @Test
  void quienNoLoNecesitaEntraConContrasena() {
    assertThat(cliente().iniciarSesion(almacen, CONTRASENA).estado()).isEqualTo(200);
  }

  @Test
  void administracionLoDaDeAltaAntesDeEntrar() {
    ClienteHttp navegador = cliente();
    ClienteHttp.Respuesta login = navegador.iniciarSesion(admin, CONTRASENA);
    assertThat(login.estado()).isEqualTo(202);
    assertThat(login.<String>json("$.paso")).isEqualTo("alta");
    // Con la contraseña sola no hay sesión.
    assertThat(navegador.get("/api/v1/auth/yo").estado()).isEqualTo(401);
    assertThat(navegador.get("/api/v1/usuarios").estado()).isEqualTo(401);

    ClienteHttp.Respuesta alta = navegador.post("/api/v1/auth/segundo-factor/alta", "{}");
    assertThat(alta.estado()).isEqualTo(200);
    String secreto = alta.json("$.secreto");
    assertThat(alta.<String>json("$.uri")).startsWith("otpauth://totp/").contains(secreto);

    ClienteHttp.Respuesta malo = navegador.post(confirmar(), codigo("000000"));
    assertThat(malo.estado()).isEqualTo(401);
    assertThat(malo.<String>json("$.codigo")).isEqualTo("CODIGO_INVALIDO");

    long paso = Totp.paso(Instant.now());
    ClienteHttp.Respuesta bueno =
        navegador.post(confirmar(), codigo(Totp.codigo(base32(secreto), paso)));
    assertThat(bueno.estado()).isEqualTo(200);
    assertThat(bueno.<String>json("$.usuario")).isEqualTo(admin);
    assertThat(navegador.get("/api/v1/auth/yo").estado()).isEqualTo(200);

    // Siguiente inicio de sesión: pide el código, y el que ya se usó no sirve otra vez.
    ClienteHttp otro = cliente();
    ClienteHttp.Respuesta segundo = otro.iniciarSesion(admin, CONTRASENA);
    assertThat(segundo.estado()).isEqualTo(202);
    assertThat(segundo.<String>json("$.paso")).isEqualTo("codigo");
    String usado = Totp.codigo(base32(secreto), paso);
    assertThat(otro.post("/api/v1/auth/segundo-factor", codigo(usado)).estado()).isEqualTo(401);
    String siguiente = Totp.codigo(base32(secreto), paso + 1);
    ClienteHttp.Respuesta entra = otro.post("/api/v1/auth/segundo-factor", codigo(siguiente));
    assertThat(entra.estado()).isEqualTo(200);
    assertThat(otro.get("/api/v1/usuarios").estado()).isEqualTo(200);
  }

  @Test
  void sinContrasenaNoHaySegundoPaso() {
    ClienteHttp navegador = cliente();
    navegador.get("/api/v1/auth/csrf");
    ClienteHttp.Respuesta respuesta =
        navegador.post("/api/v1/auth/segundo-factor", codigo("123456"));
    assertThat(respuesta.estado()).isEqualTo(401);
    assertThat(respuesta.<String>json("$.codigo")).isEqualTo("SEGUNDO_FACTOR_VENCIDO");
    assertThat(navegador.post("/api/v1/auth/segundo-factor/alta", "{}").estado()).isEqualTo(401);
  }

  @Test
  void otroAdministradorLoRestablece() {
    String secreto = darDeAlta(admin);
    ClienteHttp administracion = entrar(otroAdmin);
    long id = idDe(admin);

    ClienteHttp.Respuesta lista = administracion.get("/api/v1/usuarios");
    assertThat(
            lista.<java.util.List<Boolean>>json("$[?(@.usuario == '" + admin + "')].segundoFactor"))
        .containsExactly(true);

    assertThat(administracion.delete("/api/v1/usuarios/" + id + "/segundo-factor").estado())
        .isEqualTo(204);
    ClienteHttp.Respuesta login = cliente().iniciarSesion(admin, CONTRASENA);
    assertThat(login.<String>json("$.paso")).isEqualTo("alta");
    assertThat(secreto).isNotBlank();

    // El propio no se restablece.
    long propio = idDe(otroAdmin);
    ClienteHttp.Respuesta propioRespuesta =
        administracion.delete("/api/v1/usuarios/" + propio + "/segundo-factor");
    assertThat(propioRespuesta.estado()).isEqualTo(409);
  }

  @Test
  void cincoCodigosMalosObliganAEmpezarDeNuevo() {
    darDeAlta(admin);
    ClienteHttp navegador = cliente();
    assertThat(navegador.iniciarSesion(admin, CONTRASENA).estado()).isEqualTo(202);
    for (int i = 0; i < 5; i++) {
      assertThat(navegador.post("/api/v1/auth/segundo-factor", codigo("000000")).estado())
          .isEqualTo(401);
    }
    ClienteHttp.Respuesta despues = navegador.post("/api/v1/auth/segundo-factor", codigo("000000"));
    assertThat(despues.estado()).isEqualTo(401);
    assertThat(despues.<String>json("$.codigo")).isIn("SEGUNDO_FACTOR_VENCIDO", "CUENTA_BLOQUEADA");
    // La cuenta quedó bloqueada 15 minutos, como con 5 contraseñas equivocadas.
    assertThat(cliente().iniciarSesion(admin, CONTRASENA).<String>json("$.codigo"))
        .isEqualTo("CUENTA_BLOQUEADA");
  }

  // ------------------------------------------------------------------------------ apoyo

  /** Da de alta el segundo factor del usuario y devuelve su secreto en Base32. */
  private String darDeAlta(String usuario) {
    ClienteHttp navegador = cliente();
    assertThat(navegador.iniciarSesion(usuario, CONTRASENA).estado()).isEqualTo(202);
    String secreto = navegador.post("/api/v1/auth/segundo-factor/alta", "{}").json("$.secreto");
    String codigo = Totp.codigo(base32(secreto), Totp.paso(Instant.now()) - 1);
    assertThat(navegador.post(confirmar(), codigo(codigo)).estado()).isEqualTo(200);
    return secreto;
  }

  /** Inicia sesión completa (con alta del segundo factor si hace falta). */
  private ClienteHttp entrar(String usuario) {
    ClienteHttp navegador = cliente();
    assertThat(navegador.iniciarSesion(usuario, CONTRASENA).<String>json("$.paso"))
        .isEqualTo("alta");
    String secreto = navegador.post("/api/v1/auth/segundo-factor/alta", "{}").json("$.secreto");
    String codigo = Totp.codigo(base32(secreto), Totp.paso(Instant.now()));
    assertThat(navegador.post(confirmar(), codigo(codigo)).estado()).isEqualTo(200);
    return navegador;
  }

  private long idDe(String usuario) {
    return comoMigrador()
        .sql("SELECT id FROM organizacion.usuario WHERE usuario = :u")
        .param("u", usuario)
        .query(Long.class)
        .single();
  }

  private static String confirmar() {
    return "/api/v1/auth/segundo-factor/alta/confirmar";
  }

  private static String codigo(String codigo) {
    return "{\"codigo\":\"" + codigo + "\"}";
  }

  private static byte[] base32(String texto) {
    String alfabeto = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int buffer = 0;
    int bits = 0;
    for (char c : texto.toCharArray()) {
      buffer = (buffer << 5) | alfabeto.indexOf(c);
      bits += 5;
      if (bits >= 8) {
        bytes.write((buffer >>> (bits - 8)) & 0xFF);
        bits -= 8;
      }
    }
    return bytes.toByteArray();
  }
}

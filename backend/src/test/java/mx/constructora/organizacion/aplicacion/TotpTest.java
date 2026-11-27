package mx.constructora.organizacion.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Vectores del RFC 6238 (SHA-1) y del RFC 4648, y el cifrado del secreto. Java puro. */
class TotpTest {

  private static final byte[] SECRETO_RFC =
      "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

  @ParameterizedTest
  @CsvSource({
    "59, 287082",
    "1111111109, 081804",
    "1111111111, 050471",
    "1234567890, 005924",
    "2000000000, 279037",
    "20000000000, 353130"
  })
  void codigosDelRfc6238(long segundos, String esperado) {
    assertThat(Totp.codigo(SECRETO_RFC, Totp.paso(Instant.ofEpochSecond(segundos))))
        .isEqualTo(esperado);
  }

  @Test
  void aceptaLaVentanaAnteriorYLaSiguientePeroNoMas() {
    Instant ahora = Instant.ofEpochSecond(1111111111);
    long paso = Totp.paso(ahora);
    assertThat(Totp.verificar(SECRETO_RFC, "050 471", ahora)).hasValue(paso);
    assertThat(Totp.verificar(SECRETO_RFC, Totp.codigo(SECRETO_RFC, paso - 1), ahora))
        .hasValue(paso - 1);
    assertThat(Totp.verificar(SECRETO_RFC, Totp.codigo(SECRETO_RFC, paso + 1), ahora))
        .hasValue(paso + 1);
    assertThat(Totp.verificar(SECRETO_RFC, Totp.codigo(SECRETO_RFC, paso - 2), ahora)).isEmpty();
  }

  @Test
  void rechazaLoQueNoSonSeisDigitos() {
    Instant ahora = Instant.ofEpochSecond(1111111111);
    assertThat(Totp.verificar(SECRETO_RFC, null, ahora)).isEmpty();
    assertThat(Totp.verificar(SECRETO_RFC, "05047", ahora)).isEmpty();
    assertThat(Totp.verificar(SECRETO_RFC, "abcdef", ahora)).isEmpty();
  }

  @Test
  void base32DelRfc4648() {
    assertThat(Totp.base32("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
    assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
    assertThat(Totp.base32(SECRETO_RFC)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
  }

  @Test
  void laDireccionParaElQr() {
    assertThat(Totp.uri("Constructora RM", "ana.lopez", SECRETO_RFC))
        .isEqualTo(
            "otpauth://totp/Constructora%20RM:ana.lopez?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
                + "&issuer=Constructora%20RM&algorithm=SHA1&digits=6&period=30");
  }

  @Test
  void elSecretoCifradoSoloLoAbreSuUsuarioConLaMismaLlave() {
    String llave = Base64.getEncoder().encodeToString(new byte[32]);
    CifradoSecreto cifrado = CifradoSecreto.desde(llave);
    byte[] guardado = cifrado.cifrar(SECRETO_RFC, 7);
    assertThat(guardado).hasSize(12 + 20 + 16);
    assertThat(cifrado.descifrar(guardado, 7)).isEqualTo(SECRETO_RFC);
    assertThat(cifrado.cifrar(SECRETO_RFC, 7)).isNotEqualTo(guardado);
    assertThatThrownBy(() -> cifrado.descifrar(guardado, 8))
        .isInstanceOf(IllegalStateException.class);
    byte[] otraLlave = new byte[32];
    otraLlave[0] = 1;
    assertThatThrownBy(
            () ->
                CifradoSecreto.desde(Base64.getEncoder().encodeToString(otraLlave))
                    .descifrar(guardado, 7))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void laLlaveTieneQueMedir32Bytes() {
    assertThatThrownBy(() -> CifradoSecreto.desde(""))
        .hasMessageContaining("Falta APP_LLAVE_SEGUNDO_FACTOR");
    assertThatThrownBy(() -> CifradoSecreto.desde(Base64.getEncoder().encodeToString(new byte[16])))
        .hasMessageContaining("32 bytes");
    assertThatThrownBy(() -> CifradoSecreto.desde("no es base64!")).hasMessageContaining("Base64");
  }
}

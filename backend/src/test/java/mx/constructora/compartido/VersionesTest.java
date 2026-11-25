package mx.constructora.compartido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Lectura de If-Match para la concurrencia optimista. */
class VersionesTest {

  @Test
  void aceptaLasFormasComunesDeIfMatch() {
    assertThat(Versiones.deEncabezado("3")).isEqualTo(3);
    assertThat(Versiones.deEncabezado("\"3\"")).isEqualTo(3);
    assertThat(Versiones.deEncabezado("W/\"12\"")).isEqualTo(12);
    assertThat(Versiones.etag(5)).isEqualTo("\"5\"");
  }

  @Test
  void sinVersionResponde428YConBasuraResponde400() {
    assertThatThrownBy(() -> Versiones.deEncabezado(null))
        .isInstanceOfSatisfying(
            ErrorNegocio.class,
            e -> assertThat(e.estado()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
    assertThatThrownBy(() -> Versiones.deEncabezado("abc"))
        .isInstanceOfSatisfying(
            ErrorNegocio.class, e -> assertThat(e.codigo()).isEqualTo("VERSION_INVALIDA"));
  }
}

package mx.constructora.abastecimiento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import mx.constructora.abastecimiento.dominio.Asignacion;
import mx.constructora.abastecimiento.dominio.Avance;
import org.junit.jupiter.api.Test;

/** Cómo va un renglón de requisición y cómo se asigna lo pedido y lo recibido (Java puro). */
class AvanceTest {

  @Test
  void loQueFaltaComprarEsLoPendienteSinCubrirNiPedir() {
    Avance a = avance("12", "0", "0", "10", "0");
    assertThat(a.pendiente()).isEqualByComparingTo("12");
    assertThat(a.cubierto()).isEqualByComparingTo("10");
    assertThat(a.faltante()).isEqualByComparingTo("2");
    assertThat(a.porSurtir()).isEqualByComparingTo("12");

    Avance pedido = avance("12", "0", "0", "10", "2");
    assertThat(pedido.faltante()).isEqualByComparingTo("0");
  }

  @Test
  void unValeAbiertoSeSurteDeLoApartado() {
    // Vale de 6 sobre 6 apartados: cubre 6, no 12.
    Avance a = avance("10", "0", "6", "6", "0");
    assertThat(a.cubierto()).isEqualByComparingTo("6");
    assertThat(a.faltante()).isEqualByComparingTo("4");
    assertThat(a.porSurtir()).isEqualByComparingTo("4");
    // Vale de 8 con 6 apartados: los otros 2 salen de lo libre.
    assertThat(avance("10", "0", "8", "6", "0").faltante()).isEqualByComparingTo("2");
  }

  @Test
  void loSurtidoDeMasNoDejaNegativos() {
    Avance a = avance("5", "7", "0", "0", "0");
    assertThat(a.pendiente()).isEqualByComparingTo("0");
    assertThat(a.faltante()).isEqualByComparingTo("0");
    assertThat(a.porSurtir()).isEqualByComparingTo("0");
    assertThat(a.completo()).isTrue();
  }

  @Test
  void porcentajeSurtidoDeVariosRenglones() {
    assertThat(
            Avance.porcentaje(
                List.of(
                    avance("6", "6", "0", "0", "0"),
                    avance("6", "6", "0", "0", "0"),
                    avance("5", "3", "0", "0", "0"))))
        .isEqualByComparingTo("88.24");
    assertThat(Avance.porcentaje(List.of())).isEqualByComparingTo("0");
    // Lo surtido de más no pasa del 100%.
    assertThat(Avance.porcentaje(List.of(avance("5", "9", "0", "0", "0"))))
        .isEqualByComparingTo("100");
  }

  @Test
  void asignaEnOrdenHastaLoQueAdmiteCada() {
    assertThat(Asignacion.enOrden(new BigDecimal("5"), List.of(dec("2"), dec("4"), dec("9"))))
        .containsExactly(dec("2"), dec("3"), dec("0"));
    assertThat(Asignacion.enOrden(new BigDecimal("20"), List.of(dec("2"), dec("-1"), dec("4"))))
        .containsExactly(dec("2"), dec("0"), dec("4"));
    assertThat(Asignacion.enOrden(BigDecimal.ZERO, List.of(dec("2")))).containsExactly(dec("0"));
    assertThatThrownBy(() -> Asignacion.enOrden(new BigDecimal("-1"), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Avance avance(
      String cantidad, String surtido, String enVale, String reservado, String porRecibir) {
    return new Avance(dec(cantidad), dec(surtido), dec(enVale), dec(reservado), dec(porRecibir));
  }

  private static BigDecimal dec(String valor) {
    return new BigDecimal(valor);
  }
}

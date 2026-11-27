package mx.constructora.reportes;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import mx.constructora.reportes.dominio.Semaforo;
import org.junit.jupiter.api.Test;

/** Presupuesto por lote: aviso al 80% y al 100% (issue 36). */
class SemaforoTest {

  @Test
  void verdeAmarilloRojo() {
    assertThat(Semaforo.de(dec("799.99"), dec("1000"))).isEqualTo(Semaforo.VERDE);
    assertThat(Semaforo.de(dec("800"), dec("1000"))).isEqualTo(Semaforo.AMARILLO);
    assertThat(Semaforo.de(dec("999.99"), dec("1000"))).isEqualTo(Semaforo.AMARILLO);
    assertThat(Semaforo.de(dec("1000"), dec("1000"))).isEqualTo(Semaforo.ROJO);
    assertThat(Semaforo.de(dec("1500"), dec("1000"))).isEqualTo(Semaforo.ROJO);
  }

  @Test
  void sinPresupuestoOEnCero() {
    assertThat(Semaforo.de(dec("10"), null)).isEqualTo(Semaforo.SIN_PRESUPUESTO);
    assertThat(Semaforo.de(dec("10"), null).clave()).isEqualTo("sin");
    assertThat(Semaforo.de(BigDecimal.ZERO, BigDecimal.ZERO)).isEqualTo(Semaforo.VERDE);
    assertThat(Semaforo.de(dec("0.01"), BigDecimal.ZERO)).isEqualTo(Semaforo.ROJO);
    assertThat(Semaforo.porcentaje(dec("10"), null)).isNull();
    assertThat(Semaforo.porcentaje(dec("10"), BigDecimal.ZERO)).isNull();
  }

  @Test
  void porcentajeConDosDecimales() {
    assertThat(Semaforo.porcentaje(dec("900"), dec("1000"))).isEqualByComparingTo("90.00");
    assertThat(Semaforo.porcentaje(dec("1"), dec("3"))).isEqualByComparingTo("33.33");
    assertThat(Semaforo.porcentaje(null, dec("3"))).isEqualByComparingTo("0");
  }

  private static BigDecimal dec(String valor) {
    return new BigDecimal(valor);
  }
}

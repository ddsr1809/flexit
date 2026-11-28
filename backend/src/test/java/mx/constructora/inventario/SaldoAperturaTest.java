package mx.constructora.inventario;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import mx.constructora.inventario.dominio.SaldoApertura;
import mx.constructora.inventario.dominio.SaldoApertura.Costo;
import mx.constructora.inventario.dominio.SaldoApertura.Linea;
import mx.constructora.inventario.dominio.SaldoApertura.Origen;
import mx.constructora.inventario.dominio.SaldoApertura.PrestamoListo;
import org.junit.jupiter.api.Test;

/** El saldo de apertura (H8): qué costo manda, cuánto vale y la huella de lo que se firma. */
class SaldoAperturaTest {

  private static final LocalDate CORTE = LocalDate.of(2027, 3, 20);

  @Test
  void mandaElCapturadoDespuesElHistoricoYAlFinalLaReferencia() {
    assertThat(
            SaldoApertura.elegir(new BigDecimal("10"), new BigDecimal("55"), new BigDecimal("40")))
        .isEqualTo(new Costo(new BigDecimal("10"), Origen.CAPTURADO));
    assertThat(SaldoApertura.elegir(null, new BigDecimal("55"), new BigDecimal("40")).origen())
        .isEqualTo(Origen.HISTORICO);
    assertThat(SaldoApertura.elegir(null, null, new BigDecimal("40")).origen())
        .isEqualTo(Origen.REFERENCIA);
    assertThat(SaldoApertura.elegir(null, BigDecimal.ZERO, BigDecimal.ZERO))
        .as("un ponderado o una referencia en 0 no son costo")
        .isNull();
    assertThat(SaldoApertura.elegir(BigDecimal.ZERO, null, null).origen())
        .as("administración sí puede firmar una herramienta en 0")
        .isEqualTo(Origen.CAPTURADO);
  }

  @Test
  void entraLoContadoMasLoPrestadoConElRedondeoDelKardex() {
    Linea pintura =
        new Linea(
            1,
            new BigDecimal("12.0000"),
            null,
            new Costo(new BigDecimal("55.123456"), Origen.HISTORICO));
    Linea compresor =
        new Linea(
            2,
            new BigDecimal("0.0000"),
            new BigDecimal("1.0000"),
            new Costo(new BigDecimal("8500"), Origen.CAPTURADO));
    assertThat(pintura.cantidad()).isEqualByComparingTo("12");
    assertThat(pintura.importe()).isEqualByComparingTo("661.4815");
    assertThat(compresor.cantidad()).isEqualByComparingTo("1");
    assertThat(SaldoApertura.importe(List.of(pintura, compresor)))
        .isEqualByComparingTo("9161.4815");
  }

  @Test
  void sinCostoSoloFaltaSiEntraAlgo() {
    assertThat(new Linea(1, new BigDecimal("3"), null, null).faltaCosto()).isTrue();
    assertThat(new Linea(1, BigDecimal.ZERO, BigDecimal.ONE, null).faltaCosto()).isTrue();
    assertThat(new Linea(1, BigDecimal.ZERO, null, null).faltaCosto()).isFalse();
    assertThat(new Linea(1, null, null, null).cantidad()).isEqualByComparingTo("0");
  }

  @Test
  void laHuellaCambiaConLoQueSeFirmaYNoConElOrden() {
    Linea a = new Linea(1, new BigDecimal("12"), null, new Costo(BigDecimal.TEN, Origen.HISTORICO));
    Linea b =
        new Linea(2, BigDecimal.ZERO, BigDecimal.ONE, new Costo(BigDecimal.ONE, Origen.CAPTURADO));
    PrestamoListo p = new PrestamoListo(7, 2, BigDecimal.ONE, 30);
    String huella = SaldoApertura.huella(CORTE, List.of(a, b), List.of(p));

    assertThat(huella).hasSize(64);
    assertThat(SaldoApertura.huella(CORTE, List.of(b, a), List.of(p))).isEqualTo(huella);
    assertThat(
            SaldoApertura.huella(
                CORTE,
                List.of(new Linea(1, new BigDecimal("12.0000"), BigDecimal.ZERO, a.costo()), b),
                List.of(p)))
        .as("la escala no cambia la huella")
        .isEqualTo(huella);
    assertThat(
            SaldoApertura.huella(
                CORTE, List.of(new Linea(1, new BigDecimal("13"), null, a.costo()), b), List.of(p)))
        .as("otro conteo")
        .isNotEqualTo(huella);
    Linea referencia =
        new Linea(1, new BigDecimal("12"), null, new Costo(BigDecimal.TEN, Origen.REFERENCIA));
    assertThat(SaldoApertura.huella(CORTE, List.of(referencia, b), List.of(p)))
        .as("mismo costo, otro origen")
        .isNotEqualTo(huella);
    assertThat(
            SaldoApertura.huella(
                CORTE, List.of(a, b), List.of(new PrestamoListo(7, 2, BigDecimal.ONE, 31))))
        .as("otro responsable")
        .isNotEqualTo(huella);
    assertThat(SaldoApertura.huella(CORTE.plusDays(1), List.of(a, b), List.of(p)))
        .as("otra fecha")
        .isNotEqualTo(huella);
  }
}

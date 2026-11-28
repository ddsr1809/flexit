package mx.constructora.reportes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import mx.constructora.reportes.dominio.CriterioEstabilizacion;
import mx.constructora.reportes.dominio.CriterioEstabilizacion.DiaUso;
import mx.constructora.reportes.dominio.CriterioEstabilizacion.Resultado;
import org.junit.jupiter.api.Test;

/** El criterio de H9: dos semanas capturando a tiempo, sin críticos y con el mes cerrado. */
class CriterioEstabilizacionTest {

  private static final LocalDate APERTURA = LocalDate.of(2027, 3, 15);

  @Test
  void dosSemanasATiempoConValesCumplen() {
    Resultado r = CriterioEstabilizacion.evaluar(dias(14, -1), 0, true);
    assertThat(r.racha()).isEqualTo(14);
    assertThat(r.valesEnRacha()).isEqualTo(28);
    assertThat(r.dosSemanasATiempo()).isTrue();
    assertThat(r.cumple()).isTrue();
  }

  @Test
  void treceDiasTodaviaNo() {
    Resultado r = CriterioEstabilizacion.evaluar(dias(13, -1), 0, true);
    assertThat(r.racha()).isEqualTo(13);
    assertThat(r.dosSemanasATiempo()).isFalse();
    assertThat(r.cumple()).isFalse();
  }

  @Test
  void unaCapturaTardiaReiniciaLaRacha() {
    // 20 días; el día 6 (índice 5) tiene un vale capturado tarde: la racha son los 14 de después.
    Resultado r = CriterioEstabilizacion.evaluar(dias(20, 5), 0, true);
    assertThat(r.racha()).isEqualTo(14);
    assertThat(r.dosSemanasATiempo()).isTrue();

    Resultado tarde = CriterioEstabilizacion.evaluar(dias(20, 10), 0, true);
    assertThat(tarde.racha()).isEqualTo(9);
    assertThat(tarde.dosSemanasATiempo()).isFalse();
  }

  @Test
  void unDiaSinDocumentosNoRompeLaRacha() {
    List<DiaUso> dias = new ArrayList<>(dias(14, -1));
    dias.set(6, new DiaUso(dias.get(6).fecha(), 0, 0)); // domingo
    assertThat(CriterioEstabilizacion.evaluar(dias, 0, true).racha()).isEqualTo(14);
  }

  @Test
  void sinValesNoSeUsaElSistema() {
    List<DiaUso> vacios = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      vacios.add(new DiaUso(APERTURA.plusDays(i), 0, 0));
    }
    Resultado r = CriterioEstabilizacion.evaluar(vacios, 0, true);
    assertThat(r.racha()).isEqualTo(20);
    assertThat(r.valesEnRacha()).isZero();
    assertThat(r.dosSemanasATiempo()).isFalse();
  }

  @Test
  void unCriticoAbiertoOElMesSinCerrarDetienenElCriterio() {
    assertThat(CriterioEstabilizacion.evaluar(dias(14, -1), 1, true).cumple()).isFalse();
    assertThat(CriterioEstabilizacion.evaluar(dias(14, -1), 1, true).sinCriticos()).isFalse();
    assertThat(CriterioEstabilizacion.evaluar(dias(14, -1), 0, false).cumple()).isFalse();
  }

  @Test
  void sinDiasNoHayRacha() {
    Resultado r = CriterioEstabilizacion.evaluar(List.of(), 0, true);
    assertThat(r.racha()).isZero();
    assertThat(r.cumple()).isFalse();
  }

  /** {@code n} días con 2 vales cada uno; el día {@code tardio} (ninguno con -1), con un tardío. */
  private static List<DiaUso> dias(int n, int tardio) {
    List<DiaUso> dias = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      dias.add(new DiaUso(APERTURA.plusDays(i), 2, i == tardio ? 1 : 0));
    }
    return dias;
  }
}

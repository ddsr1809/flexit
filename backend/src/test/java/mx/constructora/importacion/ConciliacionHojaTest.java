package mx.constructora.importacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mx.constructora.importacion.dominio.ConciliacionHoja;
import mx.constructora.importacion.dominio.ConciliacionHoja.Lote;
import mx.constructora.importacion.dominio.ConciliacionHoja.Motivo;
import mx.constructora.importacion.dominio.ConciliacionHoja.SalidaSistema;
import mx.constructora.importacion.dominio.xlsx.LibroXlsx;
import org.junit.jupiter.api.Test;

/**
 * La conciliación de un contrato contra la hoja de su frente (issue 38) con {@code
 * muestra-conciliacion.xlsx}: un renglón de Salidas por cada motivo de diferencia. Los valores de
 * la hoja son los que da Excel (o LibreOffice) al recalcular la muestra: M20-L1 = 1,232.50, M20-L2
 * = 1,450 y M20-L3 = 17.
 */
class ConciliacionHojaTest {

  private static final Set<String> LOTES = Set.of("M20-L1", "M20-L2", "M20-L3", "M20-L9");

  /**
   * Lo que el sistema guardó de cada renglón (Programa y entregado), con el cemento al ponderado
   * sin IVA (225) en lugar del promedio simple con IVA de la hoja (290).
   */
  private static final List<SalidaSistema> SISTEMA =
      List.of(
          salida(3, "M20-L1", "4", "900", false),
          salida(4, "M20-L1", "2", "450", false), // "Programa " con espacio
          salida(5, "M20-L2", "1", "1500", true), // "2-3": repartido
          salida(5, "M20-L3", "1", "1500", true),
          salida(6, "M20-L9", "1", "50", false), // M20-L9 no es columna
          salida(7, "M20-L1", "3", "25.5", false), // "Lija  220" con doble espacio
          salida(8, "M20-L3", "5", "1125", false), // la cuarentena lo pasó de L2 a L3
          salida(9, "M20-L1", "1", "30", false), // aerosol: costo estimado, sin precio en la hoja
          // fila 11 (lija a L3) quedó en cuarentena: no está
          salida(12, "M20-L1", "0.25", "56.25", false)); // =1/4

  @Test
  void laHojaRecalculadaEsLaDeExcel() {
    var r = conciliar();
    assertThat(r.hoja()).isEqualTo("CORCELES");
    assertThat(r.razon()).isEqualTo("Programa");
    assertThat(r.frente()).isEqualTo("Corceles");
    assertThat(r.columnasDeLote()).isEqualTo(3); // la columna "X / X" no es lote
    assertThat(lote(r, "M20-L1").hoja()).isEqualByComparingTo("1232.50");
    assertThat(lote(r, "M20-L2").hoja()).isEqualByComparingTo("1450");
    assertThat(lote(r, "M20-L3").hoja()).isEqualByComparingTo("17");
    assertThat(lote(r, "M20-L9").hoja()).isEqualByComparingTo("0");
  }

  @Test
  void cadaPesoDeDiferenciaTieneMotivo() {
    var r = conciliar();
    for (Lote l : r.lotes()) {
      BigDecimal suma = l.efectos().values().stream().reduce(l.sistema(), BigDecimal::add);
      assertThat(suma.add(l.redondeo())).as(l.clave()).isEqualByComparingTo(l.hoja());
      assertThat(l.redondeo()).as(l.clave()).isEqualByComparingTo("0");
    }
    // M20-L1: 1,461.75 en el sistema → 1,232.50 en la hoja.
    Lote l1 = lote(r, "M20-L1");
    assertThat(l1.sistema()).isEqualByComparingTo("1461.75");
    assertThat(l1.efectos())
        .containsOnlyKeys(
            Motivo.PONDERACION, Motivo.IVA, Motivo.RAZON, Motivo.MATERIAL, Motivo.SIN_PRECIO);
    assertThat(l1.efectos().get(Motivo.PONDERACION)).isEqualByComparingTo("106.25");
    assertThat(l1.efectos().get(Motivo.IVA)).isEqualByComparingTo("170");
    assertThat(l1.efectos().get(Motivo.RAZON)).isEqualByComparingTo("-450");
    assertThat(l1.efectos().get(Motivo.MATERIAL)).isEqualByComparingTo("-25.5");
    assertThat(l1.efectos().get(Motivo.SIN_PRECIO)).isEqualByComparingTo("-30");
  }

  @Test
  void loteCorregidoRepartoYSoloEnHoja() {
    var r = conciliar();
    assertThat(lote(r, "M20-L2").efectos())
        .isEqualTo(
            Map.of(
                Motivo.REPARTO, new BigDecimal("-1500.00"),
                Motivo.SOLO_EN_HOJA, new BigDecimal("1450.00")));
    assertThat(lote(r, "M20-L3").efectos())
        .isEqualTo(
            Map.of(
                Motivo.REPARTO, new BigDecimal("-1500.00"),
                Motivo.LOTE_CORREGIDO, new BigDecimal("-1125.00"),
                Motivo.SOLO_EN_HOJA, new BigDecimal("17.00")));
    assertThat(lote(r, "M20-L9").efectos())
        .isEqualTo(Map.of(Motivo.SIN_COLUMNA, new BigDecimal("-50.00")));
    assertThat(lote(r, "M20-L9").columnaEnHoja()).isFalse();

    var detalle = r.detalle();
    assertThat(detalle)
        .anySatisfy(
            d -> {
              assertThat(d.filaExcel()).isEqualTo(8);
              assertThat(d.lote()).isEqualTo("M20-L2");
              assertThat(d.motivo()).isEqualTo(Motivo.SOLO_EN_HOJA);
              assertThat(d.nota()).contains("M20-L3");
            });
    assertThat(detalle)
        .anySatisfy(
            d -> {
              assertThat(d.filaExcel()).isEqualTo(11);
              assertThat(d.motivo()).isEqualTo(Motivo.SOLO_EN_HOJA);
              assertThat(d.nota()).contains("cuarentena");
            });
  }

  @Test
  void criteriosComoSumifs() {
    var r = conciliar();
    // El renglón no entregado (fila 10) no cuenta en la hoja ni llega al sistema.
    assertThat(r.detalle()).noneMatch(d -> d.filaExcel() == 10);
    assertThat(r.avisos()).isEmpty();
  }

  private static ConciliacionHoja.Resultado conciliar() {
    try (LibroXlsx libro = LibroXlsx.abrir(Muestras.ruta("muestra-conciliacion.xlsx"))) {
      return ConciliacionHoja.conciliar(libro, "corceles", LOTES, SISTEMA);
    }
  }

  private static Lote lote(ConciliacionHoja.Resultado r, String clave) {
    return r.lotes().stream().filter(l -> l.clave().equals(clave)).findFirst().orElseThrow();
  }

  private static SalidaSistema salida(
      int fila, String lote, String cantidad, String importe, boolean repartida) {
    return new SalidaSistema(
        fila, lote, new BigDecimal(cantidad), new BigDecimal(importe), repartida);
  }
}

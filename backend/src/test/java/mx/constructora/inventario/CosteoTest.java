package mx.constructora.inventario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import mx.constructora.inventario.dominio.AjusteCosto;
import mx.constructora.inventario.dominio.CostoRecepcion;
import mx.constructora.inventario.dominio.ExistenciaInsuficiente;
import mx.constructora.inventario.dominio.Reparto;
import mx.constructora.inventario.dominio.TipoMovimiento;
import mx.constructora.inventario.dominio.Valuacion;
import mx.constructora.inventario.dominio.Valuacion.Paso;
import org.junit.jupiter.api.Test;

/**
 * Las tres piezas que deciden si el inventario es confiable, sin base ni Spring: costo promedio en
 * cada entrada, salidas que no dejan centavos sueltos, y repartos que suman exacto.
 */
class CosteoTest {

  private static BigDecimal d(String valor) {
    return new BigDecimal(valor);
  }

  private static void igual(String esperado, BigDecimal real) {
    assertEquals(0, d(esperado).compareTo(real), () -> "esperaba " + esperado + " y fue " + real);
  }

  @Test
  void elPromedioSeRecalculaEnCadaEntrada() {
    Paso primera = Valuacion.VACIA.entrar(d("10"), d("100"));
    igual("1000", primera.importe());
    igual("100", primera.despues().costoPromedio());

    Paso segunda = primera.despues().entrar(d("30"), d("120"));
    igual("40", segunda.despues().existencia());
    igual("4600", segunda.despues().valor());
    igual("115", segunda.despues().costoPromedio());
  }

  @Test
  void laSalidaCongelaElPromedioYNoCambiaElPromedioDeLoQueQueda() {
    Valuacion v = new Valuacion(d("3"), d("100"), d("33.333333"));
    Paso salida = v.salir(d("1"));
    igual("-1", salida.cantidad());
    igual("33.333333", salida.costoUnitario());
    igual("-33.3333", salida.importe());
    igual("66.6667", salida.despues().valor());
  }

  @Test
  void laUltimaSalidaSeLlevaTodoElValor() {
    Valuacion v = new Valuacion(d("3"), d("100"), d("33.333333"));
    Paso uno = v.salir(d("1"));
    Paso dos = uno.despues().salir(d("1"));
    Paso tres = dos.despues().salir(d("1"));
    igual("0", tres.despues().existencia());
    igual("0", tres.despues().valor());
    igual("100", uno.importe().add(dos.importe()).add(tres.importe()).negate());
    // Sin existencia, el promedio conserva el último costo.
    igual("33.333333", tres.despues().costoPromedio());
  }

  @Test
  void noSaleMasDeLoQueHay() {
    Valuacion v = new Valuacion(d("8"), d("80"), d("10"));
    ExistenciaInsuficiente error =
        assertThrows(ExistenciaInsuficiente.class, () -> v.salir(d("15")));
    igual("8", error.disponible());
    igual("15", error.solicitada());
  }

  @Test
  void laDevolucionAlProveedorSaleAlCostoConQueEntro() {
    Valuacion v =
        Valuacion.VACIA.entrar(d("10"), d("10")).despues().entrar(d("10"), d("14")).despues();
    Paso devolucion = v.salirACosto(d("10"), d("14"));
    igual("-140", devolucion.importe());
    igual("10", devolucion.despues().costoPromedio());
  }

  @Test
  void elTraspasoMeteEnElDestinoElMismoValorQueSaca() {
    Valuacion origen = new Valuacion(d("3"), d("100"), d("33.333333"));
    Paso sale = origen.salir(d("2"));
    Paso entra =
        Valuacion.VACIA.entrarConImporte(d("2"), sale.importe().negate(), sale.costoUnitario());
    igual("66.6667", entra.despues().valor());
    igual("100", sale.despues().valor().add(entra.despues().valor()));
  }

  @Test
  void elAjusteDeValorNoDejaValorNegativo() {
    Valuacion v = new Valuacion(d("2"), d("10"), d("5"));
    Paso ajuste = v.ajustarValor(d("-15"));
    igual("-10", ajuste.importe());
    igual("0", ajuste.despues().valor());
    igual("-5", v.excedente(d("-15")));
  }

  @Test
  void unBoteRepartidoEntre18LotesSumaExacto() {
    List<BigDecimal> partes = Reparto.repartir(d("19"), 18, 4);
    assertEquals(18, partes.size());
    igual("1.0555", partes.get(0));
    igual("1.0565", partes.get(17));
    igual("19", partes.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  @Test
  void elProrrateoSumaExactoAunConPesosDisparejos() {
    List<BigDecimal> partes = Reparto.prorratear(d("100"), List.of(d("1"), d("1"), d("1")), 4);
    igual("33.3333", partes.get(0));
    igual("33.3334", partes.get(2));
    List<BigDecimal> negativos = Reparto.prorratear(d("-10"), List.of(d("3"), d("7")), 4);
    igual("-3", negativos.get(0));
    igual("-7", negativos.get(1));
  }

  @Test
  void laRecepcionCuestaSinIvaConDescuentoYFleteProrrateado() {
    // 2 cajas de 4 piezas a $400 con $40 de descuento, y 10 sacos a $100; flete de $90.
    List<CostoRecepcion.Costo> costos =
        CostoRecepcion.calcular(
            List.of(
                new CostoRecepcion.Renglon(d("8"), d("2"), d("400"), d("40")),
                new CostoRecepcion.Renglon(d("10"), d("10"), d("100"), null)),
            d("90"));
    // Importes 760 y 1000: el flete se reparte 38.8636 / 51.1364.
    igual("798.8636", costos.get(0).importe());
    igual("99.857950", costos.get(0).costoUnitario());
    igual("1051.1364", costos.get(1).importe());
    igual("1850", costos.get(0).importe().add(costos.get(1).importe()));
  }

  @Test
  void unDescuentoMayorQueElRenglonSeRechaza() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CostoRecepcion.calcular(
                List.of(new CostoRecepcion.Renglon(d("1"), d("1"), d("10"), d("11"))), null));
  }

  @Test
  void elAjusteDeFacturaSeDivideEntreAlmacenYVales() {
    // Llegaron 100 a $10; la factura dice $12. Quedan 40 en almacén; 60 salieron en dos vales.
    AjusteCosto.Resultado r =
        AjusteCosto.calcular(
            d("100"),
            d("10"),
            d("12"),
            d("40"),
            d("400"),
            List.of(new AjusteCosto.Consumo(1, d("45")), new AjusteCosto.Consumo(2, d("15"))));
    igual("200", r.diferencia());
    igual("80", r.enAlmacen());
    igual("90", r.porConsumo().get(0));
    igual("30", r.porConsumo().get(1));
  }

  @Test
  void elAjusteSinExistenciaNiValesNoTieneDestino() {
    assertThrows(
        AjusteCosto.SinDestino.class,
        () -> AjusteCosto.calcular(d("10"), d("10"), d("9"), d("0"), d("0"), List.of()));
  }

  @Test
  void elAjusteALaBajaNoQuitaMasValorDelQueHay() {
    // Quedan 50 de 100, pero su valor ya bajó a $100 por otras entradas baratas.
    AjusteCosto.Resultado r =
        AjusteCosto.calcular(
            d("100"),
            d("10"),
            d("6"),
            d("50"),
            d("100"),
            List.of(new AjusteCosto.Consumo(9, d("50"))));
    igual("-400", r.diferencia());
    igual("-100", r.enAlmacen());
    igual("-300", r.porConsumo().get(0));
  }

  @Test
  void soloLoQueSeCargaAObraLlevaLote() {
    for (TipoMovimiento tipo : TipoMovimiento.values()) {
      boolean esperado =
          tipo == TipoMovimiento.SALIDA
              || tipo == TipoMovimiento.DEVOLUCION
              || tipo == TipoMovimiento.REASIGNACION;
      assertEquals(esperado, tipo.cargaAObra(), tipo.clave());
      assertTrue(tipo.prefijoFolio().matches("[A-Z]{2,5}"), tipo.clave());
    }
    assertEquals(TipoMovimiento.QuienAutoriza.VALE, TipoMovimiento.SALIDA.autoriza());
    assertEquals(TipoMovimiento.QuienAutoriza.NADIE, TipoMovimiento.ENTRADA.autoriza());
  }
}

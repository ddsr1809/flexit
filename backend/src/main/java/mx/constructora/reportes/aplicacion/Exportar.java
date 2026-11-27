package mx.constructora.reportes.aplicacion;

import java.util.ArrayList;
import java.util.List;
import mx.constructora.abastecimiento.AbastecimientoApi.SurtidoRequisicion;
import mx.constructora.compartido.LibroExcel;
import mx.constructora.compartido.LibroExcel.Columna;
import mx.constructora.compartido.LibroExcel.Formato;
import mx.constructora.reportes.aplicacion.ServicioReportes.ColumnaClasificacion;
import mx.constructora.reportes.aplicacion.ServicioReportes.CostoContrato;
import mx.constructora.reportes.aplicacion.ServicioReportes.ExistenciaValorizada;
import mx.constructora.reportes.aplicacion.ServicioReportes.FilaReporteCosto;
import mx.constructora.reportes.aplicacion.ServicioReportes.LoteCosto;
import mx.constructora.reportes.aplicacion.ServicioReportes.LotePresupuesto;
import mx.constructora.reportes.aplicacion.ServicioReportes.ReporteCosto;
import mx.constructora.reportes.aplicacion.ServicioReportes.ResumenContrato;
import mx.constructora.reportes.aplicacion.ServicioReportes.ValorInventario;

/** Los reportes como .xlsx: las mismas cifras que en pantalla, sin fórmulas. */
public final class Exportar {

  private Exportar() {}

  private static final String NOTA_COSTO =
      "Costo sin IVA, ponderado; histórico del Excel + sistema";

  public static byte[] costo(ReporteCosto r) {
    boolean material = r.agrupar().equals("material");
    List<Columna> columnas = new ArrayList<>();
    columnas.add(Columna.texto("Clave", 16));
    columnas.add(Columna.texto("Nombre", 40));
    if (material) {
      columnas.add(Columna.cantidad("Cantidad"));
      columnas.add(Columna.texto("Unidad", 8));
    }
    columnas.add(Columna.moneda("Histórico (Excel)"));
    columnas.add(Columna.moneda("Sistema"));
    columnas.add(Columna.moneda("Total"));
    columnas.add(Columna.texto("Con estimado", 12));
    columnas.add(new Columna("Movimientos", Formato.ENTERO, 12));
    LibroExcel libro = new LibroExcel();
    var hoja = libro.hoja("Costo por " + r.agrupar(), columnas);
    for (FilaReporteCosto f : r.filas()) {
      List<Object> v = new ArrayList<>(List.of(f.clave(), f.nombre()));
      if (material) {
        v.add(f.cantidad());
        v.add(f.unidad());
      }
      v.addAll(List.of(f.historico(), f.sistema(), f.total(), f.estimado(), f.movimientos()));
      hoja.fila(v.toArray());
    }
    List<Object> t = new ArrayList<>(List.of("Total", NOTA_COSTO));
    if (material) {
      t.add(null);
      t.add(null);
    }
    t.addAll(List.of(r.historico(), r.sistema(), r.total()));
    hoja.totales(t.toArray());
    return libro.bytes();
  }

  public static byte[] contratos(List<ResumenContrato> filas) {
    LibroExcel libro = new LibroExcel();
    var hoja =
        libro.hoja(
            "Costo por contrato",
            List.of(
                Columna.texto("Contrato", 24),
                Columna.texto("Frente", 16),
                Columna.texto("Estado", 10),
                Columna.moneda("Material histórico"),
                Columna.moneda("Material sistema"),
                Columna.moneda("Material"),
                Columna.moneda("Destajo"),
                Columna.moneda("Total"),
                Columna.moneda("Presupuesto"),
                Columna.moneda("Consumido (Programa)"),
                new Columna("% consumido", Formato.PORCENTAJE, 12),
                Columna.texto("Semáforo", 10),
                new Columna("Lotes", Formato.ENTERO, 8),
                new Columna("En aviso", Formato.ENTERO, 9),
                new Columna("Rebasados", Formato.ENTERO, 10)));
    var total = new java.math.BigDecimal[5];
    java.util.Arrays.fill(total, java.math.BigDecimal.ZERO);
    for (ResumenContrato c : filas) {
      hoja.fila(
          c.contrato(),
          c.frente(),
          c.estado(),
          c.historico(),
          c.sistema(),
          c.material(),
          c.destajo(),
          c.total(),
          c.presupuesto(),
          c.consumido(),
          c.porcentaje(),
          c.semaforo(),
          c.lotes(),
          c.lotesEnAviso(),
          c.lotesRebasados());
      total[0] = total[0].add(c.historico());
      total[1] = total[1].add(c.sistema());
      total[2] = total[2].add(c.material());
      total[3] = total[3].add(c.destajo());
      total[4] = total[4].add(c.total());
    }
    hoja.totales("Total", null, null, total[0], total[1], total[2], total[3], total[4]);
    return libro.bytes();
  }

  public static byte[] costoPorLote(CostoContrato c) {
    List<Columna> columnas = new ArrayList<>();
    columnas.add(Columna.texto("Lote", 12));
    columnas.add(Columna.texto("Prototipo", 10));
    for (ColumnaClasificacion k : c.clasificaciones()) {
      columnas.add(Columna.moneda(k.nombre()));
    }
    columnas.addAll(
        List.of(
            Columna.moneda("Material"),
            Columna.moneda("Histórico (Excel)"),
            Columna.moneda("Sistema"),
            Columna.moneda("Destajo"),
            Columna.moneda("Total"),
            Columna.moneda("Presupuesto"),
            Columna.moneda("Consumido (Programa)"),
            new Columna("% consumido", Formato.PORCENTAJE, 12),
            Columna.texto("Semáforo", 10),
            Columna.texto("Con estimado", 12)));
    LibroExcel libro = new LibroExcel();
    var hoja = libro.hoja("Lotes " + c.contrato(), columnas);
    for (LoteCosto l : c.lotes()) {
      hoja.fila(valores(l).toArray());
    }
    hoja.totales(valores(c.totales()).toArray());
    return libro.bytes();
  }

  private static List<Object> valores(LoteCosto l) {
    List<Object> v = new ArrayList<>();
    v.add(l.clave());
    v.add(l.prototipo());
    v.addAll(l.porClasificacion());
    v.addAll(
        java.util.Arrays.asList(
            l.material(),
            l.historico(),
            l.sistema(),
            l.destajo(),
            l.total(),
            l.presupuesto(),
            l.consumido(),
            l.porcentaje(),
            l.semaforo(),
            l.estimado()));
    return v;
  }

  public static byte[] presupuesto(List<LotePresupuesto> filas) {
    LibroExcel libro = new LibroExcel();
    var hoja =
        libro.hoja(
            "Presupuesto por lote",
            List.of(
                Columna.texto("Contrato", 24),
                Columna.texto("Lote", 12),
                Columna.texto("Prototipo", 10),
                Columna.moneda("Presupuesto"),
                Columna.moneda("Consumido (Programa)"),
                Columna.moneda("Restante"),
                new Columna("% consumido", Formato.PORCENTAJE, 12),
                Columna.texto("Semáforo", 10)));
    for (LotePresupuesto p : filas) {
      hoja.fila(
          p.contrato(),
          p.clave(),
          p.prototipo(),
          p.presupuesto(),
          p.consumido(),
          p.restante(),
          p.porcentaje(),
          p.semaforo());
    }
    return libro.bytes();
  }

  public static byte[] valorInventario(ValorInventario v) {
    LibroExcel libro = new LibroExcel();
    var hoja =
        libro.hoja(
            "Valor del inventario",
            List.of(
                Columna.texto("Almacén", 20),
                Columna.texto("Clave", 12),
                Columna.texto("Material", 40),
                Columna.texto("Unidad", 8),
                Columna.cantidad("Existencia"),
                Columna.cantidad("Reservado"),
                Columna.cantidad("Prestado"),
                Columna.cantidad("Disponible"),
                Columna.moneda("Costo promedio"),
                Columna.moneda("Valor")));
    for (ExistenciaValorizada e : v.materiales()) {
      hoja.fila(
          e.almacen(),
          e.clave(),
          e.material(),
          e.unidad(),
          e.existencia(),
          e.reservado(),
          e.prestado(),
          e.disponible(),
          e.costoPromedio(),
          e.valor());
    }
    hoja.totales(
        "Total", null, "Sin IVA, costo promedio", null, null, null, null, null, null, v.valor());
    return libro.bytes();
  }

  public static byte[] requisicionVsSurtido(List<SurtidoRequisicion> filas) {
    LibroExcel libro = new LibroExcel();
    var hoja =
        libro.hoja(
            "Requisición contra surtido",
            List.of(
                Columna.texto("Folio", 18),
                Columna.texto("Estado", 11),
                new Columna("Fecha", Formato.FECHA, 11),
                new Columna("Se requiere", Formato.FECHA, 11),
                Columna.texto("Contrato", 24),
                Columna.texto("Lote", 12),
                Columna.texto("Clave", 12),
                Columna.texto("Material", 40),
                Columna.texto("Unidad", 8),
                Columna.cantidad("Pedido"),
                Columna.cantidad("Surtido"),
                Columna.cantidad("En vale"),
                Columna.cantidad("Apartado"),
                Columna.cantidad("Por recibir"),
                Columna.cantidad("Falta comprar"),
                new Columna("% surtido", Formato.PORCENTAJE, 11)));
    for (SurtidoRequisicion r : filas) {
      hoja.fila(
          r.folio(),
          r.estado(),
          r.fecha(),
          r.fechaRequerida(),
          r.contrato(),
          r.unidadObra(),
          r.materialClave(),
          r.material(),
          r.unidad(),
          r.pedido(),
          r.surtido(),
          r.enVale(),
          r.reservado(),
          r.porRecibir(),
          r.faltante(),
          r.porcentaje());
    }
    return libro.bytes();
  }
}

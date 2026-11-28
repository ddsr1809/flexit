package mx.constructora.importacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mx.constructora.historico.Registros.AltaImss;
import mx.constructora.historico.Registros.AsistenciaDia;
import mx.constructora.historico.Registros.EntradaValorada;
import mx.constructora.historico.Registros.PagoDestajo;
import mx.constructora.historico.Registros.Salida;
import mx.constructora.importacion.dominio.Aritmetica;
import mx.constructora.importacion.dominio.Conciliacion;
import mx.constructora.importacion.dominio.Importador;
import mx.constructora.importacion.dominio.Importadores;
import mx.constructora.importacion.dominio.Modelo.Decision;
import mx.constructora.importacion.dominio.Modelo.Decisiones;
import mx.constructora.importacion.dominio.Modelo.EstadoFila;
import mx.constructora.importacion.dominio.Modelo.FilaCruda;
import mx.constructora.importacion.dominio.Modelo.FilaValidada;
import mx.constructora.importacion.dominio.Modelo.Incidencia;
import mx.constructora.importacion.dominio.Modelo.Opcion;
import mx.constructora.importacion.dominio.Modelo.Severidad;
import mx.constructora.importacion.dominio.TipoCarga;
import mx.constructora.importacion.dominio.Valuacion;
import mx.constructora.importacion.dominio.xlsx.LibroXlsx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Las reglas del histórico (H5) con los renglones anonimizados que las motivan ({@code
 * muestra-historico-*.xlsx}). Los catálogos se dan de alta en memoria como los habría dejado H3.
 */
class ImportadoresHistoricoTest {

  private static final String ALMACEN = "muestra-historico-almacen.xlsx";
  private static final String SUA = "muestra-historico-sua.xlsx";
  private static final String DESTAJO = "muestra-historico-destajo.xlsx";

  private SistemaEnMemoria sistema;
  private final List<Decision> decisiones = new ArrayList<>();
  private long corceles;
  private long palermo;
  private long contratoA;
  private long contratoB;
  private final Map<String, Long> lotes = new java.util.HashMap<>();
  private long seccionCimentacion;
  private long trazo;
  private long acabados;
  private long cemento;
  private long pintura;
  private long lija;
  private long lalo;
  private long mari;
  private long pepe;
  private long mario;
  private long ana;

  @BeforeEach
  void catalogosDeH3() {
    sistema = new SistemaEnMemoria();
    long cliente = sistema.cliente();
    sistema.parametro("clienteId", cliente).parametro("mes", "2026-10").parametro("anio", 2026);
    corceles = sistema.crearFrente(cliente, "CORCELES", "Corceles");
    palermo = sistema.crearFrente(cliente, "PALERMO", "Palermo");
    sistema.ponerAlias("frente", "Pagos Corceles P-65", corceles);
    contratoA =
        sistema.crearContrato(cliente, corceles, "60-AAA-MF6-C002-2026", null, null, null, null);
    contratoB =
        sistema.crearContrato(cliente, palermo, "60-BBB-MEO-C001-2026", null, null, null, null);
    long p65 = sistema.crearPrototipo("P-65", "Prototipo 65");
    for (String lote : List.of("30", "31", "32", "33")) {
      lotes.put(
          "M20-L" + lote,
          sistema.crearUnidad(contratoA, corceles, "M20-L" + lote, "20", lote, p65));
    }
    lotes.put("M16-L34", sistema.crearUnidad(contratoB, palermo, "M16-L34", "16", "34", null));
    seccionCimentacion = sistema.crearPaquete(null, null, "S01", "CIMENTACIÓN", null, 1);
    trazo =
        sistema.crearPaquete(null, seccionCimentacion, "S01.01", "TRAZO Y EXCAVACION", "Lote", 1);
    acabados = sistema.crearPaquete(null, null, "S02", "Acabados", null, 2);
    cemento = sistema.crearMaterial("002", "Cemento gris 50 kg", unidad("SAC"), null);
    pintura = sistema.crearMaterial("008", "Pintura vinílica blanca 19 L", unidad("CUB"), null);
    sistema.crearMaterial("003", "Aerosol negro", unidad("PZA"), null);
    lija = sistema.crearMaterial("007", "Lija 220", unidad("PZA"), new BigDecimal("9"));
    sistema.crearProveedor("Materiales del Valle", new BigDecimal("0.16"));
    sistema.crearProveedor("Pinturas Sol", null);
    lalo = sistema.crearPersona("Lalo Prueba Uno", true, null);
    mari = sistema.crearPersona("Mari Ejemplo Dos", true, null);
    pepe = sistema.crearPersona("Pepe Prueba Tres", true, null);
    sistema.ponerAlias("persona", "LALO", lalo);
    sistema.ponerAlias("persona", "LALO G", lalo);
    sistema.ponerAlias("persona", "MARI", mari);
    sistema.ponerAlias("persona", "PEPE", pepe);
    mario = sistema.crearPersona("PEREZ LUNA MARIO ALBERTO", false, null);
    ana = sistema.crearPersona("SALAS RUIZ ANA MARIA", false, null);
    sistema.crearPersona("RIOS CANO LUIS", false, null);
    sistema.ponerAlias("persona", "MARIO ALBERTO PEREZ LUNA", mario);
  }

  private long unidad(String clave) {
    return sistema.unidadesMedida().stream()
        .filter(u -> u.clave().equals(clave))
        .findFirst()
        .orElseThrow()
        .id();
  }

  private List<FilaValidada> validar(TipoCarga tipo, String muestra) {
    List<FilaCruda> crudas;
    try (LibroXlsx libro = LibroXlsx.abrir(Muestras.ruta(muestra))) {
      crudas = Importadores.de(tipo).leer(libro).filas();
    }
    return Importadores.de(tipo).validar(crudas, sistema, Decisiones.de(decisiones));
  }

  /**
   * Valida, revisa que no quede nada en cuarentena ni diferencias en la conciliación y confirma.
   */
  private List<FilaValidada> confirmar(TipoCarga tipo, String muestra) {
    List<FilaValidada> filas = validar(tipo, muestra);
    assertThat(filas).noneMatch(f -> f.estado() == EstadoFila.CUARENTENA);
    Importador importador = Importadores.de(tipo);
    List<Conciliacion.Total> totales = importador.conciliar(filas, sistema);
    assertThat(totales).isNotEmpty();
    assertThat(totales).allMatch(t -> t.diferencia().signum() == 0, "diferencia 0 en cada total");
    importador.aplicar(filas, sistema, sistema);
    decisiones.clear();
    return filas;
  }

  private static FilaValidada filaExcel(List<FilaValidada> filas, String hoja, int fila) {
    return filas.stream()
        .filter(f -> f.fila().hoja().equals(hoja) && f.fila().filaExcel() == fila)
        .findFirst()
        .orElseThrow(() -> new AssertionError("No hay renglón " + hoja + "!" + fila));
  }

  private static Incidencia incidencia(FilaValidada fila, String regla) {
    return fila.incidencias().stream()
        .filter(i -> i.regla().equals(regla))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("El renglón no tiene " + regla + ": " + fila.incidencias()));
  }

  private void decidir(FilaValidada fila, String regla, Opcion opcion, Long destino, String valor) {
    decisiones.add(new Decision(fila.numero(), regla, opcion, destino, valor, "prueba"));
  }

  // ------------------------------------------------------------------------------ aritmética

  @Test
  void soloSeEvaluanFormulasDeNumerosEscritos() {
    assertThat(Aritmetica.evaluar("1/3")).contains(new BigDecimal("0.33333333"));
    assertThat(Aritmetica.evaluar("=206.95/30")).contains(new BigDecimal("6.89833333"));
    assertThat(Aritmetica.evaluar(" 3.33333333333333")).contains(new BigDecimal("3.33333333"));
    assertThat(Aritmetica.evaluar("-34.3333333333333")).contains(new BigDecimal("-34.33333333"));
    assertThat(Aritmetica.evaluar("12-SUM(H580:H583)")).isEmpty();
    assertThat(Aritmetica.evaluar("A1+1")).isEmpty();
    assertThat(Aritmetica.evaluar("1/0")).isEmpty();
    assertThat(Aritmetica.sumandos("=3100+7232"))
        .containsExactly(new BigDecimal("3100"), new BigDecimal("7232"));
    assertThat(Aritmetica.sumandos("500+\n400"))
        .containsExactly(new BigDecimal("500"), new BigDecimal("400"));
    assertThat(Aritmetica.sumandos("2500")).isEmpty();
    assertThat(Aritmetica.sumandos("2*3")).isEmpty();
  }

  @Test
  void laValuacionEsElPonderadoAcumuladoHastaLaFecha() {
    Valuacion valuacion =
        new Valuacion(
            List.of(
                new EntradaValorada(
                    1, LocalDate.of(2026, 6, 10), BigDecimal.TEN, new BigDecimal("2000")),
                new EntradaValorada(
                    1, LocalDate.of(2026, 6, 20), BigDecimal.TEN, new BigDecimal("2100"))),
            Map.of(2L, new BigDecimal("9")));
    assertThat(valuacion.costo(1, LocalDate.of(2026, 6, 15)).unitario())
        .isEqualByComparingTo("200");
    assertThat(valuacion.costo(1, LocalDate.of(2026, 6, 20)).unitario())
        .isEqualByComparingTo("205");
    Valuacion.Costo antes = valuacion.costo(1, LocalDate.of(2026, 6, 1));
    assertThat(antes.estimado()).isTrue();
    assertThat(antes.unitario()).isEqualByComparingTo("205");
    assertThat(valuacion.costo(2, LocalDate.of(2026, 6, 1)).origen())
        .isEqualTo(Valuacion.Origen.REFERENCIA);
    assertThat(valuacion.costo(3, LocalDate.of(2026, 6, 1))).isNull();
  }

  // -------------------------------------------------------------------------------- entradas

  private void cargarEntradas() {
    List<FilaValidada> filas = validar(TipoCarga.HIST_ENTRADAS, ALMACEN);
    decidir(filaExcel(filas, "Entradas", 7), "SIN_FECHA", Opcion.VALOR, null, "05/08/2026");
    confirmar(TipoCarga.HIST_ENTRADAS, ALMACEN);
  }

  @Test
  void entradasConPrecioSinIvaEstimadoYAnioCorregido() {
    List<FilaValidada> filas = validar(TipoCarga.HIST_ENTRADAS, ALMACEN);
    assertThat(filas).hasSize(7);

    FilaValidada formula = filaExcel(filas, "Entradas", 4);
    assertThat(incidencia(formula, "FORMULA_EVALUADA").detalle()).contains("=206.95/30");
    assertThat(formula.dato("precio")).isEqualTo("6.898333");

    FilaValidada sinPrecio = filaExcel(filas, "Entradas", 5);
    assertThat(sinPrecio.estado()).isEqualTo(EstadoFila.CORREGIDA);
    assertThat(incidencia(sinPrecio, "PRECIO_ESTIMADO")).isNotNull();
    assertThat(new BigDecimal(sinPrecio.dato("precio")))
        .as("(10 × 200 + 10 × 220 − 100) ÷ 20")
        .isEqualByComparingTo("205");
    assertThat(sinPrecio.dato("tasaIva")).as("la tasa del proveedor").isEqualTo("0.16");

    FilaValidada anio = filaExcel(filas, "Entradas", 6);
    assertThat(incidencia(anio, "ANIO_CORREGIDO").detalle()).contains("semana 32");
    assertThat(anio.dato("fecha")).isEqualTo("2026-08-03");
    assertThat(anio.dato("cantidad")).as("=3*4").isEqualTo("12");
    assertThat(anio.dato("tasaIva")).as("sin tasa del proveedor: 8%").isEqualTo("0.08");

    FilaValidada sinFecha = filaExcel(filas, "Entradas", 7);
    assertThat(sinFecha.estado()).isEqualTo(EstadoFila.CUARENTENA);
    assertThat(incidencia(sinFecha, "SIN_FECHA").detalle()).contains("2026-08-03");

    FilaValidada sinProveedor = filaExcel(filas, "Entradas", 8);
    assertThat(incidencia(sinProveedor, "SIN_PROVEEDOR").severidad())
        .isEqualTo(Severidad.ADVERTENCIA);

    cargarEntradas();
    assertThat(sistema.historico.entradas).hasSize(7);
    assertThat(sistema.historico.entradas.get(1).importe()).isEqualByComparingTo("2100");
    assertThat(sistema.historico.entradas.get(3).precioEstimado()).isTrue();
    assertThat(sistema.historico.entradas.get(1).recibeId()).isEqualTo(lalo);
  }

  // --------------------------------------------------------------------------------- salidas

  @Test
  void salidasPorLoteRepartidasSinAsignarYValuadas() {
    cargarEntradas();
    List<FilaValidada> filas = validar(TipoCarga.HIST_SALIDAS, ALMACEN);
    assertThat(filas).hasSize(15);

    FilaValidada tercio = filaExcel(filas, "Salidas", 4);
    assertThat(tercio.dato("cantidad")).isEqualTo("0.33333333");
    assertThat(incidencia(tercio, "FORMULA_EVALUADA")).isNotNull();
    assertThat(tercio.dato("costoEstimado"))
        .as("la pintura salió antes de su primera entrada")
        .isEqualTo("1");

    FilaValidada repartida = filaExcel(filas, "Salidas", 7);
    assertThat(incidencia(repartida, "LOTES_EXPANDIDOS").detalle()).contains("3 lotes");
    assertThat(repartida.dato("costo")).as("ponderado al 23 de junio").isEqualTo("205.000000");

    FilaValidada sinLote = filaExcel(filas, "Salidas", 8);
    assertThat(incidencia(sinLote, "SIN_LOTE").detalle()).contains("contrato de la manzana 20");
    assertThat(sinLote.id("contratoId")).isEqualTo(contratoA);
    assertThat(sinLote.dato("unidades")).isNull();

    FilaValidada variasManzanas = filaExcel(filas, "Salidas", 9);
    assertThat(variasManzanas.estado()).isEqualTo(EstadoFila.CUARENTENA);
    assertThat(incidencia(variasManzanas, "MANZANAS_VARIAS")).isNotNull();

    FilaValidada inexistente = filaExcel(filas, "Salidas", 10);
    FilaValidada mismoLote = filaExcel(filas, "Salidas", 11);
    assertThat(inexistente.estado()).isEqualTo(EstadoFila.CUARENTENA);
    assertThat(incidencia(mismoLote, "LOTE_INEXISTENTE")).isNotNull();

    FilaValidada deOtroFrente = filaExcel(filas, "Salidas", 12);
    Incidencia otro = incidencia(deOtroFrente, "LOTE_INEXISTENTE");
    assertThat(otro.detalle()).contains("existe en Palermo");
    assertThat(otro.sugerencia()).containsEntry("destinoId", lotes.get("M16-L34"));

    assertThat(filaExcel(filas, "Salidas", 13).dato("tipo")).isEqualTo("devolucion");
    assertThat(filaExcel(filas, "Salidas", 14).dato("tipo")).isEqualTo("reasignacion");
    FilaValidada almacen = filaExcel(filas, "Salidas", 15);
    assertThat(incidencia(almacen, "PARA_ALMACEN")).isNotNull();
    assertThat(almacen.dato("frenteId")).isNull();
    assertThat(filaExcel(filas, "Salidas", 16).dato("entregado")).isEqualTo("0");
    assertThat(incidencia(filaExcel(filas, "Salidas", 17), "FORMULA_CON_REFERENCIAS")).isNotNull();
    assertThat(filaExcel(filas, "Salidas", 3).id("paqueteId"))
        .as("\"Acabados \"")
        .isEqualTo(acabados);

    // Una decisión de lote resuelve todos los renglones que lo escriben igual.
    decidir(variasManzanas, "MANZANAS_VARIAS", Opcion.VALOR, null, "sin asignar");
    decidir(inexistente, "LOTE_INEXISTENTE", Opcion.ELEGIR, lotes.get("M20-L33"), null);
    decidir(deOtroFrente, "LOTE_INEXISTENTE", Opcion.ELEGIR, lotes.get("M16-L34"), null);
    decidir(filaExcel(filas, "Salidas", 17), "FORMULA_CON_REFERENCIAS", Opcion.VALOR, null, "2");
    filas = validar(TipoCarga.HIST_SALIDAS, ALMACEN);
    assertThat(filaExcel(filas, "Salidas", 11).estado()).isEqualTo(EstadoFila.CORREGIDA);
    assertThat(filaExcel(filas, "Salidas", 12).id("frenteId"))
        .as("el lote es de otro frente: la salida pasa a ese frente")
        .isEqualTo(palermo);

    confirmar(TipoCarga.HIST_SALIDAS, ALMACEN);
    List<Salida> salidas = sistema.historico.salidas;
    assertThat(salidas).hasSize(17).as("15 renglones, uno repartido entre 3 lotes");
    List<Salida> deRepartida = salidas.stream().filter(s -> s.traza().filaExcel() == 7).toList();
    assertThat(deRepartida)
        .extracting(Salida::cantidad)
        .containsExactly(
            new BigDecimal("3.33333333"),
            new BigDecimal("3.33333333"),
            new BigDecimal("3.33333334"));
    assertThat(deRepartida).allMatch(s -> "30, 31, 32".equals(s.lotesTexto()));
    assertThat(
            salidas.stream()
                .filter(s -> s.traza().filaExcel() == 12)
                .findFirst()
                .orElseThrow()
                .clasificacionId())
        .as("Garantía Ruba")
        .isEqualTo(2);
    Salida lijaSinAsignar =
        salidas.stream().filter(s -> s.traza().filaExcel() == 8).findFirst().orElseThrow();
    assertThat(lijaSinAsignar.unidadObraId()).isNull();
    assertThat(lijaSinAsignar.costoUnitario()).isEqualByComparingTo("8");
    assertThat(lijaSinAsignar.costoEstimado()).isTrue();
    assertThat(lijaSinAsignar.solicitanteId()).isEqualTo(mari);
  }

  // ------------------------------------------------------------------- requisiciones y préstamos

  @Test
  void requisicionesConLaUnidadComoSePidioYPrestamos() {
    List<FilaValidada> filas = confirmar(TipoCarga.HIST_REQUISICIONES, ALMACEN);
    assertThat(filas).hasSize(3);
    var semanal = sistema.historico.requisiciones.get(0);
    assertThat(semanal.unidadTexto()).isEqualTo("Paquete");
    assertThat(semanal.cantidadPedida()).as("2 + =1/2").isEqualByComparingTo("2.5");
    assertThat(semanal.unidadObraId()).isEqualTo(lotes.get("M20-L30"));
    assertThat(semanal.semana()).isEqualTo(31);
    assertThat(semanal.paqueteId()).isEqualTo(acabados);
    var noLigado = sistema.historico.requisiciones.get(1);
    assertThat(noLigado.unidadObraId()).isNull();
    assertThat(noLigado.contratoId()).as("\"20-21\" son dos manzanas").isNull();
    assertThat(noLigado.lotesTexto()).isEqualTo("Mza 20-21 / Lote -");
    assertThat(noLigado.autorizacion()).isEqualTo("No Autorizado");
    var diaria = sistema.historico.requisiciones.get(2);
    assertThat(diaria.origen()).isEqualTo("diaria");
    assertThat(diaria.frenteId()).as("Almacén").isNull();
    assertThat(diaria.suministrado()).isEqualByComparingTo("1");

    List<FilaValidada> prestamos = confirmar(TipoCarga.HIST_PRESTAMOS, ALMACEN);
    assertThat(incidencia(prestamos.get(0), "SIN_MATERIAL_EN_CATALOGO").severidad())
        .isEqualTo(Severidad.ADVERTENCIA);
    assertThat(incidencia(prestamos.get(1), "PRESTAMO_ABIERTO")).isNotNull();
    assertThat(sistema.historico.prestamos.get(0).devuelto()).isTrue();
    assertThat(sistema.historico.prestamos.get(1).materialId()).isEqualTo(lija);
    assertThat(sistema.historico.prestamos.get(1).entregarAId()).isNull();
  }

  // ----------------------------------------------------------------------- Existencias (H8)

  @Test
  void fotoDeExistenciasSoloConLoCapturado() {
    String muestra = "muestra-existencias.xlsx";
    List<FilaValidada> filas = validar(TipoCarga.HIST_EXISTENCIAS, muestra);
    FilaValidada aerosol = filaExcel(filas, "Existencias", 5);
    assertThat(aerosol.estado()).isEqualTo(EstadoFila.VALIDA);
    assertThat(aerosol.dato("inicial")).isEqualTo("10");
    assertThat(aerosol.dato("defectuoso")).isEqualTo("2");
    FilaValidada cemento = filaExcel(filas, "Existencias", 6);
    assertThat(cemento.estado()).as("=4+6").isEqualTo(EstadoFila.CORREGIDA);
    assertThat(incidencia(cemento, "FORMULA_EVALUADA")).isNotNull();
    assertThat(filaExcel(filas, "Existencias", 7).dato("inicial")).as("vacío es 0").isEqualTo("0");
    FilaValidada conReferencia = filaExcel(filas, "Existencias", 8);
    assertThat(conReferencia.estado()).isEqualTo(EstadoFila.CUARENTENA);
    assertThat(incidencia(conReferencia, "FORMULA_CON_REFERENCIAS")).isNotNull();
    assertThat(incidencia(conReferencia, "MATERIAL_REPETIDO").severidad())
        .isEqualTo(Severidad.ADVERTENCIA);
    FilaValidada repetida = filaExcel(filas, "Existencias", 9);
    assertThat(incidencia(repetida, "NUMERO_NO_GUARDADO_K").severidad())
        .isEqualTo(Severidad.ADVERTENCIA);
    assertThat(repetida.dato("defectuoso")).isNull();
    assertThat(repetida.dato("apartados")).isEqualTo("1");
    FilaValidada desconocido = filaExcel(filas, "Existencias", 10);
    assertThat(incidencia(desconocido, "MATERIAL_DESCONOCIDO")).isNotNull();

    decidir(conReferencia, "FORMULA_CON_REFERENCIAS", Opcion.VALOR, null, "3");
    decidir(desconocido, "MATERIAL_DESCONOCIDO", Opcion.DESCARTAR, null, null);
    confirmar(TipoCarga.HIST_EXISTENCIAS, muestra);
    var saldos = sistema.historico.saldos;
    assertThat(saldos).hasSize(5);
    assertThat(
            saldos.stream()
                .filter(x -> x.materialId() == pintura)
                .map(x -> x.inventarioInicial())
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .as("los dos renglones de pintura se suman")
        .isEqualByComparingTo("5");
    assertThat(saldos.getFirst().fechaInicial()).isEqualTo(LocalDate.of(2026, 6, 22));
  }

  // ------------------------------------------------------------------------------------- SUA

  @Test
  void suaDelMesConAsistenciaDobleYContratoPorTexto() {
    List<FilaValidada> filas = validar(TipoCarga.HIST_SUA, SUA);
    FilaValidada barra = filaExcel(filas, "IMSS OCTUBRE", 2);
    assertThat(barra.id("contratoId")).isEqualTo(contratoA);
    assertThat(incidencia(barra, "CODIGO_NORMALIZADO")).isNotNull();
    assertThat(barra.dato("estatus")).isEqualTo("ALTA");
    FilaValidada desconocido = filaExcel(filas, "IMSS OCTUBRE", 4);
    assertThat(desconocido.estado()).isEqualTo(EstadoFila.CUARENTENA);

    FilaValidada corceles = filaExcel(filas, "ASISTENCIA OCTUBRE", 2);
    assertThat(incidencia(corceles, "DOBLE_ASISTENCIA").detalle()).contains("4");
    assertThat(incidencia(filaExcel(filas, "ASISTENCIA OCTUBRE", 4), "MARCA_DESCONOCIDA_2"))
        .isNotNull();
    Incidencia porTexto = incidencia(filaExcel(filas, "SUA ANTERIOR", 2), "CONTRATO_POR_TEXTO");
    assertThat(porTexto.sugerencia()).containsEntry("destinoId", contratoA);

    sistema.ponerAlias("contrato", "60-ZZZ-NOEXISTE-2026", contratoB);
    confirmar(TipoCarga.HIST_SUA, SUA);
    List<AltaImss> altas = sistema.historico.altas;
    assertThat(altas).filteredOn(a -> a.origen().equals("mes")).hasSize(3);
    assertThat(altas)
        .filteredOn(a -> a.origen().equals("anterior"))
        .extracting(AltaImss::mes)
        .containsExactly(LocalDate.of(2026, 9, 1));
    List<AsistenciaDia> mario =
        sistema.historico.asistencias.stream().filter(a -> a.personaId() == this.mario).toList();
    assertThat(mario).hasSize(6);
    assertThat(mario.stream().map(AsistenciaDia::valor).reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("4.5");
    assertThat(mario).filteredOn(AsistenciaDia::dobleAsistencia).hasSize(2);
    assertThat(sistema.historico.asignaciones.getFirst().personaId()).isEqualTo(ana);
    assertThat(sistema.historico.asignaciones.getFirst().frenteId()).isEqualTo(this.corceles);
  }

  @Test
  void elMesDelSuaTieneQueCoincidirConElDeLasHojas() {
    sistema.parametro("mes", "2026-11");
    List<FilaValidada> filas = validar(TipoCarga.HIST_SUA, SUA);
    assertThat(incidencia(filaExcel(filas, "IMSS OCTUBRE", 2), "MES_DISTINTO").severidad())
        .isEqualTo(Severidad.BLOQUEO);
    assertThat(filaExcel(filas, "BD", 2).estado()).isNotEqualTo(EstadoFila.CUARENTENA);
  }

  // --------------------------------------------------------------------------------- destajo

  @Test
  void destajoConPagosParcialesSemanasYTrabajadoresJuntosYSobrepago() {
    List<FilaValidada> filas = validar(TipoCarga.HIST_DESTAJO, DESTAJO);
    assertThat(filas).as("ceros y subtotales =SUM(…) no son pagos").hasSize(4);
    FilaValidada parciales =
        filas.stream().filter(f -> "I8".equals(f.fila().valor("_celda"))).findFirst().orElseThrow();
    assertThat(incidencia(parciales, "PAGOS_PARCIALES").detalle()).contains("2 pagos parciales");
    assertThat(incidencia(parciales, "VARIAS_SEMANAS").detalle()).contains("39").contains("38");
    assertThat(parciales.id("paqueteId")).isEqualTo(trazo);
    FilaValidada sobre =
        filas.stream().filter(f -> "L8".equals(f.fila().valor("_celda"))).findFirst().orElseThrow();
    assertThat(incidencia(sobre, "SOBRE_IMPORTE").detalle()).contains("3000").contains("2400");
    FilaValidada ilegible =
        filas.stream().filter(f -> "O8".equals(f.fila().valor("_celda"))).findFirst().orElseThrow();
    assertThat(incidencia(ilegible, "LOTE_INEXISTENTE").detalle())
        .as("\"M2OL3\" se lee como manzana 2O, que no existe")
        .contains("M2O-L3");
    FilaValidada monto =
        filas.stream().filter(f -> "I9".equals(f.fila().valor("_celda"))).findFirst().orElseThrow();
    assertThat(monto.estado()).isEqualTo(EstadoFila.CUARENTENA);
    assertThat(incidencia(monto, "NO_ES_PERSONA").detalle()).contains("1000");
    assertThat(monto.id("paqueteId")).as("concepto fuera del catálogo: la sección").isNull();

    decidir(ilegible, "LOTE_INEXISTENTE", Opcion.VALOR, null, "M20L33");
    decidir(monto, "NO_ES_PERSONA", Opcion.ELEGIR, pepe, null);
    confirmar(TipoCarga.HIST_DESTAJO, DESTAJO);

    List<PagoDestajo> pagos = sistema.historico.pagos;
    assertThat(pagos).hasSize(5);
    assertThat(pagos.stream().map(PagoDestajo::monto).reduce(BigDecimal.ZERO, BigDecimal::add))
        .as("al peso con las celdas del Excel")
        .isEqualByComparingTo("9400");
    List<PagoDestajo> deI8 = pagos.stream().filter(p -> "I8".equals(p.celda())).toList();
    assertThat(deI8).extracting(PagoDestajo::parcial).containsExactly(1, 2);
    assertThat(deI8).allMatch(PagoDestajo::montoCompartido);
    assertThat(deI8.getFirst().trabajadores()).containsExactly(lalo);
    assertThat(deI8.getFirst().semanas()).containsExactly(39, 38);
    PagoDestajo compartido =
        pagos.stream().filter(p -> "L8".equals(p.celda())).findFirst().orElseThrow();
    assertThat(compartido.trabajadores()).containsExactly(pepe, mari);
    assertThat(compartido.sobreImporte()).isTrue();
    assertThat(
            pagos.stream()
                .filter(p -> "O8".equals(p.celda()))
                .findFirst()
                .orElseThrow()
                .unidadObraId())
        .isEqualTo(lotes.get("M20-L33"));
    PagoDestajo colado =
        pagos.stream().filter(p -> "I9".equals(p.celda())).findFirst().orElseThrow();
    assertThat(colado.paqueteId()).isEqualTo(seccionCimentacion);
    assertThat(colado.trabajadores()).containsExactly(pepe);
  }

  @Test
  void sinAnioNoSeCargaElDestajo() {
    sistema.parametro("anio", "");
    List<FilaValidada> filas = validar(TipoCarga.HIST_DESTAJO, DESTAJO);
    assertThat(filas).allMatch(f -> f.estado() == EstadoFila.CUARENTENA);
    assertThat(incidencia(filas.getFirst(), "SIN_ANIO").severidad()).isEqualTo(Severidad.BLOQUEO);
  }
}

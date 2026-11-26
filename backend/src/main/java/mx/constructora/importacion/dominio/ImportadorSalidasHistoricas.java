package mx.constructora.importacion.dominio;

import static mx.constructora.importacion.dominio.Comun.limpio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import mx.constructora.historico.Registros.Lote;
import mx.constructora.historico.Registros.Salida;
import mx.constructora.importacion.dominio.Modelo.Decision;
import mx.constructora.importacion.dominio.Modelo.Decisiones;
import mx.constructora.importacion.dominio.Modelo.FilaCruda;
import mx.constructora.importacion.dominio.Modelo.FilaValidada;
import mx.constructora.importacion.dominio.Modelo.Incidencia;
import mx.constructora.importacion.dominio.Modelo.Lectura;
import mx.constructora.importacion.dominio.Modelo.Opcion;
import mx.constructora.importacion.dominio.Modelo.Revision;
import mx.constructora.importacion.dominio.Modelo.Severidad;
import mx.constructora.importacion.dominio.Resolutor.Escritura;
import mx.constructora.importacion.dominio.xlsx.ArchivoInvalido;
import mx.constructora.importacion.dominio.xlsx.LibroXlsx;

/**
 * Histórico de la hoja Salidas (issue 48). Columnas capturadas: A fecha, B documento, C razón, D
 * folio, E paquetería, F manzana, G lote, H cantidad, J artículo, K solicitante, L frente, M
 * entregar a, N entregado, O observaciones. La unidad (I) es fórmula y no se lee.
 *
 * <ul>
 *   <li>Lote → unidad de obra; varios lotes en una celda ("4, 5, 6 … 12", "32-43") se reparten por
 *       igual; sin lote, al frente "Sin asignar". Varias manzanas, lote inexistente o de otro
 *       frente: cuarentena; la decisión vale para todos los renglones que escriben igual.
 *   <li>Razón → clasificación de costo; paquetería → paquete (alias).
 *   <li>Cantidad {@code =1/3} (un bote para tres lotes): se evalúa. Negativa: devolución. "Ajuste
 *       de materiales en lotes": reasignación.
 *   <li>Costo: ponderado sin IVA de las entradas del histórico hasta la fecha ({@link Valuacion}).
 * </ul>
 */
public final class ImportadorSalidasHistoricas extends ImportadorHistorico {

  static final String HOJA = "Salidas";

  private static final String AJUSTE = "AJUSTE DE MATERIALES EN LOTES";

  /** Reglas cuya decisión vale para todos los renglones con el mismo frente, manzana y lote. */
  private static final Set<String> REGLAS_DE_LOTE =
      Set.of("LOTE_INEXISTENTE", "MANZANAS_VARIAS", "LOTE_ILEGIBLE", "LOTE_SIN_MANZANA");

  @Override
  public TipoCarga tipo() {
    return TipoCarga.HIST_SALIDAS;
  }

  @Override
  public Lectura leer(LibroXlsx libro) {
    String hoja =
        Comun.hoja(libro, HOJA)
            .orElseThrow(() -> new ArchivoInvalido("El libro no tiene la hoja \"" + HOJA + "\""));
    List<FilaCruda> filas = new ArrayList<>();
    libro.leer(
        hoja,
        Set.of("A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "L", "M", "N", "O"),
        r -> {
          if (r.numero() == 2) {
            Comun.exigirEncabezado(r, hoja, "A", "Fecha");
            Comun.exigirEncabezado(r, hoja, "C", "Razón");
            Comun.exigirEncabezado(r, hoja, "F", "Mza");
            Comun.exigirEncabezado(r, hoja, "G", "Lote");
            Comun.exigirEncabezado(r, hoja, "H", "Cant");
            Comun.exigirEncabezado(r, hoja, "J", "Artículo");
            Comun.exigirEncabezado(r, hoja, "L", "Frente");
            return true;
          }
          if (r.numero() < 3 || limpio(r.capturado("J")) == null) {
            return true;
          }
          Map<String, String> crudo = new LinkedHashMap<>();
          for (String c :
              List.of("A", "B", "C", "D", "E", "F", "G", "J", "K", "L", "M", "N", "O")) {
            String v = limpio(r.capturado(c));
            if (v != null) {
              crudo.put(c, v);
            }
          }
          Resolutor.leerNumero(
              crudo,
              "H",
              limpio(r.capturado("H")),
              r.esFormula("H") ? r.celda("H").formula() : null);
          filas.add(new FilaCruda(filas.size() + 1, hoja, r.numero(), crudo));
          return true;
        });
    return new Lectura(List.of(hoja), filas);
  }

  // ------------------------------------------------------------------------------- validación

  @Override
  public List<FilaValidada> validar(
      List<FilaCruda> filas, Contexto contexto, Decisiones decisiones) {
    Map<Integer, String> grupos = new HashMap<>();
    Map<Integer, Integer> anios = new HashMap<>();
    for (FilaCruda fila : filas) {
      grupos.put(fila.numero(), grupo(fila));
      Comun.fecha(fila.valor("A")).ifPresent(f -> anios.merge(f.getYear(), 1, Integer::sum));
    }
    int anio =
        anios.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(LocalDate.now().getYear());
    Decisiones conGrupo = Resolutor.compartidas(decisiones, filas, grupos, REGLAS_DE_LOTE);
    Valuacion valuacion =
        new Valuacion(contexto.entradasHistoricas(), contexto.preciosReferencia());
    List<FilaValidada> resultado = new ArrayList<>();
    for (FilaCruda fila : filas) {
      Revision revision = new Revision(fila, conGrupo);
      validarFila(fila, revision, contexto, anio, valuacion);
      resultado.add(revision.cerrar());
    }
    return resultado;
  }

  /** Frente, manzana y lote tal como se escribieron. */
  static String grupo(FilaCruda fila) {
    return Normal.texto(fila.valor("L"))
        + "|"
        + Normal.texto(fila.valor("F"))
        + "|"
        + Normal.texto(fila.valor("G"));
  }

  private static void validarFila(
      FilaCruda fila, Revision revision, Contexto contexto, int anio, Valuacion valuacion) {
    revision.dato("_material", Normal.texto(fila.valor("J")));

    // Fecha
    LocalDate fecha = Comun.fecha(fila.valor("A")).orElse(null);
    if (fecha == null) {
      Optional<Decision> decision =
          revision.pedir(
              "SIN_FECHA",
              Severidad.CUARENTENA,
              "La salida no tiene fecha: escribe la fecha (dd/mm/aaaa) o descártala",
              null,
              EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
      fecha = decision.flatMap(d -> Comun.fecha(d.valor())).orElse(null);
    } else if (fecha.getYear() != anio) {
      Optional<Decision> decision =
          revision.pedir(
              "FECHA_FUERA_DE_RANGO",
              Severidad.CUARENTENA,
              "La fecha %s no es de %d como las demás: escribe la fecha correcta (dd/mm/aaaa)"
                  .formatted(fecha, anio),
              null,
              EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
      fecha = decision.flatMap(d -> Comun.fecha(d.valor())).orElse(null);
    }

    // Material y cantidad
    Contexto.Material material = Resolutor.material(fila.valor("J"), revision, contexto);
    Optional<BigDecimal> cantidad = Resolutor.numero(fila, "H", "La cantidad", revision);
    if (cantidad.isEmpty() && fila.valor("H") == null && fila.valor("H=") == null) {
      Optional<Decision> decision =
          revision.pedir(
              "SIN_CANTIDAD",
              Severidad.CUARENTENA,
              "La salida no dice cuánto salió: escribe la cantidad o descártala",
              null,
              EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
      cantidad = decision.flatMap(d -> Comun.decimal(d.valor()));
    }
    if (cantidad.isPresent() && cantidad.get().signum() == 0) {
      revision.descartarPorRegla("CANTIDAD_CERO", "La salida es de 0: no movió material");
      return;
    }

    // Tipo
    String observaciones = Normal.texto(fila.valor("O"));
    String tipo = "salida";
    if (observaciones != null && observaciones.contains(AJUSTE)) {
      tipo = "reasignacion";
      revision.agregar(
          Incidencia.correccion(
              "REASIGNACION_HISTORICA",
              "\"Ajuste de materiales en lotes\": se guarda como reasignación"));
    } else if (cantidad.isPresent() && cantidad.get().signum() < 0) {
      tipo = "devolucion";
      revision.agregar(
          Incidencia.correccion(
              "DEVOLUCION_HISTORICA", "Cantidad negativa: se guarda como devolución"));
    }

    // Frente y lotes
    Resolutor.Destino destino = null;
    boolean paraAlmacen = Resolutor.esAlmacen(fila.valor("L"), contexto);
    if (paraAlmacen) {
      revision.agregar(
          new Incidencia(
              "PARA_ALMACEN",
              Severidad.ADVERTENCIA,
              "Salió para el propio almacén: no se carga a ningún frente ni lote",
              false,
              null,
              Set.of(),
              null));
    } else {
      Contexto.Frente frente = Frentes.resolver(fila.valor("L"), revision, contexto);
      if (frente != null) {
        destino = Resolutor.lotes(frente, fila.valor("F"), fila.valor("G"), revision, contexto);
        if (destino == null) {
          return;
        }
      }
    }

    // Razón, paquetería, solicitante y entrega
    Integer clasificacion = Resolutor.clasificacion(fila.valor("C"), revision, contexto);
    Long paquete = Resolutor.paquete(fila.valor("E"), revision, contexto);
    List<Long> solicitante =
        Resolutor.personas(
            fila.valor("K"), Escritura.APODO, false, "SOLICITANTE_SIN_LIGAR", revision, contexto);
    boolean entregado = !"FALSE".equalsIgnoreCase(fila.valor("N"));
    if (!entregado) {
      revision.agregar(
          Incidencia.info(
              "NO_ENTREGADO", "Marcado como no entregado: se guarda, pero no suma al costo"));
    }

    if (material == null || fecha == null || cantidad.isEmpty() || revision.pendiente()) {
      return;
    }
    revision
        .dato("fecha", fecha)
        .dato("tipo", tipo)
        .dato("materialId", material.id())
        .dato("cantidad", cantidad.get().toPlainString())
        .dato("clasificacionId", clasificacion)
        .dato("paqueteId", paquete)
        .dato(
            "solicitanteId",
            solicitante == null || solicitante.isEmpty() ? null : solicitante.getFirst())
        .dato("entregado", entregado ? "1" : "0");
    if (destino != null) {
      revision
          .dato("frenteId", destino.frente().id())
          .dato("contratoId", destino.contratoId())
          .dato(
              "unidades",
              destino.unidades().isEmpty()
                  ? null
                  : destino.unidades().stream()
                      .map(u -> u.id() + ":" + u.contratoId())
                      .collect(Collectors.joining(",")));
    }

    // Costo
    Valuacion.Costo costo = valuacion.costo(material.id(), fecha);
    if (costo == null) {
      revision.agregar(
          new Incidencia(
              "SIN_COSTO",
              Severidad.ADVERTENCIA,
              "El material no tiene entradas con precio ni precio de referencia: entra sin costo",
              false,
              null,
              Set.of(),
              null));
      return;
    }
    revision.dato("costo", costo.unitario().toPlainString());
    if (costo.estimado()) {
      revision
          .dato("costoEstimado", "1")
          .agregar(
              Incidencia.info(
                  "COSTO_ESTIMADO",
                  costo.origen() == Valuacion.Origen.REFERENCIA
                      ? "El material no entró por el Excel: se valúa con su precio de referencia"
                      : "La salida es anterior a toda entrada del material: se valúa con el"
                          + " ponderado de sus entradas"));
    }
  }

  // ---------------------------------------------------------------------- registrar y conciliar

  @Override
  void registrar(FilaValidada fila, Contexto contexto, Lote lote) {
    FilaCruda c = fila.fila();
    BigDecimal cantidad = decimal(fila, "cantidad");
    BigDecimal costo = decimal(fila, "costo");
    String unidades = fila.dato("unidades");
    List<Long[]> destinos = new ArrayList<>();
    if (unidades == null) {
      destinos.add(new Long[] {null, fila.id("contratoId")});
    } else {
      for (String u : unidades.split(",")) {
        String[] partes = u.split(":");
        destinos.add(new Long[] {Long.valueOf(partes[0]), Long.valueOf(partes[1])});
      }
    }
    List<BigDecimal> partes = Resolutor.repartir(cantidad, destinos.size());
    String lotes = destinos.size() > 1 ? texto(c.valor("G"), 120) : null;
    for (int i = 0; i < destinos.size(); i++) {
      BigDecimal parte = partes.get(i);
      lote.salidas.add(
          new Salida(
              fecha(fila, "fecha"),
              fila.dato("tipo"),
              texto(c.valor("B"), 40),
              texto(c.valor("D"), 40),
              fila.id("materialId"),
              parte,
              fila.id("frenteId"),
              destinos.get(i)[1],
              destinos.get(i)[0],
              lotes,
              fila.id("paqueteId"),
              texto(c.valor("E"), 160),
              entero(fila, "clasificacionId"),
              fila.id("solicitanteId"),
              texto(c.valor("K"), 160),
              texto(c.valor("M"), 160),
              si(fila, "entregado"),
              costo,
              costo == null ? null : parte.multiply(costo).setScale(4, RoundingMode.HALF_UP),
              si(fila, "costoEstimado"),
              texto(c.valor("O"), 500),
              traza(fila)));
    }
  }

  @Override
  void conciliar(FilaValidada fila, Lote lote, Conciliacion conciliacion) {
    FilaCruda c = fila.fila();
    BigDecimal excel = excel(fila, "H", "cantidad");
    BigDecimal cargado = suma(lote.salidas.stream().map(Salida::cantidad).toList());
    conciliacion.sumar(
        "Cantidad por material y mes",
        fila.dato("_material") + " · " + mes(Comun.fecha(c.valor("A")).orElse(null)),
        fila.estado(),
        excel,
        cargado);
    conciliacion.sumar(
        "Cantidad por frente, manzana y lote",
        Optional.ofNullable(Normal.texto(c.valor("L"))).orElse("SIN FRENTE")
            + " · Mza "
            + (c.valor("F") == null ? "—" : c.valor("F"))
            + " / Lote "
            + (c.valor("G") == null ? "—" : c.valor("G")),
        fila.estado(),
        excel,
        cargado);
  }
}

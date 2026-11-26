package mx.constructora.importacion.dominio;

import static mx.constructora.importacion.dominio.Comun.limpio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import mx.constructora.estructura.ClaveUnidad;
import mx.constructora.historico.Registros.Lote;
import mx.constructora.historico.Registros.RequisicionLinea;
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
 * Histórico de las hojas Semanal y Faltante solicitud diaria (issue 49): lo que se pidió, como se
 * pidió. La unidad se guarda tal cual ("Paquete" en el 98% del Semanal): no se convierte. El lote
 * se liga si se reconoce; si no, queda el texto (una requisición no es un costo). Estatus de
 * compra, suministrado y faltantes del Semanal son fórmulas y no se leen.
 */
public final class ImportadorRequisicionesHistoricas extends ImportadorHistorico {

  static final String SEMANAL = "Semanal";
  static final String DIARIA = "Faltante solicitud diaria";

  private static final List<String> DIAS = List.of("O", "P", "Q", "R", "S", "T");

  @Override
  public TipoCarga tipo() {
    return TipoCarga.HIST_REQUISICIONES;
  }

  @Override
  public Lectura leer(LibroXlsx libro) {
    List<String> hojas = new ArrayList<>();
    List<FilaCruda> filas = new ArrayList<>();
    Optional<String> semanal = Comun.hoja(libro, SEMANAL);
    if (semanal.isPresent()) {
      hojas.add(semanal.get());
      String hoja = semanal.get();
      libro.leer(
          hoja,
          Set.of(
              "A", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T",
              "Y", "AA"),
          r -> {
            if (r.numero() == 2) {
              Comun.exigirEncabezado(r, hoja, "A", "Semana");
              Comun.exigirEncabezado(r, hoja, "K", "Cantidad");
              Comun.exigirEncabezado(r, hoja, "N", "Material");
              return true;
            }
            if (r.numero() < 3 || limpio(r.capturado("N")) == null) {
              return true;
            }
            Map<String, String> crudo = new LinkedHashMap<>();
            for (String c : List.of("A", "E", "F", "G", "H", "I", "J", "L", "M", "N", "AA")) {
              String v = limpio(r.capturado(c));
              if (v != null) {
                crudo.put(c, v);
              }
            }
            for (String c : List.of("K", "O", "P", "Q", "R", "S", "T", "Y")) {
              Resolutor.leerNumero(
                  crudo, c, limpio(r.capturado(c)), r.esFormula(c) ? r.celda(c).formula() : null);
            }
            filas.add(new FilaCruda(filas.size() + 1, hoja, r.numero(), crudo));
            return true;
          });
    }
    Optional<String> diaria = Comun.hoja(libro, DIARIA);
    if (diaria.isPresent()) {
      hojas.add(diaria.get());
      String hoja = diaria.get();
      libro.leer(
          hoja,
          Set.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "M"),
          r -> {
            if (r.numero() == 2) {
              Comun.exigirEncabezado(r, hoja, "A", "Fecha");
              Comun.exigirEncabezado(r, hoja, "C", "Cantidad");
              Comun.exigirEncabezado(r, hoja, "E", "Material");
              return true;
            }
            if (r.numero() < 3 || limpio(r.capturado("E")) == null) {
              return true;
            }
            Map<String, String> crudo = new LinkedHashMap<>();
            for (String c : List.of("A", "B", "D", "E", "F", "G", "H", "I", "M")) {
              String v = limpio(r.capturado(c));
              if (v != null) {
                crudo.put(c, v);
              }
            }
            for (String c : List.of("C", "J")) {
              Resolutor.leerNumero(
                  crudo, c, limpio(r.capturado(c)), r.esFormula(c) ? r.celda(c).formula() : null);
            }
            filas.add(new FilaCruda(filas.size() + 1, hoja, r.numero(), crudo));
            return true;
          });
    }
    if (hojas.isEmpty()) {
      throw new ArchivoInvalido(
          "El libro no tiene las hojas \"" + SEMANAL + "\" ni \"" + DIARIA + "\"");
    }
    return new Lectura(hojas, filas);
  }

  static boolean esSemanal(FilaCruda fila) {
    return Normal.texto(SEMANAL).equals(Normal.texto(fila.hoja()));
  }

  // ------------------------------------------------------------------------------- validación

  @Override
  public List<FilaValidada> validar(
      List<FilaCruda> filas, Contexto contexto, Decisiones decisiones) {
    List<FilaValidada> resultado = new ArrayList<>();
    for (FilaCruda fila : filas) {
      Revision revision = new Revision(fila, decisiones);
      if (esSemanal(fila)) {
        semanal(fila, revision, contexto);
      } else {
        diaria(fila, revision, contexto);
      }
      resultado.add(revision.cerrar());
    }
    return resultado;
  }

  private static void semanal(FilaCruda fila, Revision revision, Contexto contexto) {
    revision.dato("_material", Normal.texto(fila.valor("N")));
    Contexto.Material material = Resolutor.material(fila.valor("N"), revision, contexto);
    Contexto.Frente frente = frente(fila.valor("G"), revision, contexto);
    Optional<BigDecimal> cantidad = Resolutor.numero(fila, "K", "La cantidad", revision);
    BigDecimal pedida = null;
    for (String dia : DIAS) {
      Optional<BigDecimal> valor = Resolutor.numero(fila, dia, "El pedido del " + dia, revision);
      if (valor.isPresent()) {
        pedida = pedida == null ? valor.get() : pedida.add(valor.get());
      }
    }
    Optional<BigDecimal> cancelada = Resolutor.numero(fila, "Y", "Lo cancelado", revision);
    Long paquete = Resolutor.paquete(fila.valor("M"), revision, contexto);
    List<Long> solicitante =
        Resolutor.personas(
            fila.valor("F"), Escritura.APODO, false, "SOLICITANTE_SIN_LIGAR", revision, contexto);
    if (material == null || revision.pendiente()) {
      return;
    }
    revision
        .dato("materialId", material.id())
        .dato("cantidad", positivo(cantidad.orElse(null), "K", revision))
        .dato("pedida", positivo(pedida, "pedido", revision))
        .dato("cancelada", positivo(cancelada.orElse(null), "Y", revision))
        .dato("paqueteId", paquete)
        .dato("semana", ImportadorEntradasHistoricas.semana(fila.valor("A")))
        .dato(
            "solicitanteId",
            solicitante == null || solicitante.isEmpty() ? null : solicitante.getFirst());
    if (frente != null) {
      revision.dato("frenteId", frente.id());
      lote(frente, fila.valor("I"), fila.valor("J"), revision, contexto);
    }
  }

  private static void diaria(FilaCruda fila, Revision revision, Contexto contexto) {
    revision.dato("_material", Normal.texto(fila.valor("E")));
    LocalDate fecha = Comun.fecha(fila.valor("A")).orElse(null);
    if (fecha == null) {
      Optional<Decision> decision =
          revision.pedir(
              "SIN_FECHA",
              Severidad.CUARENTENA,
              "La solicitud no tiene fecha: escribe la fecha (dd/mm/aaaa) o descártala",
              null,
              EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
      fecha = decision.flatMap(d -> Comun.fecha(d.valor())).orElse(null);
    }
    Contexto.Material material = Resolutor.material(fila.valor("E"), revision, contexto);
    Contexto.Frente frente = frente(fila.valor("G"), revision, contexto);
    Optional<BigDecimal> cantidad = Resolutor.numero(fila, "C", "La cantidad", revision);
    Optional<BigDecimal> suministrado = Resolutor.numero(fila, "J", "Lo suministrado", revision);
    List<Long> solicitante =
        Resolutor.personas(
            fila.valor("F"), Escritura.APODO, false, "SOLICITANTE_SIN_LIGAR", revision, contexto);
    if (material == null || fecha == null || revision.pendiente()) {
      return;
    }
    revision
        .dato("fecha", fecha)
        .dato("materialId", material.id())
        .dato("cantidad", positivo(cantidad.orElse(null), "C", revision))
        .dato("suministrado", positivo(suministrado.orElse(null), "J", revision))
        .dato("frenteId", frente == null ? null : frente.id())
        .dato(
            "solicitanteId",
            solicitante == null || solicitante.isEmpty() ? null : solicitante.getFirst());
  }

  /** El frente; "Almacén" es una solicitud para el propio almacén (sin frente). */
  private static Contexto.Frente frente(String texto, Revision revision, Contexto contexto) {
    if (texto == null) {
      return null;
    }
    if (Resolutor.esAlmacen(texto, contexto)) {
      revision.agregar(Incidencia.info("PARA_ALMACEN", "Es una solicitud para el propio almacén"));
      return null;
    }
    return Frentes.resolver(texto, revision, contexto);
  }

  /** Liga el lote si es uno solo y existe en el frente; si no, queda solo el texto. */
  private static void lote(
      Contexto.Frente frente, String manzana, String lote, Revision revision, Contexto contexto) {
    String mza = limpio(manzana);
    String lt = limpio(lote);
    if (mza == null && lt == null) {
      return;
    }
    try {
      String clave = new ClaveUnidad(mza, lt).clave();
      Optional<Contexto.Unidad> unidad = Resolutor.unidad(frente.id(), clave, contexto);
      if (unidad.isPresent()) {
        revision.dato("unidadId", unidad.get().id()).dato("contratoId", unidad.get().contratoId());
        return;
      }
    } catch (IllegalArgumentException e) {
      // Varias manzanas o lotes ("20-21", "-"): se guarda el texto.
    }
    Long contrato = mza == null ? null : Resolutor.contratoDeManzana(frente, mza, contexto);
    if (contrato != null) {
      revision.dato("contratoId", contrato);
    }
    revision.agregar(
        Incidencia.info(
            "LOTE_NO_LIGADO",
            "\"Mza %s / Lote %s\" no es un lote del sistema: se guarda el texto"
                .formatted(mza == null ? "—" : mza, lt == null ? "—" : lt)));
  }

  private static String positivo(BigDecimal valor, String columna, Revision revision) {
    if (valor == null) {
      return null;
    }
    if (valor.signum() < 0) {
      revision.agregar(
          Incidencia.correccion(
              "CANTIDAD_NEGATIVA_" + columna.toUpperCase(),
              "Una cantidad negativa en una solicitud no tiene sentido: no se guarda"));
      return null;
    }
    return valor.toPlainString();
  }

  // ---------------------------------------------------------------------- registrar y conciliar

  @Override
  void registrar(FilaValidada fila, Contexto contexto, Lote lote) {
    FilaCruda c = fila.fila();
    boolean semanal = esSemanal(c);
    String lotes = null;
    if (semanal && (c.valor("I") != null || c.valor("J") != null)) {
      lotes =
          texto(
              "Mza %s / Lote %s"
                  .formatted(
                      Optional.ofNullable(c.valor("I")).orElse("—"),
                      Optional.ofNullable(c.valor("J")).orElse("—")),
              120);
    }
    String observaciones =
        semanal
            ? c.valor("AA")
            : String.join(
                " · ", Stream.of(c.valor("H"), c.valor("M")).filter(Objects::nonNull).toList());
    lote.requisiciones.add(
        new RequisicionLinea(
            semanal ? "semanal" : "diaria",
            fecha(fila, "fecha"),
            entero(fila, "semana"),
            semanal ? texto(c.valor("A"), 60) : null,
            semanal ? null : texto(c.valor("B"), 40),
            fila.id("frenteId"),
            semanal ? texto(c.valor("H"), 40) : null,
            fila.id("contratoId"),
            fila.id("unidadId"),
            lotes,
            fila.id("materialId"),
            decimal(fila, "cantidad"),
            texto(c.valor(semanal ? "L" : "D"), 40),
            decimal(fila, "pedida"),
            decimal(fila, "cancelada"),
            decimal(fila, "suministrado"),
            fila.id("paqueteId"),
            semanal ? texto(c.valor("M"), 160) : null,
            fila.id("solicitanteId"),
            texto(c.valor("F"), 160),
            semanal ? texto(c.valor("E"), 40) : null,
            semanal ? null : texto(c.valor("I"), 60),
            texto(observaciones, 500),
            traza(fila)));
  }

  @Override
  void conciliar(FilaValidada fila, Lote lote, Conciliacion conciliacion) {
    FilaCruda c = fila.fila();
    boolean semanal = esSemanal(c);
    conciliacion.sumar(
        "Renglones por hoja",
        c.hoja(),
        fila.estado(),
        BigDecimal.ONE,
        BigDecimal.valueOf(lote.requisiciones.size()));
    BigDecimal excel = excel(fila, semanal ? "K" : "C", "cantidad");
    if (excel != null && excel.signum() >= 0) {
      conciliacion.sumar(
          "Cantidad solicitada por hoja y material",
          c.hoja() + " · " + fila.dato("_material"),
          fila.estado(),
          excel,
          suma(
              lote.requisiciones.stream()
                  .map(RequisicionLinea::cantidad)
                  .filter(Objects::nonNull)
                  .toList()));
    }
  }
}

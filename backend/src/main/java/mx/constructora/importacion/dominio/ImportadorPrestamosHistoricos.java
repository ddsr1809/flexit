package mx.constructora.importacion.dominio;

import static mx.constructora.importacion.dominio.Comun.limpio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mx.constructora.historico.Registros.Lote;
import mx.constructora.historico.Registros.Prestamo;
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
 * Histórico de la hoja Préstamo Herramientas-Material (issue 49): todos los préstamos, devueltos o
 * no. Los que siguen abiertos pasan a préstamos vivos en el corte (H8, issue 54). La herramienta se
 * liga a un material si existe; si no, queda su nombre (decisión pendiente de H4: si las
 * herramientas se dan de alta como materiales).
 */
public final class ImportadorPrestamosHistoricos extends ImportadorHistorico {

  static final String HOJA = "Prestamo Herramientas-Material";

  @Override
  public TipoCarga tipo() {
    return TipoCarga.HIST_PRESTAMOS;
  }

  @Override
  public Lectura leer(LibroXlsx libro) {
    String hoja =
        Comun.hoja(libro, HOJA)
            .orElseThrow(() -> new ArchivoInvalido("El libro no tiene la hoja \"" + HOJA + "\""));
    List<FilaCruda> filas = new ArrayList<>();
    libro.leer(
        hoja,
        Set.of("A", "B", "C", "D", "E", "F", "H", "I", "J", "K", "L", "N"),
        r -> {
          if (r.numero() == 1) {
            Comun.exigirEncabezado(r, hoja, "C", "Cant");
            Comun.exigirEncabezado(r, hoja, "E", "Artículo");
            Comun.exigirEncabezado(r, hoja, "K", "Recibido");
            return true;
          }
          if (limpio(r.capturado("E")) == null) {
            return true;
          }
          Map<String, String> crudo = new LinkedHashMap<>();
          for (String c : List.of("A", "B", "D", "E", "F", "H", "I", "J", "K", "L", "N")) {
            String v = limpio(r.capturado(c));
            if (v != null) {
              crudo.put(c, v);
            }
          }
          Resolutor.leerNumero(
              crudo,
              "C",
              limpio(r.capturado("C")),
              r.esFormula("C") ? r.celda("C").formula() : null);
          filas.add(new FilaCruda(filas.size() + 1, hoja, r.numero(), crudo));
          return true;
        });
    return new Lectura(List.of(hoja), filas);
  }

  @Override
  public List<FilaValidada> validar(
      List<FilaCruda> filas, Contexto contexto, Decisiones decisiones) {
    List<FilaValidada> resultado = new ArrayList<>();
    for (FilaCruda fila : filas) {
      Revision revision = new Revision(fila, decisiones);
      validarFila(fila, revision, contexto);
      resultado.add(revision.cerrar());
    }
    return resultado;
  }

  private static void validarFila(FilaCruda fila, Revision revision, Contexto contexto) {
    Optional<BigDecimal> cantidad = Resolutor.numero(fila, "C", "La cantidad", revision);
    if (cantidad.isEmpty() && fila.valor("C") == null && fila.valor("C=") == null) {
      cantidad = Optional.of(BigDecimal.ONE);
      revision.agregar(
          Incidencia.correccion("CANTIDAD_UNO", "El préstamo no dice cuántos: se toma 1"));
    }
    if (cantidad.isPresent() && cantidad.get().signum() <= 0) {
      Optional<Decision> decision =
          revision.pedir(
              "CANTIDAD_NO_POSITIVA",
              Severidad.CUARENTENA,
              "Un préstamo de " + cantidad.get().toPlainString() + ": escribe cuántos se prestaron",
              null,
              EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
      cantidad = decision.flatMap(d -> Comun.decimal(d.valor())).filter(v -> v.signum() > 0);
    }
    Contexto.Material material = Resolutor.materialOpcional(fila.valor("E"), revision, contexto);
    Contexto.Frente frente = null;
    if (fila.valor("I") != null && !Resolutor.esAlmacen(fila.valor("I"), contexto)) {
      frente = Frentes.resolver(fila.valor("I"), revision, contexto);
    }
    List<Long> solicitante =
        Resolutor.personas(
            fila.valor("H"), Escritura.APODO, false, "SOLICITANTE_SIN_LIGAR", revision, contexto);
    boolean devuelto = "TRUE".equalsIgnoreCase(fila.valor("K"));
    LocalDate fecha = Comun.fecha(fila.valor("F")).orElse(null);
    LocalDate devueltoEn = devuelto ? Comun.fecha(fila.valor("L")).orElse(null) : null;
    if (devueltoEn != null && fecha != null && devueltoEn.isBefore(fecha)) {
      revision.agregar(
          new Incidencia(
              "DEVOLUCION_ANTES_DEL_PRESTAMO",
              Severidad.ADVERTENCIA,
              "Se devolvió (%s) antes de prestarse (%s): se guarda como está"
                  .formatted(devueltoEn, fecha),
              false,
              null,
              Set.of(),
              null));
    }
    if (!devuelto) {
      revision.agregar(
          Incidencia.info("PRESTAMO_ABIERTO", "Sigue prestado: pasa a préstamo vivo en el corte"));
    }
    if (cantidad.isEmpty() || revision.pendiente()) {
      return;
    }
    revision
        .dato("cantidad", cantidad.get().toPlainString())
        .dato("materialId", material == null ? null : material.id())
        .dato("frenteId", frente == null ? null : frente.id())
        .dato(
            "solicitanteId",
            solicitante == null || solicitante.isEmpty() ? null : solicitante.getFirst())
        .dato(
            "entregarAId", Resolutor.personaSilenciosa(fila.valor("J"), Escritura.APODO, contexto))
        .dato("fecha", fecha)
        .dato("devuelto", devuelto ? "1" : "0")
        .dato("devueltoEn", devueltoEn);
  }

  @Override
  void registrar(FilaValidada fila, Contexto contexto, Lote lote) {
    FilaCruda c = fila.fila();
    String lotes =
        c.valor("A") == null && c.valor("B") == null
            ? null
            : texto(
                "Mza %s / Lote %s"
                    .formatted(
                        Optional.ofNullable(c.valor("A")).orElse("—"),
                        Optional.ofNullable(c.valor("B")).orElse("—")),
                120);
    lote.prestamos.add(
        new Prestamo(
            fecha(fila, "fecha"),
            fila.id("materialId"),
            texto(c.valor("E"), 200),
            decimal(fila, "cantidad"),
            texto(c.valor("D"), 40),
            fila.id("frenteId"),
            lotes,
            fila.id("solicitanteId"),
            texto(c.valor("H"), 160),
            fila.id("entregarAId"),
            texto(c.valor("J"), 160),
            si(fila, "devuelto"),
            fecha(fila, "devueltoEn"),
            texto(c.valor("N"), 500),
            traza(fila)));
  }

  @Override
  void conciliar(FilaValidada fila, Lote lote, Conciliacion conciliacion) {
    boolean devuelto = "TRUE".equalsIgnoreCase(fila.fila().valor("K"));
    conciliacion.sumar(
        "Préstamos (renglones)",
        devuelto ? "devueltos" : "abiertos",
        fila.estado(),
        BigDecimal.ONE,
        BigDecimal.valueOf(lote.prestamos.size()));
  }
}

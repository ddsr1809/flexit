package mx.constructora.importacion.dominio;

import static mx.constructora.importacion.dominio.Comun.limpio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mx.constructora.historico.Registros.Lote;
import mx.constructora.historico.Registros.SaldoExcel;
import mx.constructora.importacion.dominio.Modelo.Decisiones;
import mx.constructora.importacion.dominio.Modelo.FilaCruda;
import mx.constructora.importacion.dominio.Modelo.FilaValidada;
import mx.constructora.importacion.dominio.Modelo.Incidencia;
import mx.constructora.importacion.dominio.Modelo.Lectura;
import mx.constructora.importacion.dominio.Modelo.Revision;
import mx.constructora.importacion.dominio.Modelo.Severidad;
import mx.constructora.importacion.dominio.xlsx.ArchivoInvalido;
import mx.constructora.importacion.dominio.xlsx.LibroXlsx;

/**
 * Foto de la hoja Existencias para el corte (H8, issue 54): lo que se capturó de cada material,
 * Inventario inicial (O), su fecha (P), Material defectuoso (K) y Apartados (L). La existencia del
 * Excel (J = O + M − N) es fórmula y no se lee: el sistema la recalcula con las entradas y salidas
 * del histórico, y la compara con el conteo físico de la apertura.
 *
 * <ul>
 *   <li>Inventario inicial vacío: 0, como lo suma la hoja.
 *   <li>Inventario inicial con fórmula: la aritmética simple se evalúa; con referencias va a la
 *       cuarentena (se escribe el valor o se descarta).
 *   <li>Defectuoso o apartados que no son número capturado: no se guardan (advertencia).
 *   <li>El mismo material en dos renglones: entran los dos y se suman (advertencia).
 * </ul>
 */
public final class ImportadorExistenciasHistoricas extends ImportadorHistorico {

  static final String HOJA = "Existencias";

  @Override
  public TipoCarga tipo() {
    return TipoCarga.HIST_EXISTENCIAS;
  }

  @Override
  public Lectura leer(LibroXlsx libro) {
    String hoja =
        Comun.hoja(libro, HOJA)
            .orElseThrow(() -> new ArchivoInvalido("El libro no tiene la hoja \"" + HOJA + "\""));
    List<FilaCruda> filas = new ArrayList<>();
    libro.leer(
        hoja,
        Set.of("E", "F", "K", "L", "O", "P"),
        r -> {
          if (r.numero() == 3) {
            Comun.exigirEncabezado(r, hoja, "F", "Artículo");
            Comun.exigirEncabezado(r, hoja, "O", "Inventario inicial");
          }
          if (r.numero() < 5) {
            return true;
          }
          String nombre = limpio(r.capturado("F"));
          if (nombre == null) {
            return true;
          }
          Map<String, String> crudo = new LinkedHashMap<>();
          crudo.put("F", nombre);
          for (String c : List.of("E", "K", "L", "P")) {
            String v = limpio(r.capturado(c));
            if (v != null) {
              crudo.put(c, v);
            }
          }
          Resolutor.leerNumero(
              crudo,
              "O",
              limpio(r.capturado("O")),
              r.esFormula("O") ? r.celda("O").formula() : null);
          filas.add(new FilaCruda(filas.size() + 1, hoja, r.numero(), crudo));
          return true;
        });
    if (filas.isEmpty()) {
      throw new ArchivoInvalido("La hoja \"" + hoja + "\" no tiene materiales");
    }
    return new Lectura(List.of(hoja), filas);
  }

  @Override
  public List<FilaValidada> validar(
      List<FilaCruda> filas, Contexto contexto, Decisiones decisiones) {
    Map<String, Integer> veces = new HashMap<>();
    for (FilaCruda fila : filas) {
      String normal = Normal.texto(fila.valor("F"));
      if (normal != null) {
        veces.merge(normal, 1, Integer::sum);
      }
    }
    List<FilaValidada> resultado = new ArrayList<>();
    for (FilaCruda fila : filas) {
      Revision revision = new Revision(fila, decisiones);
      validarFila(fila, revision, contexto, veces);
      resultado.add(revision.cerrar());
    }
    return resultado;
  }

  private static void validarFila(
      FilaCruda fila, Revision revision, Contexto contexto, Map<String, Integer> veces) {
    String normal = Normal.texto(fila.valor("F"));
    revision.dato("_material", normal);
    Contexto.Material material = Resolutor.material(fila.valor("F"), revision, contexto);
    if (veces.getOrDefault(normal, 0) > 1) {
      revision.agregar(
          new Incidencia(
              "MATERIAL_REPETIDO",
              Severidad.ADVERTENCIA,
              "\"%s\" aparece %d veces en Existencias: se suman"
                  .formatted(limpio(fila.valor("F")), veces.get(normal)),
              false,
              null,
              Set.of(),
              null));
    }
    BigDecimal inicial;
    if (fila.valor("O") == null && fila.valor("O=") == null) {
      inicial = BigDecimal.ZERO;
    } else {
      Optional<BigDecimal> leido = Resolutor.numero(fila, "O", "El inventario inicial", revision);
      if (leido.isEmpty()) {
        return;
      }
      inicial = leido.get();
      if (inicial.signum() < 0) {
        revision.agregar(
            new Incidencia(
                "INICIAL_NEGATIVO",
                Severidad.ADVERTENCIA,
                "Inventario inicial negativo ("
                    + inicial.toPlainString()
                    + "): se guarda como está",
                false,
                null,
                Set.of(),
                null));
      }
    }
    BigDecimal defectuoso = opcional(fila, "K", "Material defectuoso", revision);
    BigDecimal apartados = opcional(fila, "L", "Apartados", revision);
    LocalDate fecha = null;
    if (fila.valor("P") != null) {
      fecha = Comun.fecha(fila.valor("P")).orElse(null);
      if (fecha == null) {
        revision.agregar(
            Incidencia.info(
                "FECHA_ILEGIBLE",
                "La fecha \"" + fila.valor("P") + "\" no se entiende: no se guarda"));
      }
    }
    if (material == null || revision.pendiente()) {
      return;
    }
    revision
        .dato("materialId", material.id())
        .dato("inicial", inicial.toPlainString())
        .dato("defectuoso", defectuoso == null ? null : defectuoso.toPlainString())
        .dato("apartados", apartados == null ? null : apartados.toPlainString())
        .dato("fecha", fecha);
  }

  /** Un número capturado que solo informa: si no se entiende, no se guarda. */
  private static BigDecimal opcional(
      FilaCruda fila, String columna, String nombre, Revision revision) {
    String valor = fila.valor(columna);
    if (valor == null) {
      return null;
    }
    Optional<BigDecimal> numero = Comun.decimal(valor);
    if (numero.isEmpty()) {
      revision.agregar(
          new Incidencia(
              "NUMERO_NO_GUARDADO_" + columna,
              Severidad.ADVERTENCIA,
              "%s \"%s\" no es un número: no se guarda".formatted(nombre, valor),
              false,
              null,
              Set.of(),
              null));
      return null;
    }
    return numero.get();
  }

  @Override
  void registrar(FilaValidada fila, Contexto contexto, Lote lote) {
    lote.saldos.add(
        new SaldoExcel(
            fila.id("materialId"),
            decimal(fila, "inicial"),
            fecha(fila, "fecha"),
            decimal(fila, "defectuoso"),
            decimal(fila, "apartados"),
            traza(fila)));
  }

  @Override
  void conciliar(FilaValidada fila, Lote lote, Conciliacion conciliacion) {
    BigDecimal excel = excel(fila, "O", "inicial");
    conciliacion.sumar(
        "Inventario inicial por material",
        fila.dato("_material"),
        fila.estado(),
        excel == null ? BigDecimal.ZERO : excel,
        suma(lote.saldos.stream().map(SaldoExcel::inventarioInicial).toList()));
    conciliacion.sumar(
        "Materiales (renglones)",
        "Existencias",
        fila.estado(),
        BigDecimal.ONE,
        BigDecimal.valueOf(lote.saldos.size()));
  }
}

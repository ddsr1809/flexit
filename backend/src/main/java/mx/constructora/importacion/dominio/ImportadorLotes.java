package mx.constructora.importacion.dominio;

import static mx.constructora.importacion.dominio.Comun.limpio;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.constructora.estructura.ClaveUnidad;
import mx.constructora.estructura.CodigoContrato;
import mx.constructora.importacion.dominio.Modelo.Accion;
import mx.constructora.importacion.dominio.Modelo.Decision;
import mx.constructora.importacion.dominio.Modelo.Decisiones;
import mx.constructora.importacion.dominio.Modelo.FilaCruda;
import mx.constructora.importacion.dominio.Modelo.FilaValidada;
import mx.constructora.importacion.dominio.Modelo.Incidencia;
import mx.constructora.importacion.dominio.Modelo.Lectura;
import mx.constructora.importacion.dominio.Modelo.Opcion;
import mx.constructora.importacion.dominio.Modelo.Revision;
import mx.constructora.importacion.dominio.Modelo.Severidad;
import mx.constructora.importacion.dominio.xlsx.ArchivoInvalido;
import mx.constructora.importacion.dominio.xlsx.LibroXlsx;
import mx.constructora.importacion.dominio.xlsx.Renglon;

/**
 * Lotes y prototipos. Dos perfiles:
 *
 * <ul>
 *   <li><b>Destajo</b>: cada hoja de pagos ("Pagos Corceles P-65") trae en la fila 6 los lotes
 *       ({@code M20L10}) y en su nombre el frente y el prototipo. Ningún Excel dice de qué contrato
 *       es cada lote: lo elige una persona en la cuarentena, por manzana.
 *   <li><b>Plantilla</b>: una hoja "Lotes" con Frente, Contrato, Manzana, Lote(s) y Prototipo, para
 *       cuando coordinación ya tiene la lista. "29-31, 35" se expande en un renglón por lote.
 * </ul>
 *
 * Un lote no se repite en el frente (regla de H2); si ya existe, no se toca.
 */
public final class ImportadorLotes implements Importador {

  static final String HOJA_PLANTILLA = "Lotes";

  /** "P-65", "P 65", "P65" al final del nombre de la hoja. */
  private static final Pattern PROTOTIPO_EN_HOJA = Pattern.compile("\\bP\\s*-?\\s*(\\d{2,3})\\s*$");

  /** "65", "P-65", "T-65", "Prototipo 65". */
  private static final Pattern PROTOTIPO =
      Pattern.compile("^(?:PROTOTIPO\\s*|[PT]\\s*-?\\s*)?(\\d{1,4}[A-Z]?)$");

  private static final List<String> ENCABEZADOS =
      List.of("Frente", "Contrato", "Manzana", "Lote", "Prototipo");

  @Override
  public TipoCarga tipo() {
    return TipoCarga.LOTES;
  }

  @Override
  public Lectura leer(LibroXlsx libro) {
    Optional<String> plantilla = Comun.hoja(libro, HOJA_PLANTILLA);
    return plantilla.isPresent() ? leerPlantilla(libro, plantilla.get()) : leerDestajo(libro);
  }

  /** Hojas del destajo que son de pagos por prototipo. */
  static List<String> hojasDeDestajo(LibroXlsx libro) {
    return libro.hojas().stream()
        .filter(h -> PROTOTIPO_EN_HOJA.matcher(Normal.texto(h)).find())
        .toList();
  }

  /** "Pagos Corceles P-65" → "P-65". */
  static String prototipoDeHoja(String hoja) {
    Matcher m = PROTOTIPO_EN_HOJA.matcher(Normal.texto(hoja));
    return m.find() ? "P-" + m.group(1) : null;
  }

  private Lectura leerDestajo(LibroXlsx libro) {
    List<String> hojas = hojasDeDestajo(libro);
    if (hojas.isEmpty()) {
      throw new ArchivoInvalido(
          "El libro no tiene hojas de pagos del destajo (\"Pagos Corceles P-65\") ni una hoja"
              + " \"Lotes\"");
    }
    List<FilaCruda> filas = new ArrayList<>();
    for (String hoja : hojas) {
      List<Renglon> encabezado = Comun.renglones(libro, hoja, null, 6, 6);
      Renglon fila6 = encabezado.isEmpty() ? null : encabezado.getFirst();
      Comun.exigirEncabezado(fila6, hoja, "A", "No.");
      fila6.celdas().values().stream()
          .filter(c -> LibroXlsx.indiceColumna(c.columna()) >= LibroXlsx.indiceColumna("I"))
          .filter(c -> c.capturado() != null)
          .sorted(
              (a, b) -> LibroXlsx.indiceColumna(a.columna()) - LibroXlsx.indiceColumna(b.columna()))
          .forEach(
              c -> {
                Map<String, String> crudo = new LinkedHashMap<>();
                crudo.put("lote", c.capturado().trim());
                crudo.put("_celda", c.referencia());
                crudo.put("_prototipo", prototipoDeHoja(hoja));
                filas.add(new FilaCruda(filas.size() + 1, hoja, 6, crudo));
              });
    }
    return new Lectura(hojas, filas);
  }

  private Lectura leerPlantilla(LibroXlsx libro, String hoja) {
    List<FilaCruda> filas = new ArrayList<>();
    libro.leer(
        hoja,
        Set.of("A", "B", "C", "D", "E"),
        r -> {
          if (r.numero() == 1) {
            String[] columnas = {"A", "B", "C", "D", "E"};
            for (int i = 0; i < columnas.length; i++) {
              Comun.exigirEncabezado(r, hoja, columnas[i], ENCABEZADOS.get(i));
            }
            return true;
          }
          String lotes = limpio(r.capturado("D"));
          if (lotes == null && limpio(r.capturado("C")) == null) {
            return true;
          }
          List<String> expandidos;
          try {
            expandidos = lotes == null ? List.of("") : ClaveUnidad.expandirLotes(lotes);
          } catch (IllegalArgumentException e) {
            expandidos = List.of(lotes);
          }
          for (String lote : expandidos) {
            Map<String, String> crudo = new LinkedHashMap<>();
            for (String columna : List.of("A", "B", "C", "E")) {
              String valor = limpio(r.capturado(columna));
              if (valor != null) {
                crudo.put(columna, valor);
              }
            }
            crudo.put("D", lote);
            if (expandidos.size() > 1) {
              crudo.put("_expandido", lotes);
            }
            filas.add(new FilaCruda(filas.size() + 1, hoja, r.numero(), crudo));
          }
          return true;
        });
    return new Lectura(List.of(hoja), filas);
  }

  // ------------------------------------------------------------------------------- validación

  @Override
  public List<FilaValidada> validar(
      List<FilaCruda> filas, Contexto contexto, Decisiones decisiones) {
    // Primera vuelta: lote y frente de cada renglón, para detectar repetidos y para sugerir el
    // contrato que ya se eligió para otro lote de la misma manzana.
    Map<Integer, Revision> revisiones = new LinkedHashMap<>();
    Map<String, List<Integer>> porLote = new HashMap<>();
    Map<String, Long> contratoPorManzana = new HashMap<>();
    Map<Integer, String> prototipoPorFila = new HashMap<>();
    for (FilaCruda fila : filas) {
      Revision revision = new Revision(fila, decisiones);
      revisiones.put(fila.numero(), revision);
      boolean plantilla = !fila.crudo().containsKey("_celda");
      Contexto.Frente frente =
          Frentes.resolver(plantilla ? fila.valor("A") : fila.hoja(), revision, contexto);
      ClaveUnidad clave = clave(fila, plantilla, revision);
      if (frente == null || clave == null) {
        continue;
      }
      revision
          .dato("frenteId", frente.id())
          .dato("clave", clave.clave())
          .dato("manzana", clave.manzana())
          .dato("lote", clave.lote());
      prototipoPorFila.put(
          fila.numero(), clavePrototipo(plantilla ? fila.valor("E") : fila.valor("_prototipo")));
      boolean descartado =
          decisiones
              .de(fila.numero(), "PROTOTIPO_CONTRADICTORIO")
              .map(d -> d.opcion() == Opcion.DESCARTAR)
              .orElse(false);
      if (!descartado) {
        porLote
            .computeIfAbsent(frente.id() + "|" + clave.clave(), k -> new ArrayList<>())
            .add(fila.numero());
      }
      decisiones
          .de(fila.numero(), "LOTE_SIN_CONTRATO")
          .filter(d -> d.destinoId() != null)
          .ifPresent(
              d ->
                  contratoPorManzana.putIfAbsent(
                      frente.id() + "|" + clave.manzana(), d.destinoId()));
    }

    List<FilaValidada> resultado = new ArrayList<>();
    for (FilaCruda fila : filas) {
      Revision revision = revisiones.get(fila.numero());
      if (revision.dato("clave") != null) {
        validarLote(fila, revision, contexto, porLote, prototipoPorFila, contratoPorManzana);
      }
      resultado.add(revision.cerrar());
    }
    return resultado;
  }

  private static ClaveUnidad clave(FilaCruda fila, boolean plantilla, Revision revision) {
    if (plantilla) {
      try {
        ClaveUnidad clave = new ClaveUnidad(fila.valor("C"), fila.valor("D"));
        if (fila.crudo().containsKey("_expandido")) {
          revision.agregar(
              Incidencia.correccion(
                  "LOTES_EXPANDIDOS",
                  "\"" + fila.valor("_expandido") + "\" se expandió en un renglón por lote"));
        }
        return clave;
      } catch (IllegalArgumentException e) {
        revision.pedir(
            "LOTE_ILEGIBLE",
            Severidad.BLOQUEO,
            "Manzana \"%s\" y lote \"%s\": %s"
                .formatted(fila.valor("C"), fila.valor("D"), e.getMessage()),
            null,
            EnumSet.of(Opcion.DESCARTAR));
        return null;
      }
    }
    Optional<ClaveUnidad> clave = ClaveUnidad.leer(fila.valor("lote"));
    if (clave.isPresent()) {
      return clave.get();
    }
    Optional<Decision> decision =
        revision.pedir(
            "LOTE_ILEGIBLE",
            Severidad.CUARENTENA,
            "\"%s\" (%s) no es un lote como M20L10: escribe el lote correcto o descártalo"
                .formatted(fila.valor("lote"), fila.valor("_celda")),
            null,
            EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
    Optional<ClaveUnidad> corregida = decision.flatMap(d -> ClaveUnidad.leer(d.valor()));
    if (decision.isPresent() && corregida.isEmpty()) {
      revision.pedir(
          "LOTE_CORREGIDO_ILEGIBLE",
          Severidad.CUARENTENA,
          "\"" + decision.get().valor() + "\" tampoco es un lote: escribe uno como M7L16",
          null,
          EnumSet.of(Opcion.DESCARTAR));
    }
    return corregida.orElse(null);
  }

  private static void validarLote(
      FilaCruda fila,
      Revision revision,
      Contexto contexto,
      Map<String, List<Integer>> porLote,
      Map<Integer, String> prototipoPorFila,
      Map<String, Long> contratoPorManzana) {
    boolean plantilla = !fila.crudo().containsKey("_celda");
    long frenteId = Long.parseLong(revision.dato("frenteId"));
    String clave = revision.dato("clave");

    // Prototipo
    String textoPrototipo = plantilla ? fila.valor("E") : fila.valor("_prototipo");
    Contexto.Prototipo prototipo = null;
    if (textoPrototipo != null) {
      String clavePrototipo = clavePrototipo(textoPrototipo);
      if (clavePrototipo == null) {
        revision.agregar(
            new Incidencia(
                "PROTOTIPO_ILEGIBLE",
                Severidad.ADVERTENCIA,
                "\"" + textoPrototipo + "\" no es un prototipo: el lote se carga sin prototipo",
                true,
                null,
                Set.of(),
                null));
      } else {
        if (plantilla && !clavePrototipo.equals(Normal.texto(textoPrototipo))) {
          revision.agregar(
              Incidencia.correccion(
                  "PROTOTIPO_NORMALIZADO", textoPrototipo + " se guarda como " + clavePrototipo));
        }
        Optional<Long> alias = contexto.alias("prototipo", Normal.texto(textoPrototipo));
        prototipo =
            contexto.prototipos().stream()
                .filter(
                    p ->
                        alias.map(a -> a == p.id()).orElse(false)
                            || p.clave().equals(clavePrototipo))
                .findFirst()
                .orElse(null);
        if (prototipo == null) {
          revision
              .dato("prototipoClave", clavePrototipo)
              .agregar(
                  Incidencia.info(
                      "PROTOTIPO_NUEVO", "Se da de alta el prototipo " + clavePrototipo));
        } else {
          revision.dato("prototipoId", prototipo.id());
        }
      }
    } else {
      revision.agregar(
          new Incidencia(
              "SIN_PROTOTIPO",
              Severidad.ADVERTENCIA,
              "El lote se carga sin prototipo",
              false,
              null,
              Set.of(),
              null));
    }

    // ¿Ya existe en el frente?
    Optional<Contexto.Unidad> existente =
        contexto.unidades().stream()
            .filter(u -> Objects.equals(u.frenteId(), frenteId) && u.clave().equals(clave))
            .findFirst();
    if (existente.isPresent()) {
      revision
          .dato("unidadId", existente.get().id())
          .dato("contratoId", existente.get().contratoId())
          .accion(Accion.EXISTE)
          .agregar(Incidencia.info("YA_EXISTE", "El lote ya está en el sistema"));
      Long prototipoId = prototipo == null ? null : prototipo.id();
      if (prototipoId != null
          && existente.get().prototipoId() != null
          && !prototipoId.equals(existente.get().prototipoId())) {
        revision.agregar(
            new Incidencia(
                "PROTOTIPO_DISTINTO",
                Severidad.ADVERTENCIA,
                "En el sistema el lote tiene otro prototipo; no se cambia",
                false,
                null,
                Set.of(),
                null));
      }
      return;
    }

    // Repetidos dentro de la carga: mismo prototipo → el segundo no hace nada; distinto → se
    // pregunta.
    List<Integer> mismos = porLote.get(frenteId + "|" + clave);
    // Un renglón descartado por contradictorio no está en `mismos`: se vuelve a preguntar para que
    // la decisión de descartarlo se aplique.
    if (mismos != null && (mismos.size() > 1 || !mismos.contains(fila.numero()))) {
      String mio = prototipoPorFila.get(fila.numero());
      boolean contradictorio =
          mismos.stream().anyMatch(otro -> !Objects.equals(prototipoPorFila.get(otro), mio));
      if (contradictorio) {
        revision.pedir(
            "PROTOTIPO_CONTRADICTORIO",
            Severidad.CUARENTENA,
            "El lote %s aparece en hojas de prototipos distintos (renglones %s). Descarta el que no corresponde"
                .formatted(clave, mismos),
            null,
            EnumSet.of(Opcion.DESCARTAR));
        return;
      }
      int primero = mismos.getFirst();
      if (primero != fila.numero()) {
        revision
            .accion(Accion.OMITIR)
            .agregar(
                Incidencia.info(
                    "DUPLICADO_EN_CARGA",
                    "El lote " + clave + " ya viene en el renglón " + primero));
        return;
      }
    }

    // Contrato
    Contexto.Frente frente =
        contexto.frentes().stream().filter(f -> f.id() == frenteId).findFirst().orElseThrow();
    if (plantilla && fila.valor("B") != null) {
      contratoDePlantilla(fila, revision, contexto, frente);
    } else {
      contratoPorDecision(fila, revision, contexto, frente, contratoPorManzana);
    }
  }

  private static void contratoDePlantilla(
      FilaCruda fila, Revision revision, Contexto contexto, Contexto.Frente frente) {
    String texto = fila.valor("B");
    Optional<String> codigo = CodigoContrato.normalizar(texto);
    Optional<Long> alias = contexto.alias("contrato", Normal.texto(texto));
    Optional<Contexto.Contrato> contrato =
        contexto.contratos().stream()
            .filter(
                c ->
                    alias.map(a -> a == c.id()).orElse(false)
                        || codigo.map(c.codigo()::equals).orElse(false))
            .findFirst();
    if (contrato.isEmpty()) {
      revision.pedir(
          "CONTRATO_DESCONOCIDO",
          Severidad.CUARENTENA,
          "No hay un contrato \"" + texto + "\"",
          Comun.sugerencia("contrato", Normal.texto(texto), null, null, null),
          EnumSet.of(Opcion.ELEGIR, Opcion.DESCARTAR));
      return;
    }
    if (!Objects.equals(contrato.get().frenteId(), frente.id())) {
      revision.pedir(
          "CONTRATO_DE_OTRO_FRENTE",
          Severidad.BLOQUEO,
          "El contrato " + contrato.get().codigo() + " no es del frente " + frente.nombre(),
          null,
          EnumSet.of(Opcion.DESCARTAR));
      return;
    }
    revision.dato("contratoId", contrato.get().id());
  }

  private static void contratoPorDecision(
      FilaCruda fila,
      Revision revision,
      Contexto contexto,
      Contexto.Frente frente,
      Map<String, Long> contratoPorManzana) {
    Long sugerido = contratoPorManzana.get(frente.id() + "|" + revision.dato("manzana"));
    Map<String, Object> sugerencia = new LinkedHashMap<>();
    sugerencia.put("frenteId", frente.id());
    sugerencia.put("manzana", revision.dato("manzana"));
    String detalle =
        "Elige el contrato del lote %s de %s".formatted(revision.dato("clave"), frente.nombre());
    if (sugerido != null) {
      Optional<Contexto.Contrato> contrato =
          contexto.contratos().stream().filter(c -> c.id() == sugerido).findFirst();
      if (contrato.isPresent()) {
        sugerencia.put("destinoId", sugerido);
        sugerencia.put("destino", contrato.get().codigo());
        detalle +=
            " (otro lote de la manzana "
                + revision.dato("manzana")
                + " es de "
                + contrato.get().codigo()
                + ")";
      }
    }
    Optional<Decision> decision =
        revision.pedir(
            "LOTE_SIN_CONTRATO",
            Severidad.CUARENTENA,
            detalle,
            sugerencia,
            sugerencia.containsKey("destinoId")
                ? EnumSet.of(Opcion.ACEPTAR, Opcion.ELEGIR, Opcion.DESCARTAR)
                : EnumSet.of(Opcion.ELEGIR, Opcion.DESCARTAR));
    if (decision.isEmpty() || decision.get().destinoId() == null) {
      return;
    }
    Optional<Contexto.Contrato> elegido =
        contexto.contratos().stream().filter(c -> c.id() == decision.get().destinoId()).findFirst();
    if (elegido.isEmpty() || !Objects.equals(elegido.get().frenteId(), frente.id())) {
      revision.pedir(
          "CONTRATO_ELEGIDO_DE_OTRO_FRENTE",
          Severidad.CUARENTENA,
          "El contrato elegido no es del frente " + frente.nombre() + ": elige otro",
          null,
          EnumSet.of(Opcion.DESCARTAR));
      return;
    }
    revision.dato("contratoId", elegido.get().id());
  }

  /** "65", "T-65", "p65", "Prototipo 65" → "P-65"; {@code null} si no se entiende. */
  static String clavePrototipo(String texto) {
    String normal = Normal.texto(texto);
    if (normal == null) {
      return null;
    }
    Matcher m = PROTOTIPO.matcher(normal);
    return m.matches() ? "P-" + m.group(1) : null;
  }

  // ---------------------------------------------------------------------------------- aplicar

  @Override
  public void aplicar(List<FilaValidada> filas, Contexto contexto, Destino destino) {
    Map<String, Long> prototiposNuevos = new HashMap<>();
    for (FilaValidada fila : filas) {
      if (fila.accion() != Accion.CREAR) {
        continue;
      }
      Long prototipo = fila.id("prototipoId");
      String nuevo = fila.dato("prototipoClave");
      if (prototipo == null && nuevo != null) {
        prototipo =
            prototiposNuevos.computeIfAbsent(
                nuevo, c -> destino.crearPrototipo(c, "Prototipo " + c.substring(2)));
      }
      destino.crearUnidad(
          fila.id("contratoId"),
          fila.id("frenteId"),
          fila.dato("clave"),
          fila.dato("manzana"),
          fila.dato("lote"),
          prototipo);
    }
  }
}

package mx.constructora.importacion.dominio;

import static mx.constructora.importacion.dominio.Comun.limpio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import mx.constructora.estructura.ClaveUnidad;
import mx.constructora.importacion.dominio.Comun.Empate;
import mx.constructora.importacion.dominio.Modelo.Decision;
import mx.constructora.importacion.dominio.Modelo.Decisiones;
import mx.constructora.importacion.dominio.Modelo.FilaCruda;
import mx.constructora.importacion.dominio.Modelo.Incidencia;
import mx.constructora.importacion.dominio.Modelo.Opcion;
import mx.constructora.importacion.dominio.Modelo.Revision;
import mx.constructora.importacion.dominio.Modelo.Severidad;

/**
 * Cómo los importadores del histórico (H5) ligan lo que dice un renglón del Excel con los
 * catálogos: material, persona, frente, lote, paquete, razón y proveedor; y cómo leen un número que
 * se capturó como fórmula simple. Lo que se liga por nombre deja un alias al decidirse, así que una
 * decisión resuelve todos los renglones que escriben igual.
 */
final class Resolutor {

  /** Lo que se escribe en la cuarentena para cargar una salida al frente sin lote. */
  static final String SIN_ASIGNAR = "SIN ASIGNAR";

  private static final Pattern VARIAS = Pattern.compile(".*(,|;|\\sY\\s|/).*");
  private static final Pattern GUION = Pattern.compile("^[-–—_.\\s]*$");

  private Resolutor() {}

  // ------------------------------------------------------------------------------- números

  /** Guarda en el renglón crudo lo capturado o, si es fórmula, su texto con la llave "col=". */
  static void leerNumero(
      Map<String, String> crudo, String columna, String capturado, String formula) {
    if (capturado != null) {
      crudo.put(columna, capturado);
    } else if (formula != null) {
      crudo.put(columna + "=", formula);
    }
  }

  /**
   * El número de la columna: capturado, o una fórmula de aritmética simple ({@code =1/3}). Una
   * fórmula con referencias va a la cuarentena (se puede escribir el valor). Vacío si la celda está
   * vacía o en cuarentena.
   */
  static Optional<BigDecimal> numero(
      FilaCruda fila, String columna, String nombre, Revision revision) {
    String capturado = fila.valor(columna);
    if (capturado != null) {
      Optional<BigDecimal> valor = Comun.decimal(capturado);
      if (valor.isEmpty()) {
        Optional<Decision> decision =
            revision.pedir(
                "NUMERO_ILEGIBLE_" + columna,
                Severidad.CUARENTENA,
                "%s \"%s\" no es un número: escribe el valor o descarta el renglón"
                    .formatted(nombre, capturado),
                null,
                EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
        return decision.flatMap(d -> Comun.decimal(d.valor()));
      }
      return valor;
    }
    String formula = fila.valor(columna + "=");
    if (formula == null) {
      return Optional.empty();
    }
    Optional<BigDecimal> evaluada = Aritmetica.evaluar(formula);
    if (evaluada.isPresent()) {
      revision.agregar(
          Incidencia.correccion(
              "FORMULA_EVALUADA",
              "%s =%s es aritmética simple: se toma %s"
                  .formatted(
                      nombre, formula.replaceAll("\\s+", ""), evaluada.get().toPlainString())));
      return evaluada;
    }
    Optional<Decision> decision =
        revision.pedir(
            "FORMULA_CON_REFERENCIAS",
            Severidad.CUARENTENA,
            "%s es la fórmula =%s, que depende de otras celdas: escribe el valor o descarta el renglón"
                .formatted(nombre, formula),
            null,
            EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
    return decision.flatMap(d -> Comun.decimal(d.valor()));
  }

  /** El número sin incidencias, para conciliar: lo capturado o la fórmula simple. */
  static BigDecimal numeroExcel(FilaCruda fila, String columna) {
    String capturado = fila.valor(columna);
    if (capturado != null) {
      return Comun.decimal(capturado).orElse(null);
    }
    return Aritmetica.evaluar(fila.valor(columna + "=")).orElse(null);
  }

  /** Reparte {@code total} en {@code partes} iguales a 8 decimales; el residuo va a la última. */
  static List<BigDecimal> repartir(BigDecimal total, int partes) {
    List<BigDecimal> lista = new ArrayList<>();
    BigDecimal parte =
        total.divide(BigDecimal.valueOf(partes), Aritmetica.ESCALA, RoundingMode.DOWN);
    BigDecimal acumulado = BigDecimal.ZERO;
    for (int i = 0; i < partes - 1; i++) {
      lista.add(parte);
      acumulado = acumulado.add(parte);
    }
    lista.add(total.subtract(acumulado));
    return lista;
  }

  // ------------------------------------------------------------------------------- material

  /** El material del renglón, o {@code null} si queda en cuarentena. */
  static Contexto.Material material(String texto, Revision revision, Contexto contexto) {
    String normal = Normal.texto(texto);
    if (normal == null) {
      revision.pedir(
          "SIN_MATERIAL",
          Severidad.CUARENTENA,
          "El renglón no dice qué material",
          null,
          EnumSet.of(Opcion.DESCARTAR));
      return null;
    }
    Empate<Contexto.Material> empate =
        Comun.empatar(
            "material",
            normal,
            contexto,
            contexto.materiales(),
            Contexto.Material::id,
            Contexto.Material::nombreNormal);
    if (empate.exacto() != null) {
      return empate.exacto();
    }
    Map<String, Object> sugerencia = Comun.sugerencia("material", normal, null, null, null);
    String detalle = "\"" + limpio(texto) + "\" no está en el catálogo";
    if (empate.parecido() != null) {
      sugerencia =
          Comun.sugerencia(
              "material",
              normal,
              empate.parecido().id(),
              empate.parecido().clave() + " " + empate.parecido().nombre(),
              empate.puntos());
      detalle += ": se parece a " + empate.parecido().nombre();
    }
    revision.pedir(
        "MATERIAL_DESCONOCIDO",
        Severidad.CUARENTENA,
        detalle + ". Lígalo a un material (vale para todos los renglones que lo escriben igual)",
        sugerencia,
        empate.parecido() != null
            ? EnumSet.of(Opcion.ACEPTAR, Opcion.ELEGIR, Opcion.DESCARTAR)
            : EnumSet.of(Opcion.ELEGIR, Opcion.DESCARTAR));
    return null;
  }

  /** El material si se reconoce; si no, una advertencia que se puede resolver con un alias. */
  static Contexto.Material materialOpcional(String texto, Revision revision, Contexto contexto) {
    String normal = Normal.texto(texto);
    if (normal == null) {
      return null;
    }
    Empate<Contexto.Material> empate =
        Comun.empatar(
            "material",
            normal,
            contexto,
            contexto.materiales(),
            Contexto.Material::id,
            Contexto.Material::nombreNormal);
    if (empate.exacto() != null) {
      return empate.exacto();
    }
    revision.pedir(
        "SIN_MATERIAL_EN_CATALOGO",
        Severidad.ADVERTENCIA,
        "\"%s\" no es un material del catálogo: se guarda solo el nombre (puedes ligarlo)"
            .formatted(limpio(texto)),
        empate.parecido() == null
            ? Comun.sugerencia("material", normal, null, null, null)
            : Comun.sugerencia(
                "material",
                normal,
                empate.parecido().id(),
                empate.parecido().clave() + " " + empate.parecido().nombre(),
                empate.puntos()),
        empate.parecido() == null
            ? EnumSet.of(Opcion.ELEGIR)
            : EnumSet.of(Opcion.ACEPTAR, Opcion.ELEGIR));
    return null;
  }

  // -------------------------------------------------------------------------------- personas

  /** Cómo se escribe un nombre en la hoja. */
  enum Escritura {
    /** Apodo o nombre corto (almacén, destajo): se limpia igual que en H3 ("OSCAR S(1,2)"). */
    APODO,
    /** Nombre completo (SUA). */
    COMPLETO
  }

  /** La llave con la que H3 guardó el alias de la persona. */
  static String llavePersona(String texto, Escritura escritura) {
    if (escritura == Escritura.COMPLETO) {
      return Normal.texto(texto);
    }
    String llave = ImportadorPersonas.limpiarApodo(texto);
    return llave == null ? Normal.texto(texto) : llave;
  }

  /** Las partes de "Pepe y Mari" (apodos); un nombre completo es una sola persona. */
  static List<String> partes(String texto, Escritura escritura) {
    if (escritura == Escritura.COMPLETO) {
      return List.of(texto);
    }
    return ImportadorPersonas.separar(texto);
  }

  /** La persona por alias o nombre igual, sin incidencias; {@code null} si no la hay. */
  static Long personaSilenciosa(String texto, Escritura escritura, Contexto contexto) {
    String limpio = limpio(texto);
    if (limpio == null) {
      return null;
    }
    List<String> partes = partes(limpio, escritura);
    String llave = llavePersona(partes.getFirst(), escritura);
    if (llave == null) {
      return null;
    }
    Empate<Contexto.Persona> empate =
        Comun.empatar(
            "persona",
            llave,
            contexto,
            contexto.personas(),
            Contexto.Persona::id,
            Contexto.Persona::nombreNormal);
    return empate.exacto() == null ? null : empate.exacto().id();
  }

  /**
   * Las personas que nombra la celda, en orden. Cada parte que no se reconoce pide una decisión
   * (cuarentena si {@code obligatoria}, advertencia si no); al aceptarla o elegir se guarda el
   * alias. {@code null} si alguna parte obligatoria quedó pendiente.
   */
  static List<Long> personas(
      String texto,
      Escritura escritura,
      boolean obligatoria,
      String regla,
      Revision revision,
      Contexto contexto) {
    String limpio = limpio(texto);
    if (limpio == null) {
      return List.of();
    }
    List<String> partes = partes(limpio, escritura);
    if (partes.size() > 1) {
      revision.agregar(
          Incidencia.correccion(
              "VARIAS_PERSONAS", "\"" + limpio + "\" son " + partes.size() + " personas"));
    }
    List<Long> ids = new ArrayList<>();
    boolean pendiente = false;
    int n = 0;
    for (String parte : partes) {
      String llave = llavePersona(parte, escritura);
      String reglaParte = n == 0 ? regla : regla + "_" + (n + 1);
      n++;
      if (llave == null || llave.matches("^[^A-Z]*$") || llave.matches(".*[0-9].*")) {
        Optional<Decision> decision =
            revision.pedir(
                obligatoria ? "NO_ES_PERSONA" : "NO_ES_PERSONA_ADVERTENCIA",
                obligatoria ? Severidad.CUARENTENA : Severidad.ADVERTENCIA,
                "\"%s\" no es un nombre (¿un monto escrito en la columna?): elige quién es%s"
                    .formatted(parte, obligatoria ? " o descarta el renglón" : ""),
                Comun.sugerencia("persona", null, null, null, null),
                obligatoria
                    ? EnumSet.of(Opcion.ELEGIR, Opcion.DESCARTAR)
                    : EnumSet.of(Opcion.ELEGIR));
        if (decision.isPresent() && decision.get().destinoId() != null) {
          ids.add(decision.get().destinoId());
        } else if (obligatoria) {
          pendiente = true;
        }
        continue;
      }
      Empate<Contexto.Persona> empate =
          Comun.empatar(
              "persona",
              llave,
              contexto,
              contexto.personas(),
              Contexto.Persona::id,
              Contexto.Persona::nombreNormal);
      if (empate.exacto() != null) {
        ids.add(empate.exacto().id());
        continue;
      }
      Contexto.Persona sugerida = null;
      if (escritura == Escritura.APODO) {
        List<Contexto.Persona> candidatas =
            ImportadorPersonas.candidatas(llave, contexto.personas());
        if (candidatas.size() == 1) {
          sugerida = candidatas.getFirst();
        }
      } else if (empate.parecido() != null) {
        sugerida = empate.parecido();
      }
      Map<String, Object> sugerencia =
          sugerida == null
              ? Comun.sugerencia("persona", llave, null, null, null)
              : Comun.sugerencia("persona", llave, sugerida.id(), sugerida.nombre(), null);
      revision.pedir(
          reglaParte,
          obligatoria ? Severidad.CUARENTENA : Severidad.ADVERTENCIA,
          "\"%s\" no está ligado a ninguna persona%s. Elige quién es (vale para todos los"
                  .formatted(parte, sugerida == null ? "" : ": parece " + sugerida.nombre())
              + " renglones que lo escriben igual)",
          sugerencia,
          sugerida == null
              ? (obligatoria
                  ? EnumSet.of(Opcion.ELEGIR, Opcion.DESCARTAR)
                  : EnumSet.of(Opcion.ELEGIR))
              : (obligatoria
                  ? EnumSet.of(Opcion.ACEPTAR, Opcion.ELEGIR, Opcion.DESCARTAR)
                  : EnumSet.of(Opcion.ACEPTAR, Opcion.ELEGIR)));
      if (obligatoria) {
        pendiente = true;
      }
    }
    return pendiente ? null : ids;
  }

  // --------------------------------------------------------------------------------- frentes

  /** El frente por alias, nombre o clave iguales, sin incidencias. */
  static Contexto.Frente frenteExacto(String texto, Contexto contexto) {
    String normal = Normal.texto(texto);
    if (normal == null) {
      return null;
    }
    Optional<Long> alias = contexto.alias("frente", normal);
    String clave = Normal.clave(normal, 12);
    for (Contexto.Frente frente : contexto.frentes()) {
      if (alias.map(a -> a == frente.id()).orElse(false)
          || normal.equals(frente.nombreNormal())
          || frente.clave().equals(clave)) {
        return frente;
      }
    }
    return null;
  }

  /**
   * {@code true} si el texto es el propio almacén ("Almacén ") y no hay un frente con ese nombre.
   */
  static boolean esAlmacen(String texto, Contexto contexto) {
    return "ALMACEN".equals(Normal.texto(texto)) && frenteExacto(texto, contexto) == null;
  }

  // ----------------------------------------------------------------------------------- lotes

  /** A dónde va un renglón: un lote, varios (repartido) o el frente sin lote. */
  record Destino(
      Contexto.Frente frente,
      Long contratoId,
      List<Contexto.Unidad> unidades,
      boolean sinAsignar) {}

  /**
   * Los lotes del renglón en el frente. Sin lote (o "-") va al frente "Sin asignar", con el
   * contrato si toda la manzana es de uno. "4, 5, 6 … 12" o "32-43" se expande. Lote inexistente o
   * de otro frente, o varias manzanas en una celda, van a la cuarentena. {@code null} si queda
   * pendiente.
   */
  static Destino lotes(
      Contexto.Frente frente, String manzana, String lote, Revision revision, Contexto contexto) {
    String mza = limpio(manzana);
    String lt = limpio(lote);
    boolean sinLote = lt == null || GUION.matcher(lt).matches();
    boolean sinManzana = mza == null || GUION.matcher(mza).matches();
    String escrito = "Mza " + (mza == null ? "—" : mza) + " / Lote " + (lt == null ? "—" : lt);

    if (!sinManzana && VARIAS.matcher(Normal.texto(mza)).matches()) {
      Optional<Decision> decision =
          revision.pedir(
              "MANZANAS_VARIAS",
              Severidad.CUARENTENA,
              "%s: varias manzanas en una celda, no se adivina el reparto. Escribe el lote (M16L34)"
                      .formatted(escrito)
                  + " o \""
                  + SIN_ASIGNAR
                  + "\" para cargarlo al frente",
              null,
              EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
      return decision.map(d -> porValor(d.valor(), frente, revision, contexto)).orElse(null);
    }
    if (sinLote) {
      Long contrato = sinManzana ? null : contratoDeManzana(frente, mza, contexto);
      revision.agregar(
          Incidencia.correccion(
              "SIN_LOTE",
              "%s: se carga al frente %s sin lote (\"Sin asignar\")%s"
                  .formatted(
                      escrito,
                      frente.nombre(),
                      contrato == null ? "" : ", con el contrato de la manzana " + mza)));
      return new Destino(frente, contrato, List.of(), true);
    }
    if (sinManzana) {
      Optional<Decision> decision =
          revision.pedir(
              "LOTE_SIN_MANZANA",
              Severidad.CUARENTENA,
              "%s: falta la manzana. Escribe el lote (M16L34) o \"%s\""
                  .formatted(escrito, SIN_ASIGNAR),
              null,
              EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
      return decision.map(d -> porValor(d.valor(), frente, revision, contexto)).orElse(null);
    }
    List<String> numeros;
    try {
      numeros = ClaveUnidad.expandirLotes(lt);
    } catch (IllegalArgumentException e) {
      Optional<Decision> decision =
          revision.pedir(
              "LOTE_ILEGIBLE",
              Severidad.CUARENTENA,
              "%s: %s. Escribe el lote (M16L34) o \"%s\""
                  .formatted(escrito, e.getMessage(), SIN_ASIGNAR),
              null,
              EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
      return decision.map(d -> porValor(d.valor(), frente, revision, contexto)).orElse(null);
    }
    List<Contexto.Unidad> unidades = new ArrayList<>();
    List<String> faltan = new ArrayList<>();
    for (String numero : numeros) {
      String clave;
      try {
        clave = new ClaveUnidad(mza, numero).clave();
      } catch (IllegalArgumentException e) {
        faltan.add("M" + mza + "-L" + numero);
        continue;
      }
      Optional<Contexto.Unidad> unidad = unidad(frente.id(), clave, contexto);
      if (unidad.isPresent()) {
        unidades.add(unidad.get());
      } else {
        faltan.add(clave);
      }
    }
    if (faltan.isEmpty()) {
      if (unidades.size() > 1) {
        revision.agregar(
            Incidencia.correccion(
                "LOTES_EXPANDIDOS",
                "\"%s\" son %d lotes: la cantidad se reparte por igual"
                    .formatted(lt, unidades.size())));
      }
      Set<Long> contratos = new LinkedHashSet<>();
      unidades.forEach(u -> contratos.add(u.contratoId()));
      if (contratos.size() > 1) {
        revision.agregar(
            Incidencia.info(
                "LOTES_DE_VARIOS_CONTRATOS", "Los lotes repartidos son de varios contratos"));
      }
      return new Destino(frente, unidades.getFirst().contratoId(), unidades, false);
    }
    return loteInexistente(frente, escrito, faltan, numeros.size(), revision, contexto);
  }

  private static Destino loteInexistente(
      Contexto.Frente frente,
      String escrito,
      List<String> faltan,
      int total,
      Revision revision,
      Contexto contexto) {
    Map<String, Object> sugerencia = new LinkedHashMap<>();
    sugerencia.put("entidad", "unidad_obra");
    sugerencia.put("frenteId", frente.id());
    String detalle;
    Set<Opcion> opciones = EnumSet.of(Opcion.ELEGIR, Opcion.VALOR, Opcion.DESCARTAR);
    if (total == 1) {
      String clave = faltan.getFirst();
      List<Contexto.Unidad> enOtros =
          contexto.unidades().stream()
              .filter(u -> u.clave().equals(clave) && !Objects.equals(u.frenteId(), frente.id()))
              .toList();
      Contexto.Unidad sugerida = null;
      if (enOtros.size() == 1) {
        sugerida = enOtros.getFirst();
        String otro = nombreFrente(sugerida.frenteId(), contexto);
        detalle =
            "%s: el lote %s no es de %s; existe en %s"
                .formatted(escrito, clave, frente.nombre(), otro);
      } else {
        sugerida = parecido(frente.id(), clave, contexto);
        detalle =
            "%s: el lote %s no está en %s%s"
                .formatted(
                    escrito,
                    clave,
                    frente.nombre(),
                    sugerida == null ? "" : "; el más parecido es " + sugerida.clave());
      }
      if (sugerida != null) {
        sugerencia.put("destinoId", sugerida.id());
        sugerencia.put(
            "destino", sugerida.clave() + " · " + nombreFrente(sugerida.frenteId(), contexto));
        opciones = EnumSet.of(Opcion.ACEPTAR, Opcion.ELEGIR, Opcion.VALOR, Opcion.DESCARTAR);
      }
    } else {
      detalle =
          "%s: no están en %s los lotes %s"
              .formatted(escrito, frente.nombre(), String.join(", ", faltan));
      opciones = EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR);
      sugerencia.remove("entidad");
    }
    Optional<Decision> decision =
        revision.pedir(
            "LOTE_INEXISTENTE",
            Severidad.CUARENTENA,
            detalle + ". Elige el lote, escribe uno (M16L34) o \"" + SIN_ASIGNAR + "\"",
            sugerencia,
            opciones);
    if (decision.isEmpty()) {
      return null;
    }
    if (decision.get().destinoId() != null) {
      Optional<Contexto.Unidad> elegida =
          contexto.unidades().stream()
              .filter(u -> u.id() == decision.get().destinoId())
              .findFirst();
      if (elegida.isEmpty()) {
        revision.pedir(
            "LOTE_ELEGIDO_INEXISTENTE",
            Severidad.CUARENTENA,
            "El lote elegido ya no existe: elige otro",
            null,
            EnumSet.of(Opcion.DESCARTAR));
        return null;
      }
      return destinoDeUnidad(elegida.get(), frente, contexto);
    }
    return porValor(decision.get().valor(), frente, revision, contexto);
  }

  /** "SIN ASIGNAR" o un lote escrito en la cuarentena (en el frente, o en otro si es único). */
  private static Destino porValor(
      String valor, Contexto.Frente frente, Revision revision, Contexto contexto) {
    if (SIN_ASIGNAR.equals(Normal.texto(valor))) {
      return new Destino(frente, null, List.of(), true);
    }
    Optional<ClaveUnidad> clave = ClaveUnidad.leer(valor);
    Optional<Contexto.Unidad> unidad = clave.flatMap(c -> unidad(frente.id(), c.clave(), contexto));
    if (unidad.isEmpty() && clave.isPresent()) {
      List<Contexto.Unidad> otras =
          contexto.unidades().stream().filter(u -> u.clave().equals(clave.get().clave())).toList();
      if (otras.size() == 1) {
        unidad = Optional.of(otras.getFirst());
      }
    }
    if (unidad.isEmpty()) {
      revision.pedir(
          "LOTE_CORREGIDO_INEXISTENTE",
          Severidad.CUARENTENA,
          "\"%s\" no es un lote del sistema: escribe uno como M16L34 o \"%s\""
              .formatted(valor, SIN_ASIGNAR),
          null,
          EnumSet.of(Opcion.DESCARTAR));
      return null;
    }
    return destinoDeUnidad(unidad.get(), frente, contexto);
  }

  private static Destino destinoDeUnidad(
      Contexto.Unidad unidad, Contexto.Frente frente, Contexto contexto) {
    Contexto.Frente suyo =
        contexto.frentes().stream()
            .filter(f -> Objects.equals(f.id(), unidad.frenteId()))
            .findFirst()
            .orElse(frente);
    return new Destino(suyo, unidad.contratoId(), List.of(unidad), false);
  }

  static Optional<Contexto.Unidad> unidad(long frenteId, String clave, Contexto contexto) {
    return contexto.unidades().stream()
        .filter(u -> Objects.equals(u.frenteId(), frenteId) && u.clave().equals(clave))
        .findFirst();
  }

  /** El contrato de la manzana, si todos sus lotes en el frente son de uno solo. */
  static Long contratoDeManzana(Contexto.Frente frente, String manzana, Contexto contexto) {
    String mza;
    try {
      mza = ClaveUnidad.numero(manzana);
    } catch (IllegalArgumentException e) {
      return null;
    }
    Set<Long> contratos = new LinkedHashSet<>();
    for (Contexto.Unidad u : contexto.unidades()) {
      if (Objects.equals(u.frenteId(), frente.id()) && u.clave().startsWith("M" + mza + "-L")) {
        contratos.add(u.contratoId());
      }
    }
    return contratos.size() == 1 ? contratos.iterator().next() : null;
  }

  /**
   * El mismo lote en otra manzana del frente si es único; si no, el lote más cercano en la manzana.
   */
  private static Contexto.Unidad parecido(long frenteId, String clave, Contexto contexto) {
    String[] partes = clave.substring(1).split("-L");
    if (partes.length != 2) {
      return null;
    }
    String manzana = partes[0];
    String lote = partes[1];
    List<Contexto.Unidad> mismoLote =
        contexto.unidades().stream()
            .filter(u -> Objects.equals(u.frenteId(), frenteId) && u.clave().endsWith("-L" + lote))
            .toList();
    if (mismoLote.size() == 1) {
      return mismoLote.getFirst();
    }
    Integer buscado = entero(lote);
    if (buscado == null) {
      return null;
    }
    Contexto.Unidad mejor = null;
    int distancia = Integer.MAX_VALUE;
    for (Contexto.Unidad u : contexto.unidades()) {
      if (!Objects.equals(u.frenteId(), frenteId) || !u.clave().startsWith("M" + manzana + "-L")) {
        continue;
      }
      Integer suyo = entero(u.clave().substring(u.clave().indexOf("-L") + 2));
      if (suyo != null && Math.abs(suyo - buscado) < distancia) {
        distancia = Math.abs(suyo - buscado);
        mejor = u;
      }
    }
    return distancia <= 2 ? mejor : null;
  }

  private static Integer entero(String texto) {
    try {
      return Integer.parseInt(texto.replaceAll("[A-Z]$", ""));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String nombreFrente(Long id, Contexto contexto) {
    return contexto.frentes().stream()
        .filter(f -> Objects.equals(f.id(), id))
        .map(Contexto.Frente::nombre)
        .findFirst()
        .orElse("otro frente");
  }

  // -------------------------------------------------------------------------------- paquetes

  /**
   * El paquete propio que nombra la paquetería ("ACABADO EN MUROS INTERIORES"). Si no se reconoce,
   * entra sin paquete con una advertencia; aceptar o elegir guarda el alias para todos los
   * renglones que lo escriben igual.
   */
  static Long paquete(String texto, Revision revision, Contexto contexto) {
    String normal = Normal.texto(texto);
    if (normal == null) {
      return null;
    }
    List<Contexto.Paquete> propios =
        contexto.paquetes().stream().filter(p -> p.clienteId() == null).toList();
    Empate<Contexto.Paquete> empate =
        Comun.empatar(
            "paquete",
            normal,
            contexto,
            propios,
            Contexto.Paquete::id,
            Contexto.Paquete::nombreNormal);
    if (empate.exacto() != null) {
      return empate.exacto().id();
    }
    Map<String, Object> sugerencia =
        empate.parecido() == null
            ? Comun.sugerencia("paquete", normal, null, null, null)
            : Comun.sugerencia(
                "paquete",
                normal,
                empate.parecido().id(),
                empate.parecido().clave() + " " + empate.parecido().nombreNormal(),
                empate.puntos());
    revision.pedir(
        "PAQUETE_SIN_LIGAR",
        Severidad.ADVERTENCIA,
        "La paquetería \"%s\" no es un paquete del catálogo: entra sin paquete (puedes ligarla)"
            .formatted(limpio(texto)),
        sugerencia,
        empate.parecido() == null
            ? EnumSet.of(Opcion.ELEGIR)
            : EnumSet.of(Opcion.ACEPTAR, Opcion.ELEGIR));
    return null;
  }

  // ------------------------------------------------------------------------- razón y proveedor

  /** La clasificación de costo de la "Razón" (Programa, Garantía Ruba…). */
  static Integer clasificacion(String texto, Revision revision, Contexto contexto) {
    String normal = Normal.texto(texto);
    if (normal == null) {
      revision.agregar(
          new Incidencia(
              "SIN_RAZON",
              Severidad.ADVERTENCIA,
              "La salida no dice la razón: entra sin clasificación de costo",
              false,
              null,
              Set.of(),
              null));
      return null;
    }
    for (Contexto.Clasificacion c : contexto.clasificaciones()) {
      if (normal.equals(Normal.texto(c.nombre())) || normal.equals(c.clave())) {
        return c.id();
      }
    }
    revision.agregar(
        new Incidencia(
            "RAZON_DESCONOCIDA",
            Severidad.ADVERTENCIA,
            "\"" + limpio(texto) + "\" no es una razón conocida: entra sin clasificación de costo",
            false,
            null,
            Set.of(),
            null));
    return null;
  }

  /** El proveedor por alias o nombre; si no se reconoce, a la cuarentena con el más parecido. */
  static Contexto.Proveedor proveedor(String texto, Revision revision, Contexto contexto) {
    String normal = Normal.texto(texto);
    Empate<Contexto.Proveedor> empate =
        Comun.empatar(
            "proveedor",
            normal,
            contexto,
            contexto.proveedores(),
            Contexto.Proveedor::id,
            Contexto.Proveedor::nombreNormal);
    if (empate.exacto() != null) {
      return empate.exacto();
    }
    revision.pedir(
        "PROVEEDOR_DESCONOCIDO",
        Severidad.CUARENTENA,
        "\"%s\" no es un proveedor del catálogo%s"
            .formatted(
                limpio(texto),
                empate.parecido() == null ? "" : ": se parece a " + empate.parecido().nombre()),
        empate.parecido() == null
            ? Comun.sugerencia("proveedor", normal, null, null, null)
            : Comun.sugerencia(
                "proveedor",
                normal,
                empate.parecido().id(),
                empate.parecido().nombre(),
                empate.puntos()),
        empate.parecido() == null
            ? EnumSet.of(Opcion.ELEGIR, Opcion.DESCARTAR)
            : EnumSet.of(Opcion.ACEPTAR, Opcion.ELEGIR, Opcion.DESCARTAR));
    return null;
  }

  // ---------------------------------------------------------------------- decisiones en grupo

  /**
   * Decisiones que valen para todo un grupo de renglones: elegir el lote de "Mza 17 / Lote 25" una
   * vez lo resuelve en todos los renglones que lo escriben igual. Para las reglas de {@code
   * reglas}, un renglón sin decisión propia toma la primera del grupo.
   */
  static Decisiones compartidas(
      Decisiones base, List<FilaCruda> filas, Map<Integer, String> grupo, Set<String> reglas) {
    Map<String, Decision> primera = new LinkedHashMap<>();
    for (FilaCruda fila : filas) {
      String g = grupo.get(fila.numero());
      if (g == null) {
        continue;
      }
      for (String regla : reglas) {
        base.de(fila.numero(), regla).ifPresent(d -> primera.putIfAbsent(g + "|" + regla, d));
      }
    }
    return (numero, regla) -> {
      Optional<Decision> propia = base.de(numero, regla);
      if (propia.isPresent() || !reglas.contains(regla)) {
        return propia;
      }
      String g = grupo.get(numero);
      Decision d = g == null ? null : primera.get(g + "|" + regla);
      return d == null
          ? Optional.empty()
          : Optional.of(
              new Decision(numero, regla, d.opcion(), d.destinoId(), d.valor(), d.motivo()));
    };
  }
}

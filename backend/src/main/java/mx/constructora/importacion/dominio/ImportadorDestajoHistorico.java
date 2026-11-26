package mx.constructora.importacion.dominio;

import static mx.constructora.importacion.dominio.Comun.limpio;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.constructora.estructura.ClaveUnidad;
import mx.constructora.historico.Registros.Lote;
import mx.constructora.historico.Registros.PagoDestajo;
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
import mx.constructora.importacion.dominio.xlsx.Celda;
import mx.constructora.importacion.dominio.xlsx.LibroXlsx;
import mx.constructora.importacion.dominio.xlsx.Renglon;

/**
 * Histórico de los pagos de destajo (issue 51). En cada hoja de pagos ("Pagos Granada P-51"), la
 * fila 5 marca por lote tres columnas (Pagos, Sem, Trabajador) y la fila 6 dice el lote ({@code
 * M56L6}). Cada celda de pago no vacía ni cero de un renglón de concepto es un renglón de la carga:
 * lote × concepto. Un cero no es pago.
 *
 * <ul>
 *   <li>{@code =3100+7232}: dos pagos parciales. Otra aritmética simple se evalúa; una fórmula con
 *       referencias va a la cuarentena.
 *   <li>"s39\ns38(2)": varias semanas; "Pepe y Mari": varios trabajadores. El monto no se reparte:
 *       se marca "monto compartido".
 *   <li>Trabajador por apodo ("Heriberto M(1,2)"): se liga por el alias que dejó H3; si no hay, a
 *       la cuarentena. Un número en la columna de trabajador también.
 *   <li>Pagado más que el importe del concepto (cantidad × P.U.): se carga con la bandera "sobre
 *       importe" para revisión, no se corrige.
 * </ul>
 */
public final class ImportadorDestajoHistorico extends ImportadorHistorico {

  private static final Pattern SEMANA = Pattern.compile("(?i)S(?:EM(?:ANA)?)?\\.?\\s*(\\d{1,2})");
  private static final Pattern SOLO_NUMERO = Pattern.compile("^\\s*(\\d{1,2})\\s*$");
  private static final Pattern NUMERO_SECCION = Pattern.compile("^\\d{1,3}$");

  /** Reglas cuya decisión vale para todos los pagos del mismo lote (columna) de la hoja. */
  private static final Set<String> REGLAS_DE_LOTE =
      Set.of("LOTE_INEXISTENTE", "LOTE_ILEGIBLE", "SIN_LOTE", "LOTE_CORREGIDO_INEXISTENTE");

  @Override
  public TipoCarga tipo() {
    return TipoCarga.HIST_DESTAJO;
  }

  // ------------------------------------------------------------------------------------ leer

  @Override
  public Lectura leer(LibroXlsx libro) {
    List<String> hojas = new ArrayList<>();
    List<FilaCruda> filas = new ArrayList<>();
    for (String hoja : libro.hojas()) {
      List<Renglon> fila5 = Comun.renglones(libro, hoja, null, 5, 6);
      Renglon r5 = fila5.stream().filter(r -> r.numero() == 5).findFirst().orElse(null);
      Renglon r6 = fila5.stream().filter(r -> r.numero() == 6).findFirst().orElse(null);
      if (r5 == null) {
        continue;
      }
      List<String> pagos = new ArrayList<>();
      for (Celda c : r5.celdas().values()) {
        if ("PAGOS".equals(Normal.texto(c.capturado()))) {
          pagos.add(c.columna());
        }
      }
      if (pagos.isEmpty()) {
        continue;
      }
      pagos.sort((a, b) -> LibroXlsx.indiceColumna(a) - LibroXlsx.indiceColumna(b));
      hojas.add(hoja);
      leerHoja(libro, hoja, pagos, r6, filas);
    }
    if (hojas.isEmpty()) {
      throw new ArchivoInvalido(
          "El libro no tiene hojas de pagos del destajo (\"Pagos\" en la fila 5)");
    }
    return new Lectura(hojas, filas);
  }

  private static void leerHoja(
      LibroXlsx libro, String hoja, List<String> pagos, Renglon r6, List<FilaCruda> filas) {
    Map<String, String[]> columnas = new LinkedHashMap<>();
    Set<String> leer = new LinkedHashSet<>(List.of("A", "B", "D", "E", "F", "G"));
    for (String pago : pagos) {
      int i = LibroXlsx.indiceColumna(pago);
      String sem = LibroXlsx.letraColumna(i + 1);
      String trabajador = LibroXlsx.letraColumna(i + 2);
      columnas.put(pago, new String[] {sem, trabajador});
      leer.addAll(List.of(pago, sem, trabajador));
    }
    String prototipo = ImportadorLotes.prototipoDeHoja(hoja);
    String[] seccion = {null};
    Map<String, Integer> ocurrencias = new HashMap<>();
    libro.leer(
        hoja,
        leer,
        r -> {
          if (r.numero() < 7) {
            return true;
          }
          String b = Normal.texto(r.capturado("B"));
          if (b != null && (b.equals("PRECIOS") || b.startsWith("TOTAL GASTADO"))) {
            return false;
          }
          String numero = limpio(r.capturado("A"));
          String propio = limpio(r.capturado("D"));
          String precio = limpio(r.capturado("G"));
          boolean esSeccion =
              numero != null
                  && NUMERO_SECCION.matcher(numero).matches()
                  && precio == null
                  && (propio != null || limpio(r.capturado("B")) != null);
          if (esSeccion) {
            seccion[0] = propio != null ? propio : limpio(r.capturado("B"));
            return true;
          }
          boolean conDatos =
              precio != null
                  || limpio(r.capturado("E")) != null
                  || limpio(r.capturado("F")) != null;
          boolean esConcepto =
              (propio != null && conDatos)
                  || precio != null
                  || (numero != null && seccion[0] != null);
          if (!esConcepto) {
            return true;
          }
          String concepto = propio;
          if (concepto != null) {
            int n = ocurrencias.merge(seccion[0] + "|" + Normal.texto(concepto), 1, Integer::sum);
            if (n > 1) {
              concepto = concepto + " (" + n + ")";
            }
          }
          for (Map.Entry<String, String[]> lote : columnas.entrySet()) {
            String col = lote.getKey();
            Celda celda = r.celda(col);
            if (celda == null) {
              continue;
            }
            String capturado = limpio(celda.capturado());
            String formula = celda.esFormula() ? celda.formula() : null;
            if (capturado == null && formula == null) {
              continue;
            }
            if (capturado != null
                && Comun.decimal(capturado).map(v -> v.signum() == 0).orElse(false)) {
              continue;
            }
            if (formula != null
                && Aritmetica.evaluar(formula).map(v -> v.signum() == 0).orElse(false)) {
              continue;
            }
            // Renglón de subtotales (sin número ni concepto, con =SUM(…)): no es un pago.
            if (numero == null
                && propio == null
                && formula != null
                && Aritmetica.evaluar(formula).isEmpty()) {
              continue;
            }
            Map<String, String> crudo = new LinkedHashMap<>();
            Celda encabezado = r6 == null ? null : r6.celda(col);
            if (encabezado != null && limpio(encabezado.capturado()) != null) {
              crudo.put("lote", limpio(encabezado.capturado()));
            }
            crudo.put("_columna", col);
            crudo.put("_celda", celda.referencia());
            Resolutor.leerNumero(crudo, "pago", capturado, formula);
            putSi(crudo, "sem", limpio(r.capturado(lote.getValue()[0])));
            putSi(crudo, "trabajador", limpio(r.capturado(lote.getValue()[1])));
            putSi(crudo, "seccion", seccion[0]);
            putSi(crudo, "concepto", concepto);
            putSi(crudo, "A", numero);
            putSi(crudo, "cantidad", limpio(r.capturado("F")));
            putSi(crudo, "precio", precio);
            putSi(crudo, "_prototipo", prototipo);
            filas.add(new FilaCruda(filas.size() + 1, hoja, r.numero(), crudo));
          }
          return true;
        });
  }

  private static void putSi(Map<String, String> mapa, String clave, String valor) {
    if (valor != null) {
      mapa.put(clave, valor);
    }
  }

  // ------------------------------------------------------------------------------- validación

  @Override
  public List<FilaValidada> validar(
      List<FilaCruda> filas, Contexto contexto, Decisiones decisiones) {
    Integer anio = anio(contexto);
    Map<Integer, String> grupos = new HashMap<>();
    for (FilaCruda fila : filas) {
      grupos.put(fila.numero(), fila.hoja() + "|" + fila.valor("_columna"));
    }
    Decisiones conGrupo = Resolutor.compartidas(decisiones, filas, grupos, REGLAS_DE_LOTE);
    List<FilaValidada> resultado = new ArrayList<>();
    for (FilaCruda fila : filas) {
      Revision revision = new Revision(fila, conGrupo);
      validarFila(fila, revision, contexto, anio);
      resultado.add(revision.cerrar());
    }
    return resultado;
  }

  private static Integer anio(Contexto contexto) {
    try {
      int anio = Integer.parseInt(contexto.parametros().getOrDefault("anio", ""));
      return anio >= 2020 && anio <= 2100 ? anio : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static void validarFila(
      FilaCruda fila, Revision revision, Contexto contexto, Integer anio) {
    if (anio == null) {
      revision.pedir(
          "SIN_ANIO",
          Severidad.BLOQUEO,
          "La carga no dice de qué año son las semanas: súbela otra vez eligiendo el año",
          null,
          EnumSet.of(Opcion.DESCARTAR));
      return;
    }

    // Monto: número, suma de pagos parciales o aritmética simple
    List<BigDecimal> parciales = montos(fila, revision);

    // Frente y lote
    Contexto.Frente frente = Frentes.resolver(fila.hoja(), revision, contexto);
    Contexto.Unidad unidad = frente == null ? null : unidad(fila, frente, revision, contexto);

    // Paquete
    Long paquete = paquete(fila, revision, contexto);

    // Semanas
    List<Integer> semanas = semanas(fila.valor("sem"), revision);

    // Trabajadores
    List<Long> trabajadores = List.of();
    if (fila.valor("trabajador") == null) {
      revision.agregar(
          new Incidencia(
              "SIN_TRABAJADOR",
              Severidad.ADVERTENCIA,
              "El pago no dice quién cobró",
              false,
              null,
              Set.of(),
              null));
    } else {
      trabajadores =
          Resolutor.personas(
              fila.valor("trabajador"),
              Escritura.APODO,
              true,
              "PERSONA_SIN_LIGAR",
              revision,
              contexto);
    }

    if (parciales == null || unidad == null || trabajadores == null || revision.pendiente()) {
      return;
    }
    BigDecimal total = parciales.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    if (total.signum() < 0) {
      revision.agregar(
          new Incidencia(
              "MONTO_NEGATIVO",
              Severidad.ADVERTENCIA,
              "Un pago negativo (" + total.toPlainString() + "): se carga como está",
              false,
              null,
              Set.of(),
              null));
    }
    BigDecimal importe = importeConcepto(fila);
    boolean sobre = importe != null && total.compareTo(importe) > 0;
    if (sobre) {
      revision.agregar(
          new Incidencia(
              "SOBRE_IMPORTE",
              Severidad.ADVERTENCIA,
              "Se pagó %s y el concepto vale %s: se carga con la bandera \"sobre importe\""
                  .formatted(total.toPlainString(), importe.toPlainString()),
              false,
              null,
              Set.of(),
              null));
    }
    boolean compartido = semanas.size() > 1 || trabajadores.size() > 1;
    if (compartido) {
      revision.agregar(
          Incidencia.correccion(
              "MONTO_COMPARTIDO",
              "Varias semanas o trabajadores en la celda: el monto no se reparte"));
    }
    revision
        .dato("anio", anio)
        .dato("frenteId", frente.id())
        .dato("contratoId", unidad.contratoId())
        .dato("unidadId", unidad.id())
        .dato("prototipoId", unidad.prototipoId())
        .dato("paqueteId", paquete)
        .dato(
            "montos", String.join(";", parciales.stream().map(BigDecimal::toPlainString).toList()))
        .dato("semanas", String.join(",", semanas.stream().map(String::valueOf).toList()))
        .dato("trabajadores", String.join(",", trabajadores.stream().map(String::valueOf).toList()))
        .dato("importe", importe == null ? null : importe.toPlainString())
        .dato("sobre", sobre ? "1" : null)
        .dato("compartido", compartido ? "1" : null);
  }

  /** Los pagos de la celda; {@code null} si queda en cuarentena. */
  private static List<BigDecimal> montos(FilaCruda fila, Revision revision) {
    String capturado = fila.valor("pago");
    if (capturado != null) {
      Optional<BigDecimal> valor = Comun.decimal(capturado);
      if (valor.isPresent()) {
        return List.of(valor.get());
      }
      Optional<Decision> decision =
          revision.pedir(
              "PAGO_ILEGIBLE",
              Severidad.CUARENTENA,
              "\"%s\" (%s) no es un monto: escribe el monto o descarta la celda"
                  .formatted(capturado, fila.valor("_celda")),
              null,
              EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
      return decision.flatMap(d -> Comun.decimal(d.valor())).map(List::of).orElse(null);
    }
    String formula = fila.valor("pago=");
    List<BigDecimal> sumandos = Aritmetica.sumandos(formula);
    if (!sumandos.isEmpty()) {
      revision.agregar(
          Incidencia.correccion(
              "PAGOS_PARCIALES",
              "=%s: %d pagos parciales"
                  .formatted(formula.replaceAll("\\s+", ""), sumandos.size())));
      return sumandos;
    }
    Optional<BigDecimal> evaluada = Aritmetica.evaluar(formula);
    if (evaluada.isPresent()) {
      revision.agregar(
          Incidencia.correccion(
              "FORMULA_EVALUADA",
              "=%s es aritmética simple: se toma %s"
                  .formatted(formula.replaceAll("\\s+", ""), evaluada.get().toPlainString())));
      return List.of(evaluada.get());
    }
    Optional<Decision> decision =
        revision.pedir(
            "FORMULA_CON_REFERENCIAS",
            Severidad.CUARENTENA,
            "El pago (%s) es la fórmula =%s, que depende de otras celdas: escribe el monto o descarta"
                    .formatted(fila.valor("_celda"), formula)
                + " la celda",
            null,
            EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
    return decision.flatMap(d -> Comun.decimal(d.valor())).map(List::of).orElse(null);
  }

  /** El lote de la columna (fila 6) en el frente de la hoja. */
  private static Contexto.Unidad unidad(
      FilaCruda fila, Contexto.Frente frente, Revision revision, Contexto contexto) {
    String texto = fila.valor("lote");
    Optional<ClaveUnidad> clave = ClaveUnidad.leer(texto);
    if (clave.isEmpty()) {
      Optional<Decision> decision =
          revision.pedir(
              texto == null ? "SIN_LOTE" : "LOTE_ILEGIBLE",
              Severidad.CUARENTENA,
              (texto == null
                          ? "La columna %s no dice el lote en la fila 6"
                          : "\"" + texto + "\" (columna %s) no es un lote como M20L10")
                      .formatted(fila.valor("_columna"))
                  + ": escribe el lote (vale para toda la columna) o descarta",
              null,
              EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
      clave = decision.flatMap(d -> ClaveUnidad.leer(d.valor()));
      if (decision.isPresent() && clave.isEmpty()) {
        revision.pedir(
            "LOTE_CORREGIDO_INEXISTENTE",
            Severidad.CUARENTENA,
            "\"" + decision.get().valor() + "\" tampoco es un lote: escribe uno como M7L16",
            null,
            EnumSet.of(Opcion.DESCARTAR));
      }
      if (clave.isEmpty()) {
        return null;
      }
    }
    Resolutor.Destino destino =
        Resolutor.lotes(frente, clave.get().manzana(), clave.get().lote(), revision, contexto);
    if (destino == null) {
      return null;
    }
    if (destino.unidades().isEmpty()) {
      revision.pedir(
          "DESTAJO_SIN_LOTE",
          Severidad.CUARENTENA,
          "Un pago de destajo siempre es de un lote: elige el lote o descarta",
          null,
          EnumSet.of(Opcion.DESCARTAR));
      return null;
    }
    return destino.unidades().getFirst();
  }

  /** El concepto (paquete propio) por sección y nombre, como lo dio de alta H3. */
  private static Long paquete(FilaCruda fila, Revision revision, Contexto contexto) {
    String seccion = Normal.texto(fila.valor("seccion"));
    String concepto = Normal.texto(fila.valor("concepto"));
    Contexto.Paquete padre =
        seccion == null
            ? null
            : contexto.paquetes().stream()
                .filter(
                    p ->
                        p.clienteId() == null
                            && p.padreId() == null
                            && seccion.equals(p.nombreNormal()))
                .findFirst()
                .orElse(null);
    if (padre != null && concepto != null) {
      Optional<Contexto.Paquete> hijo =
          contexto.paquetes().stream()
              .filter(
                  p -> Objects.equals(p.padreId(), padre.id()) && concepto.equals(p.nombreNormal()))
              .findFirst();
      if (hijo.isPresent()) {
        return hijo.get().id();
      }
    }
    if (padre != null) {
      revision.agregar(
          Incidencia.info(
              "PAQUETE_SECCION",
              "El concepto no está en el catálogo: el pago se liga a la sección " + padre.clave()));
      return padre.id();
    }
    revision.agregar(
        new Incidencia(
            "PAQUETE_SIN_LIGAR",
            Severidad.ADVERTENCIA,
            "La sección \"%s\" no está en el catálogo de paquetes: el pago entra sin paquete"
                .formatted(fila.valor("seccion")),
            false,
            null,
            Set.of(),
            null));
    return null;
  }

  /** "s39\ns38(2)" → 39, 38. Los números entre paréntesis son pagos parciales, no semanas. */
  static List<Integer> semanas(String texto, Revision revision) {
    if (texto == null) {
      revision.agregar(
          new Incidencia(
              "SIN_SEMANA",
              Severidad.ADVERTENCIA,
              "El pago no dice la semana",
              false,
              null,
              Set.of(),
              null));
      return List.of();
    }
    Set<Integer> semanas = new LinkedHashSet<>();
    String sinParentesis = texto.replaceAll("\\([^)]*\\)?", " ");
    Matcher solo = SOLO_NUMERO.matcher(sinParentesis);
    if (solo.matches()) {
      semanas.add(Integer.valueOf(solo.group(1)));
    } else {
      Matcher m = SEMANA.matcher(sinParentesis);
      while (m.find()) {
        semanas.add(Integer.valueOf(m.group(1)));
      }
    }
    semanas.removeIf(s -> s < 1 || s > 53);
    if (semanas.isEmpty()) {
      revision.agregar(
          new Incidencia(
              "SEMANA_ILEGIBLE",
              Severidad.ADVERTENCIA,
              "\"" + texto.replaceAll("\\s+", " ") + "\" no dice una semana: entra sin semana",
              false,
              null,
              Set.of(),
              null));
    } else if (semanas.size() > 1) {
      revision.agregar(
          Incidencia.correccion("VARIAS_SEMANAS", "Semanas " + semanas + " en la misma celda"));
    }
    return new ArrayList<>(semanas);
  }

  /** Cantidad × P.U. del concepto (sin cantidad: 1). */
  private static BigDecimal importeConcepto(FilaCruda fila) {
    Optional<BigDecimal> precio = Comun.decimal(fila.valor("precio"));
    if (precio.isEmpty()) {
      return null;
    }
    BigDecimal cantidad = Comun.decimal(fila.valor("cantidad")).orElse(BigDecimal.ONE);
    return Aritmetica.normal(precio.get().multiply(cantidad));
  }

  // ---------------------------------------------------------------------- registrar y conciliar

  @Override
  void registrar(FilaValidada fila, Contexto contexto, Lote lote) {
    FilaCruda c = fila.fila();
    List<Integer> semanas =
        fila.dato("semanas") == null || fila.dato("semanas").isEmpty()
            ? List.of()
            : Arrays.stream(fila.dato("semanas").split(",")).map(Integer::valueOf).toList();
    List<Long> trabajadores =
        fila.dato("trabajadores") == null || fila.dato("trabajadores").isEmpty()
            ? List.of()
            : Arrays.stream(fila.dato("trabajadores").split(",")).map(Long::valueOf).toList();
    String[] montos = fila.dato("montos").split(";");
    for (int i = 0; i < montos.length; i++) {
      BigDecimal monto = new BigDecimal(montos[i]);
      if (monto.signum() == 0) {
        continue;
      }
      lote.pagos.add(
          new PagoDestajo(
              entero(fila, "anio"),
              semanas,
              texto(c.valor("sem"), 60),
              fila.id("frenteId"),
              fila.id("contratoId"),
              fila.id("unidadId"),
              fila.id("prototipoId"),
              fila.id("paqueteId"),
              texto(c.valor("seccion"), 160),
              texto(c.valor("concepto"), 160),
              decimal(fila, "importe"),
              monto,
              i + 1,
              si(fila, "compartido"),
              si(fila, "sobre"),
              texto(c.valor("trabajador"), 160),
              trabajadores,
              c.valor("_celda"),
              traza(fila)));
    }
  }

  @Override
  void conciliar(FilaValidada fila, Lote lote, Conciliacion conciliacion) {
    FilaCruda c = fila.fila();
    BigDecimal excel = Resolutor.numeroExcel(c, "pago");
    if (excel == null && fila.dato("montos") != null) {
      excel =
          Arrays.stream(fila.dato("montos").split(";"))
              .map(BigDecimal::new)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    BigDecimal cargado = suma(lote.pagos.stream().map(PagoDestajo::monto).toList());
    conciliacion.sumar(
        "Pagado por hoja y lote",
        c.hoja()
            + " · "
            + Optional.ofNullable(c.valor("lote")).orElse("columna " + c.valor("_columna")),
        fila.estado(),
        excel,
        cargado);
    String trabajador = c.valor("trabajador");
    conciliacion.sumar(
        "Pagado por trabajador (como se escribió)",
        trabajador == null
            ? "sin trabajador"
            : Optional.ofNullable(ImportadorPersonas.limpiarApodo(trabajador))
                .orElse(Normal.texto(trabajador)),
        fila.estado(),
        excel,
        cargado);
  }
}

package mx.constructora.importacion.dominio;

import static mx.constructora.importacion.dominio.Comun.limpio;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import mx.constructora.estructura.CodigoContrato;
import mx.constructora.historico.Registros.AltaImss;
import mx.constructora.historico.Registros.Asignacion;
import mx.constructora.historico.Registros.AsistenciaDia;
import mx.constructora.historico.Registros.Lote;
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
 * Histórico del SUA de un mes (issue 50). El mes se elige al subir ({@code mes=2026-10}) y tiene
 * que coincidir con el de las hojas ("IMSS OCTUBRE"). Las columnas se buscan por su encabezado en
 * la fila 1, porque cambian de un mes a otro.
 *
 * <ul>
 *   <li>IMSS: estatus, días y contrato de cada alta. Contrato con "/" → "-".
 *   <li>SUA ANTERIOR: las altas del mes previo; el contrato viene como texto ("1 MFF 35 VIVIENDAS")
 *       y se liga por alias.
 *   <li>BD: el frente asignado para el IMSS.
 *   <li>ASISTENCIA: una marca por persona y día (1, 0.5 o 0). La misma persona en dos frentes el
 *       mismo día se carga dos veces con la bandera "doble asistencia".
 * </ul>
 *
 * Cada nombre se liga a una persona (cargada en H3); si no se reconoce, va a la cuarentena.
 */
public final class ImportadorSuaHistorico extends ImportadorHistorico {

  static final String IMSS = "IMSS";
  static final String ASISTENCIA = "ASISTENCIA";
  static final String ANTERIOR = "SUA ANTERIOR";
  static final String BD = "BD";

  private static final List<String> MESES =
      List.of(
          "ENERO",
          "FEBRERO",
          "MARZO",
          "ABRIL",
          "MAYO",
          "JUNIO",
          "JULIO",
          "AGOSTO",
          "SEPTIEMBRE",
          "OCTUBRE",
          "NOVIEMBRE",
          "DICIEMBRE");

  private static final Set<BigDecimal> MARCAS =
      Set.of(BigDecimal.ZERO, new BigDecimal("0.5"), BigDecimal.ONE);

  @Override
  public TipoCarga tipo() {
    return TipoCarga.HIST_SUA;
  }

  // ------------------------------------------------------------------------------------ leer

  @Override
  public Lectura leer(LibroXlsx libro) {
    List<String> hojas = new ArrayList<>();
    List<FilaCruda> filas = new ArrayList<>();
    for (String hoja : Comun.hojasQueEmpiezan(libro, IMSS)) {
      leerHoja(
          libro,
          hoja,
          IMSS,
          Map.of(
              "estatus", "STATUS",
              "nombre", "NOMBRE",
              "dias", "DIAS",
              "contrato", "CONTRATO",
              "obra", "NO OBRA",
              "frente", "SUA"),
          hojas,
          filas);
    }
    for (String hoja : Comun.hojasQueEmpiezan(libro, ASISTENCIA)) {
      leerHoja(
          libro,
          hoja,
          ASISTENCIA,
          Map.of("frente", "FRENTE", "nombre", "NOMBRE", "tipo", "TIPO", "ocupacion", "OCUPACION"),
          hojas,
          filas);
    }
    Comun.hoja(libro, ANTERIOR)
        .ifPresent(
            hoja ->
                leerHoja(
                    libro,
                    hoja,
                    ANTERIOR,
                    Map.of("nombre", "NOMBRE", "frente", "FRENTE", "contrato", "CONTRATO"),
                    hojas,
                    filas));
    Comun.hoja(libro, BD)
        .ifPresent(
            hoja ->
                leerHoja(
                    libro,
                    hoja,
                    BD,
                    Map.of("nombre", "NOMBRE", "frente", "FRENTE", "asignacion", "ASIGNACION"),
                    hojas,
                    filas));
    if (hojas.isEmpty()) {
      throw new ArchivoInvalido(
          "El libro no tiene las hojas del SUA (IMSS, ASISTENCIA, SUA ANTERIOR, BD)");
    }
    return new Lectura(hojas, filas);
  }

  private static void leerHoja(
      LibroXlsx libro,
      String hoja,
      String tipo,
      Map<String, String> encabezados,
      List<String> hojas,
      List<FilaCruda> filas) {
    Map<String, String> columnas = new LinkedHashMap<>();
    Map<String, Integer> dias = new LinkedHashMap<>();
    Integer mesHoja = mesDeHoja(hoja);
    libro.leer(
        hoja,
        r -> {
          if (r.numero() == 1) {
            encabezados.forEach(
                (campo, prefijo) -> {
                  String columna = columna(r, prefijo);
                  if (columna != null) {
                    columnas.put(campo, columna);
                  }
                });
            if (!columnas.containsKey("nombre")) {
              throw new ArchivoInvalido(
                  "La hoja \"" + hoja + "\" no tiene la columna NOMBRE en la fila 1");
            }
            if (ASISTENCIA.equals(tipo)) {
              for (Celda c : r.celdas().values()) {
                Optional<BigDecimal> dia = Comun.decimal(c.capturado());
                if (dia.isPresent()
                    && dia.get().scale() <= 0
                    && dia.get().intValue() >= 1
                    && dia.get().intValue() <= 31
                    && LibroXlsx.indiceColumna(c.columna())
                        > LibroXlsx.indiceColumna(columnas.get("nombre"))) {
                  dias.put(c.columna(), dia.get().intValue());
                }
              }
            }
            return true;
          }
          String nombre = limpio(r.capturado(columnas.get("nombre")));
          if (nombre == null) {
            return true;
          }
          Map<String, String> crudo = new LinkedHashMap<>();
          crudo.put("_tipo", tipo);
          if (mesHoja != null) {
            crudo.put("_mes_hoja", mesHoja.toString());
          }
          columnas.forEach(
              (campo, columna) -> {
                String v = limpio(r.capturado(columna));
                if (v != null) {
                  crudo.put(campo, v);
                }
              });
          dias.forEach(
              (columna, dia) -> {
                String v = limpio(r.capturado(columna));
                if (v != null) {
                  crudo.put("d" + dia, v);
                }
              });
          filas.add(new FilaCruda(filas.size() + 1, hoja, r.numero(), crudo));
          return true;
        });
    hojas.add(hoja);
  }

  /** La columna cuyo encabezado normalizado empieza con {@code prefijo} (la primera). */
  private static String columna(Renglon encabezado, String prefijo) {
    return encabezado.celdas().values().stream()
        .filter(c -> c.capturado() != null && Normal.palabras(c.capturado()) != null)
        .filter(c -> Normal.palabras(c.capturado()).startsWith(prefijo))
        .map(Celda::columna)
        .min((a, b) -> LibroXlsx.indiceColumna(a) - LibroXlsx.indiceColumna(b))
        .orElse(null);
  }

  /** "IMSS OCTUBRE" → 10. */
  static Integer mesDeHoja(String hoja) {
    String normal = Normal.palabras(hoja);
    if (normal == null) {
      return null;
    }
    for (String palabra : normal.split(" ")) {
      int indice = MESES.indexOf(palabra);
      if (indice >= 0) {
        return indice + 1;
      }
    }
    return null;
  }

  // ------------------------------------------------------------------------------- validación

  @Override
  public List<FilaValidada> validar(
      List<FilaCruda> filas, Contexto contexto, Decisiones decisiones) {
    YearMonth mes = mes(contexto);
    // Doble asistencia: la misma persona con marca en más de un renglón el mismo día.
    Map<String, Integer> marcas = new HashMap<>();
    for (FilaCruda fila : filas) {
      if (ASISTENCIA.equals(fila.valor("_tipo"))) {
        String persona = clavePersona(fila, contexto);
        for (int dia = 1; dia <= 31; dia++) {
          Optional<BigDecimal> valor = Comun.decimal(fila.valor("d" + dia));
          if (valor.isPresent() && valor.get().signum() > 0) {
            marcas.merge(persona + "|" + dia, 1, Integer::sum);
          }
        }
      }
    }
    List<FilaValidada> resultado = new ArrayList<>();
    for (FilaCruda fila : filas) {
      Revision revision = new Revision(fila, decisiones);
      validarFila(fila, revision, contexto, mes, marcas);
      resultado.add(revision.cerrar());
    }
    return resultado;
  }

  /**
   * La persona del renglón para detectar doble asistencia: su id si ya está ligada (así "GARZA LEAL
   * EDUARDO" y "EDUARDO GARZA LEAL" son la misma), si no, el nombre normalizado.
   */
  private static String clavePersona(FilaCruda fila, Contexto contexto) {
    Long id = Resolutor.personaSilenciosa(fila.valor("nombre"), Escritura.COMPLETO, contexto);
    return id != null ? "id:" + id : "nombre:" + Normal.texto(fila.valor("nombre"));
  }

  private static YearMonth mes(Contexto contexto) {
    String texto = contexto.parametros().get("mes");
    try {
      return texto == null ? null : YearMonth.parse(texto);
    } catch (DateTimeException e) {
      return null;
    }
  }

  private static void validarFila(
      FilaCruda fila,
      Revision revision,
      Contexto contexto,
      YearMonth mes,
      Map<String, Integer> marcas) {
    String tipo = fila.valor("_tipo");
    revision.dato("_persona", Normal.texto(fila.valor("nombre")));
    if (mes == null) {
      revision.pedir(
          "SIN_MES",
          Severidad.BLOQUEO,
          "La carga no dice de qué mes es el SUA: súbela otra vez eligiendo el mes",
          null,
          EnumSet.of(Opcion.DESCARTAR));
      return;
    }
    String mesHoja = fila.valor("_mes_hoja");
    if ((IMSS.equals(tipo) || ASISTENCIA.equals(tipo))
        && mesHoja != null
        && Integer.parseInt(mesHoja) != mes.getMonthValue()) {
      revision.pedir(
          "MES_DISTINTO",
          Severidad.BLOQUEO,
          "La hoja \"%s\" no es del mes elegido (%s): súbela con el mes correcto"
              .formatted(fila.hoja(), mes),
          null,
          EnumSet.of(Opcion.DESCARTAR));
      return;
    }
    List<Long> persona =
        Resolutor.personas(
            fila.valor("nombre"),
            Escritura.COMPLETO,
            true,
            "PERSONA_SIN_LIGAR",
            revision,
            contexto);
    switch (tipo) {
      case IMSS -> imss(fila, revision, contexto);
      case ANTERIOR -> anterior(fila, revision, contexto);
      case BD -> {
        Contexto.Frente frente = Resolutor.frenteExacto(fila.valor("asignacion"), contexto);
        if (frente == null) {
          frente = Resolutor.frenteExacto(fila.valor("frente"), contexto);
        }
        revision.dato("frenteId", frente == null ? null : frente.id());
      }
      default -> asistencia(fila, revision, contexto, mes, marcas);
    }
    if (persona != null && !persona.isEmpty()) {
      revision.dato("personaId", persona.getFirst());
    }
    revision.dato("mes", mes.atDay(1));
  }

  private static void imss(FilaCruda fila, Revision revision, Contexto contexto) {
    String estatus = Normal.texto(fila.valor("estatus"));
    if (estatus != null && !estatus.equals(limpio(fila.valor("estatus")))) {
      revision.agregar(
          Incidencia.correccion(
              "ESTATUS_NORMALIZADO", "\"" + fila.valor("estatus") + "\" → " + estatus));
    }
    revision.dato("estatus", estatus);
    Optional<BigDecimal> dias = Resolutor.numero(fila, "dias", "Los días IMSS", revision);
    dias.filter(d -> d.signum() >= 0 && d.compareTo(BigDecimal.valueOf(31)) <= 0)
        .ifPresent(d -> revision.dato("dias", d.intValue()));
    String texto = fila.valor("contrato");
    if (texto == null) {
      revision.agregar(
          new Incidencia(
              "SIN_CONTRATO",
              Severidad.ADVERTENCIA,
              "El alta no dice el contrato",
              false,
              null,
              Set.of(),
              null));
      return;
    }
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
          "No hay un contrato \""
              + texto
              + "\": elige cuál es (vale para todas las altas con ese texto)",
          Comun.sugerencia("contrato", Normal.texto(texto), null, null, null),
          EnumSet.of(Opcion.ELEGIR, Opcion.DESCARTAR));
      return;
    }
    if (codigo.isPresent() && !codigo.get().equals(limpio(texto))) {
      revision.agregar(
          Incidencia.correccion("CODIGO_NORMALIZADO", texto + " se lee como " + codigo.get()));
    }
    revision.dato("contratoId", contrato.get().id());
  }

  /** SUA ANTERIOR: el contrato viene como texto ("1 MFF 35 VIVIENDAS"): se liga por alias. */
  private static void anterior(FilaCruda fila, Revision revision, Contexto contexto) {
    String texto = fila.valor("contrato");
    if (texto == null) {
      return;
    }
    String normal = Normal.texto(texto);
    Optional<Long> alias = contexto.alias("contrato", normal);
    if (alias.isPresent() && contexto.contratos().stream().anyMatch(c -> c.id() == alias.get())) {
      revision.dato("contratoId", alias.get());
      return;
    }
    Optional<String> codigo = CodigoContrato.normalizar(texto);
    Optional<Contexto.Contrato> exacto =
        codigo.flatMap(
            k -> contexto.contratos().stream().filter(c -> c.codigo().equals(k)).findFirst());
    if (exacto.isPresent()) {
      revision.dato("contratoId", exacto.get().id());
      return;
    }
    // "1 MFF 35 VIVIENDAS" → el contrato cuyo tercer segmento es MFF (60-KBN-MFF-C001-2026).
    Set<String> palabras = new TreeSet<>(List.of(Normal.palabras(texto).split(" ")));
    List<Contexto.Contrato> candidatos =
        contexto.contratos().stream()
            .filter(
                c -> {
                  String[] segmentos = c.codigo().split("[-/]");
                  return segmentos.length > 2 && palabras.contains(segmentos[2]);
                })
            .toList();
    Contexto.Contrato sugerido = candidatos.size() == 1 ? candidatos.getFirst() : null;
    revision.pedir(
        "CONTRATO_POR_TEXTO",
        Severidad.ADVERTENCIA,
        "\"%s\" no es un código de contrato%s: el alta del mes anterior entra sin contrato (puedes"
                .formatted(texto, sugerido == null ? "" : "; parece " + sugerido.codigo())
            + " ligarlo)",
        sugerido == null
            ? Comun.sugerencia("contrato", normal, null, null, null)
            : Comun.sugerencia("contrato", normal, sugerido.id(), sugerido.codigo(), null),
        sugerido == null ? EnumSet.of(Opcion.ELEGIR) : EnumSet.of(Opcion.ACEPTAR, Opcion.ELEGIR));
  }

  private static void asistencia(
      FilaCruda fila,
      Revision revision,
      Contexto contexto,
      YearMonth mes,
      Map<String, Integer> marcas) {
    Contexto.Frente frente = null;
    if (fila.valor("frente") != null) {
      frente = Frentes.resolver(fila.valor("frente"), revision, contexto);
      if (frente == null) {
        return;
      }
      revision.dato("frenteId", frente.id());
    }
    String persona = clavePersona(fila, contexto);
    List<String> validos = new ArrayList<>();
    List<String> dobles = new ArrayList<>();
    for (int dia = 1; dia <= 31; dia++) {
      String texto = fila.valor("d" + dia);
      if (texto == null) {
        continue;
      }
      Optional<BigDecimal> valor = Comun.decimal(texto);
      if (valor.isEmpty() || MARCAS.stream().noneMatch(m -> m.compareTo(valor.get()) == 0)) {
        revision.agregar(
            new Incidencia(
                "MARCA_DESCONOCIDA_" + dia,
                Severidad.ADVERTENCIA,
                "El día %d dice \"%s\": no es 1, 0.5 ni 0 y no se carga".formatted(dia, texto),
                false,
                null,
                Set.of(),
                null));
        continue;
      }
      if (dia > mes.lengthOfMonth()) {
        revision.agregar(
            new Incidencia(
                "DIA_FUERA_DEL_MES",
                Severidad.ADVERTENCIA,
                "%s no tiene día %d: esa marca no se carga".formatted(mes, dia),
                false,
                null,
                Set.of(),
                null));
        continue;
      }
      validos.add(dia + "=" + Aritmetica.normal(valor.get()).toPlainString());
      if (valor.get().signum() > 0 && marcas.getOrDefault(persona + "|" + dia, 0) > 1) {
        dobles.add(Integer.toString(dia));
      }
    }
    revision.dato("dias", String.join(",", validos));
    if (!dobles.isEmpty()) {
      revision
          .dato("dobles", String.join(",", dobles))
          .agregar(
              new Incidencia(
                  "DOBLE_ASISTENCIA",
                  Severidad.ADVERTENCIA,
                  "Marcada también en otro frente los días "
                      + String.join(", ", dobles)
                      + ": se cargan ambas marcas",
                  false,
                  null,
                  Set.of(),
                  null));
    }
  }

  // ---------------------------------------------------------------------- registrar y conciliar

  @Override
  void registrar(FilaValidada fila, Contexto contexto, Lote lote) {
    FilaCruda c = fila.fila();
    LocalDate mes = fecha(fila, "mes");
    long persona = fila.id("personaId");
    switch (c.valor("_tipo")) {
      case IMSS ->
          lote.altas.add(
              new AltaImss(
                  mes,
                  "mes",
                  persona,
                  texto(fila.dato("estatus"), 20),
                  entero(fila, "dias"),
                  texto(c.valor("frente"), 80),
                  fila.id("contratoId"),
                  texto(c.valor("contrato"), 80),
                  texto(c.valor("obra"), 40),
                  traza(fila)));
      case ANTERIOR ->
          lote.altas.add(
              new AltaImss(
                  mes.minusMonths(1),
                  "anterior",
                  persona,
                  null,
                  null,
                  texto(c.valor("frente"), 80),
                  fila.id("contratoId"),
                  texto(c.valor("contrato"), 80),
                  null,
                  traza(fila)));
      case BD ->
          lote.asignaciones.add(
              new Asignacion(
                  mes,
                  persona,
                  fila.id("frenteId"),
                  texto(c.valor("frente"), 80),
                  texto(c.valor("asignacion"), 80),
                  traza(fila)));
      default -> {
        String dias = fila.dato("dias");
        if (dias == null || dias.isEmpty()) {
          return;
        }
        Set<String> dobles =
            fila.dato("dobles") == null ? Set.of() : Set.of(fila.dato("dobles").split(","));
        for (String marca : dias.split(",")) {
          String[] partes = marca.split("=");
          lote.asistencias.add(
              new AsistenciaDia(
                  mes.withDayOfMonth(Integer.parseInt(partes[0])),
                  persona,
                  fila.id("frenteId"),
                  texto(c.valor("frente"), 80),
                  texto(c.valor("tipo"), 40),
                  texto(c.valor("ocupacion"), 120),
                  new BigDecimal(partes[1]),
                  dobles.contains(partes[0]),
                  traza(fila)));
        }
      }
    }
  }

  @Override
  void conciliar(FilaValidada fila, Lote lote, Conciliacion conciliacion) {
    FilaCruda c = fila.fila();
    String tipo = c.valor("_tipo");
    if (IMSS.equals(tipo)) {
      String contrato = c.valor("contrato");
      conciliacion.sumar(
          "Altas IMSS por contrato",
          contrato == null
              ? "sin contrato"
              : CodigoContrato.normalizar(contrato).orElse(Normal.texto(contrato)),
          fila.estado(),
          BigDecimal.ONE,
          BigDecimal.valueOf(lote.altas.size()));
    } else if (ASISTENCIA.equals(tipo)) {
      BigDecimal excel = BigDecimal.ZERO;
      for (int dia = 1; dia <= 31; dia++) {
        excel = excel.add(Comun.decimal(c.valor("d" + dia)).orElse(BigDecimal.ZERO));
      }
      conciliacion.sumar(
          "Marcas de asistencia por persona y frente",
          fila.dato("_persona")
              + " · "
              + Optional.ofNullable(Normal.texto(c.valor("frente"))).orElse("—"),
          fila.estado(),
          excel,
          suma(lote.asistencias.stream().map(AsistenciaDia::valor).toList()));
    } else {
      conciliacion.sumar(
          "Renglones por hoja",
          c.hoja(),
          fila.estado(),
          BigDecimal.ONE,
          BigDecimal.valueOf(lote.altas.size() + lote.asignaciones.size()));
    }
  }
}

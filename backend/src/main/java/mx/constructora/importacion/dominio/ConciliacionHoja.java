package mx.constructora.importacion.dominio;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.constructora.estructura.ClaveUnidad;
import mx.constructora.importacion.dominio.xlsx.Celda;
import mx.constructora.importacion.dominio.xlsx.LibroXlsx;
import mx.constructora.importacion.dominio.xlsx.Renglon;

/**
 * Concilia el costo de los lotes de un contrato en el sistema contra la hoja de su frente
 * (CORCELES, PALERMO, GRANADA) y explica cada peso de diferencia (issue 38).
 *
 * <p>La hoja no se lee: sus 192,606 celdas son fórmulas y el archivo se guardó sin terminar de
 * calcular. Se <b>recalcula</b> con las mismas reglas que sus fórmulas, a partir de lo capturado en
 * el mismo archivo:
 *
 * <ul>
 *   <li>Cada columna de lote suma la cantidad de Salidas con esa manzana y lote, ese material
 *       (nombre exacto, sin importar mayúsculas), Entregado = TRUE, razón "Programa" y el frente de
 *       la hoja ({@code SUMIFS}).
 *   <li>La multiplica por el precio de Existencias: el promedio simple de "Precio Unitario +IVA" de
 *       las entradas del material ({@code AVERAGEIFS}); sin entradas, la celda queda vacía.
 * </ul>
 *
 * <p>Luego cada renglón de Salidas se compara contra lo que el sistema guardó de él (por su fila de
 * Excel) y la diferencia se reparte en motivos: el lote no es columna de la hoja, el renglón decía
 * varios lotes, el sistema lo cargó a otro lote, la hoja suma el IVA, promedia simple en lugar de
 * ponderar… Por construcción, sistema + efectos = hoja, peso por peso.
 */
public final class ConciliacionHoja {

  /** Por qué difiere un renglón. */
  public enum Motivo {
    SIN_COLUMNA("El lote no es columna de la hoja"),
    REPARTO("El renglón decía varios lotes y el sistema los repartió"),
    LOTE_CORREGIDO("El sistema lo cargó a otro lote (decisión de la cuarentena)"),
    MATERIAL("El nombre del material no coincide con ningún renglón de la hoja"),
    RAZON("La razón no dice exactamente \"Programa\""),
    FRENTE("El frente del renglón no es el de la hoja"),
    NO_ENTREGADO("No está marcado como entregado"),
    CANTIDAD("La cantidad no es un número para la hoja"),
    SOLO_EN_HOJA("La hoja lo cuenta en este lote y el sistema no"),
    SIN_PRECIO("El material no tiene precio en la hoja (sin entradas)"),
    PONDERACION("La hoja usa el promedio simple con descuento incluido; el sistema, el ponderado"),
    IVA("La hoja suma el IVA al precio");

    private final String descripcion;

    Motivo(String descripcion) {
      this.descripcion = descripcion;
    }

    public String descripcion() {
      return descripcion;
    }
  }

  /**
   * Lo que el sistema guardó de un renglón de Salidas (Programa, entregado) en un lote o sin él.
   */
  public record SalidaSistema(
      int filaExcel, String lote, BigDecimal cantidad, BigDecimal importe, boolean repartida) {}

  /** Un lote del contrato: lo del sistema, cada efecto y lo que diría la hoja. */
  public record Lote(
      String clave,
      boolean columnaEnHoja,
      BigDecimal sistema,
      Map<Motivo, BigDecimal> efectos,
      BigDecimal hoja,
      BigDecimal redondeo) {}

  /** Un renglón de Salidas que explica parte de la diferencia de un lote. */
  public record Detalle(
      int filaExcel,
      String lote,
      String material,
      Motivo motivo,
      BigDecimal cantidad,
      BigDecimal importe,
      String nota) {}

  public record Resultado(
      String hoja,
      String razon,
      String frente,
      int columnasDeLote,
      List<Lote> lotes,
      List<Detalle> detalle,
      List<String> avisos) {}

  private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);
  private static final Pattern CRITERIO =
      Pattern.compile(
          "Salidas!\\$?([A-Z]{1,3})\\$?[0-9]*:\\$?[A-Z]{1,3}\\$?[0-9]*\\s*,\\s*(\"[^\"]*\"|TRUE|FALSE|[A-Za-z_."
              + " ]+![$]?[A-Z]{1,3}[$]?[0-9]+|[$]?[A-Z]{1,3}[$]?[0-9]+)(?=\\s*[,)])");
  private static final Pattern REFERENCIA =
      Pattern.compile("(?:[A-Za-z_. ]+!)?\\$?([A-Z]{1,3})\\$?([0-9]+)");
  private static final Pattern EXISTENCIAS = Pattern.compile("Existencias!\\$?F\\$?([0-9]+)");

  private ConciliacionHoja() {}

  /** Una columna de lote de la hoja con sus criterios ya resueltos. */
  private record Columna(String mza, String lote, String clave) {}

  /** Criterios comunes de las columnas de lote. */
  private record Criterios(String razon, String frente, boolean entregado) {}

  /** Un renglón capturado de Salidas. */
  private record Salida(
      int fila,
      String razon,
      String mza,
      String lote,
      BigDecimal cantidad,
      String material,
      String frente,
      boolean entregado) {}

  /** Precio de la hoja para un material: promedio de G y de G × IVA de sus entradas. */
  private record Precio(BigDecimal promedioSinIva, BigDecimal promedioIva) {
    BigDecimal conIva() {
      return promedioSinIva.add(promedioIva);
    }
  }

  /**
   * @param hojaFrente nombre de la hoja del frente (la clave del frente: CORCELES)
   * @param lotes claves de los lotes del contrato ({@code M20-L29})
   * @param sistema lo que el sistema guardó de Salidas para el frente: Programa y entregado
   */
  public static Resultado conciliar(
      LibroXlsx libro, String hojaFrente, Set<String> lotes, List<SalidaSistema> sistema) {
    String hoja =
        Comun.hoja(libro, hojaFrente)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "El archivo no tiene la hoja \"" + hojaFrente + "\""));
    List<String> avisos = new ArrayList<>();

    // 1. La hoja del frente: columnas de lote, sus criterios y qué material va en cada renglón.
    Map<Integer, Renglon> encabezado = new HashMap<>();
    Map<Integer, String> materialDeFila = new LinkedHashMap<>();
    Map<Integer, Integer> existenciaDeFila = new HashMap<>();
    Renglon[] primeraFila = new Renglon[1];
    libro.leer(
        hoja,
        r -> {
          if (r.numero() <= 4) {
            encabezado.put(r.numero(), r);
            return true;
          }
          String d = r.capturado("D");
          if (d != null && d.trim().equalsIgnoreCase("TOTAL")) {
            return false;
          }
          if (primeraFila[0] == null) {
            primeraFila[0] = r;
          }
          Celda celda = r.celda("D");
          if (celda != null && celda.esFormula()) {
            Matcher m = EXISTENCIAS.matcher(celda.formula());
            if (m.find()) {
              existenciaDeFila.put(r.numero(), Integer.parseInt(m.group(1)));
            }
          } else if (d != null) {
            materialDeFila.put(r.numero(), d);
          }
          return true;
        });
    if (primeraFila[0] == null || !encabezado.containsKey(2) || !encabezado.containsKey(3)) {
      throw new IllegalArgumentException("La hoja " + hoja + " no tiene el formato esperado");
    }
    Renglon etiquetas = encabezado.get(2);
    Renglon valores = encabezado.get(3);
    List<Columna> columnas = new ArrayList<>();
    Criterios criterios = null;
    for (String col :
        etiquetas.celdas().keySet().stream().sorted(ConciliacionHoja::compararColumnas).toList()) {
      if (!"MZA".equalsIgnoreCase(texto(etiquetas.capturado(col)))) {
        continue;
      }
      Celda formula = primeraFila[0].celda(col);
      if (formula == null || !formula.esFormula()) {
        continue;
      }
      Map<String, String> c = criterios(formula.formula());
      String mza = valorDeReferencia(c.get("F"), encabezado);
      String lote = valorDeReferencia(c.get("G"), encabezado);
      if (criterios == null) {
        criterios =
            new Criterios(
                literal(c.get("C")).orElse("Programa"),
                literal(c.get("L")).orElse(hojaFrente),
                !"FALSE".equalsIgnoreCase(c.getOrDefault("N", "TRUE")));
      }
      String clave = clave(mza, lote).orElse(null);
      if (clave == null) {
        continue;
      }
      columnas.add(new Columna(mza, lote, clave));
    }
    if (criterios == null) {
      throw new IllegalArgumentException("La hoja " + hoja + " no tiene columnas de lote");
    }
    Set<String> clavesHoja = new LinkedHashSet<>();
    columnas.forEach(c -> clavesHoja.add(c.clave()));

    // 2. Existencias (nombre de cada renglón) y Entradas (precio de la hoja).
    Map<Integer, String> nombres = new HashMap<>();
    libro.leer(
        "Existencias",
        Set.of("F"),
        r -> {
          if (r.numero() >= 5 && r.capturado("F") != null) {
            nombres.put(r.numero(), r.capturado("F"));
          }
          return true;
        });
    existenciaDeFila.forEach(
        (fila, existencia) -> materialDeFila.put(fila, nombres.getOrDefault(existencia, "")));
    Map<String, BigDecimal[]> porNombre = new HashMap<>();
    List<String> copias = new ArrayList<>();
    libro.leer(
        "Entradas",
        Set.of("F", "G", "J"),
        r -> {
          if (r.numero() < 2 || r.capturado("F") == null) {
            return true;
          }
          Optional<BigDecimal> g = numero(r.celda("G"), copias);
          if (g.isEmpty()) {
            return true;
          }
          BigDecimal iva = numero(r.celda("J"), copias).orElse(BigDecimal.ZERO);
          BigDecimal k = g.get().add(g.get().multiply(iva, MC), MC);
          if (k.signum() <= 0) {
            return true;
          }
          BigDecimal[] suma =
              porNombre.computeIfAbsent(
                  r.capturado("F").toLowerCase(Locale.ROOT),
                  x -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
          suma[0] = suma[0].add(g.get());
          suma[1] = suma[1].add(g.get().multiply(iva, MC));
          suma[2] = suma[2].add(BigDecimal.ONE);
          return true;
        });
    Map<Integer, Precio> precioDeFila = new HashMap<>();
    materialDeFila.forEach(
        (fila, nombre) -> precio(nombre, porNombre).ifPresent(p -> precioDeFila.put(fila, p)));

    // 3. Salidas capturadas.
    List<Salida> salidas = new ArrayList<>();
    libro.leer(
        "Salidas",
        Set.of("C", "F", "G", "H", "J", "L", "N"),
        r -> {
          if (r.numero() < 3) {
            return true;
          }
          Celda h = r.celda("H");
          String material = r.capturado("J");
          if (h == null && material == null) {
            return true;
          }
          Celda n = r.celda("N");
          salidas.add(
              new Salida(
                  r.numero(),
                  r.capturado("C"),
                  valorCrudo(r.celda("F")),
                  valorCrudo(r.celda("G")),
                  numero(h, copias).orElse(null),
                  material,
                  r.capturado("L"),
                  n != null && n.tipo() == Celda.Tipo.LOGICO && "TRUE".equals(n.valor())));
          return true;
        });

    // 4. Renglón por renglón: dónde lo cuenta la hoja y dónde el sistema.
    Map<Integer, List<SalidaSistema>> sistemaPorFila = new HashMap<>();
    for (SalidaSistema s : sistema) {
      sistemaPorFila.computeIfAbsent(s.filaExcel(), k -> new ArrayList<>()).add(s);
    }
    Map<String, Map<Motivo, BigDecimal>> efectos = new LinkedHashMap<>();
    Map<String, BigDecimal> totalSistema = new LinkedHashMap<>();
    Map<String, BigDecimal> totalHoja = new LinkedHashMap<>();
    for (String l : lotes.stream().sorted(ConciliacionHoja::compararLotes).toList()) {
      efectos.put(l, new EnumMap<>(Motivo.class));
      totalSistema.put(l, BigDecimal.ZERO);
      totalHoja.put(l, BigDecimal.ZERO);
    }
    List<Detalle> detalle = new ArrayList<>();
    Criterios k = criterios;
    // Índices para no comparar cada renglón contra cada material y cada columna.
    Map<String, List<Integer>> filasPorNombre = new HashMap<>();
    List<Integer> filasConComodin = new ArrayList<>();
    materialDeFila.forEach(
        (fila, nombre) -> {
          if (nombre == null || nombre.isEmpty()) {
            return;
          }
          if (tieneComodines(nombre)) {
            filasConComodin.add(fila);
          } else {
            filasPorNombre.computeIfAbsent(llave(nombre), x -> new ArrayList<>()).add(fila);
          }
        });
    Map<String, List<Columna>> columnasPorLote = new HashMap<>();
    for (Columna c : columnas) {
      columnasPorLote
          .computeIfAbsent(llave(c.mza()) + "|" + llave(c.lote()), x -> new ArrayList<>())
          .add(c);
    }
    for (Salida r : salidas) {
      // Lo que cuenta la hoja: en qué columnas de lote y con qué renglones de material.
      boolean pasaComunes =
          r.cantidad() != null
              && coincide(k.razon(), r.razon())
              && coincide(k.frente(), r.frente())
              && (!k.entregado() || r.entregado());
      List<Integer> filasMaterial = new ArrayList<>();
      if (pasaComunes && r.material() != null) {
        filasMaterial.addAll(filasPorNombre.getOrDefault(llave(r.material()), List.of()));
        for (int fila : filasConComodin) {
          if (coincide(materialDeFila.get(fila), r.material())) {
            filasMaterial.add(fila);
          }
        }
      }
      Map<String, BigDecimal> hojaPorLote = new HashMap<>();
      Map<String, BigDecimal[]> partesPorLote = new HashMap<>();
      boolean sinPrecio = false;
      if (!filasMaterial.isEmpty()) {
        List<Columna> deEsteLote =
            r.mza() == null || r.lote() == null
                ? List.of()
                : columnasPorLote.getOrDefault(llave(r.mza()) + "|" + llave(r.lote()), List.of());
        for (Columna c : deEsteLote) {
          for (int fila : filasMaterial) {
            Precio p = precioDeFila.get(fila);
            BigDecimal[] partes =
                partesPorLote.computeIfAbsent(
                    c.clave(),
                    x -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            partes[2] = partes[2].add(r.cantidad());
            if (p == null) {
              sinPrecio = true;
              continue;
            }
            partes[0] = partes[0].add(r.cantidad().multiply(p.promedioSinIva(), MC));
            partes[1] = partes[1].add(r.cantidad().multiply(p.promedioIva(), MC));
            hojaPorLote.merge(c.clave(), r.cantidad().multiply(p.conIva(), MC), BigDecimal::add);
          }
        }
      }
      // Lo que guardó el sistema, por lote.
      Map<String, BigDecimal> sistemaPorLote = new HashMap<>();
      Map<String, BigDecimal> cantidadPorLote = new HashMap<>();
      boolean repartida = false;
      for (SalidaSistema s : sistemaPorFila.getOrDefault(r.fila(), List.of())) {
        String l = s.lote() == null ? "" : s.lote();
        sistemaPorLote.merge(l, importe(s), BigDecimal::add);
        cantidadPorLote.merge(l, s.cantidad(), BigDecimal::add);
        repartida |= s.repartida();
      }
      Set<String> tocados = new LinkedHashSet<>(partesPorLote.keySet());
      tocados.addAll(sistemaPorLote.keySet());
      for (String l : tocados) {
        if (!efectos.containsKey(l)) {
          continue; // Lote de otro contrato o "sin asignar": no es de este reporte.
        }
        BigDecimal enSistema = sistemaPorLote.get(l);
        BigDecimal[] partes = partesPorLote.get(l);
        if (enSistema != null) {
          totalSistema.merge(l, enSistema, BigDecimal::add);
        }
        if (partes != null) {
          totalHoja.merge(l, hojaPorLote.getOrDefault(l, BigDecimal.ZERO), BigDecimal::add);
        }
        if (enSistema != null && partes != null) {
          if (partes[0].signum() == 0 && partes[1].signum() == 0 && sinPrecio) {
            anotar(efectos, detalle, r, l, Motivo.SIN_PRECIO, enSistema.negate(), partes[2], null);
          } else {
            anotar(
                efectos,
                detalle,
                r,
                l,
                Motivo.PONDERACION,
                partes[0].subtract(enSistema),
                partes[2],
                null);
            anotar(efectos, detalle, r, l, Motivo.IVA, partes[1], partes[2], null);
          }
        } else if (enSistema != null) {
          Motivo m = motivo(r, l, k, filasMaterial.isEmpty(), clavesHoja, repartida);
          anotar(
              efectos,
              detalle,
              r,
              l,
              m,
              enSistema.negate(),
              cantidadPorLote.get(l),
              m == Motivo.LOTE_CORREGIDO
                  ? "La hoja lo cuenta en " + lotesDondeCuenta(partesPorLote)
                  : null);
        } else {
          String donde =
              sistemaPorLote.isEmpty()
                  ? "El sistema no lo tiene (cuarentena o descartado)"
                  : "El sistema lo cargó a "
                      + String.join(
                          ", ",
                          sistemaPorLote.keySet().stream()
                              .map(x -> x.isEmpty() ? "Sin asignar" : x)
                              .toList());
          anotar(
              efectos,
              detalle,
              r,
              l,
              Motivo.SOLO_EN_HOJA,
              hojaPorLote.getOrDefault(l, BigDecimal.ZERO),
              partes[2],
              donde);
        }
      }
    }

    // 5. Resultado por lote, redondeado a centavos.
    List<Lote> resultado = new ArrayList<>();
    for (var e : efectos.entrySet()) {
      String l = e.getKey();
      BigDecimal sistemaLote = centavos(totalSistema.get(l));
      Map<Motivo, BigDecimal> redondeados = new EnumMap<>(Motivo.class);
      BigDecimal suma = sistemaLote;
      for (var f : e.getValue().entrySet()) {
        BigDecimal v = centavos(f.getValue());
        if (v.signum() != 0) {
          redondeados.put(f.getKey(), v);
          suma = suma.add(v);
        }
      }
      BigDecimal hojaLote = centavos(totalHoja.get(l));
      resultado.add(
          new Lote(
              l,
              clavesHoja.contains(l),
              sistemaLote,
              redondeados,
              hojaLote,
              hojaLote.subtract(suma)));
    }
    detalle.sort(
        (a, b) -> {
          int porImporte = b.importe().abs().compareTo(a.importe().abs());
          return porImporte != 0 ? porImporte : Integer.compare(a.filaExcel(), b.filaExcel());
        });
    if (columnas.isEmpty()) {
      avisos.add("La hoja no tiene columnas de lote con manzana y lote válidos");
    }
    if (!copias.isEmpty()) {
      avisos.add(
          copias.size()
              + " celdas son copias de una fórmula (=2/8 arrastrado) y se tomó su valor guardado: "
              + String.join(", ", copias.subList(0, Math.min(10, copias.size())))
              + (copias.size() > 10 ? "…" : ""));
    }
    return new Resultado(hoja, k.razon(), k.frente(), columnas.size(), resultado, detalle, avisos);
  }

  // ------------------------------------------------------------------------------ Apoyo

  /** Por qué la hoja no cuenta en {@code lote} un renglón que el sistema sí. */
  private static Motivo motivo(
      Salida r,
      String lote,
      Criterios k,
      boolean sinRenglonDeMaterial,
      Set<String> clavesHoja,
      boolean repartida) {
    if (r.cantidad() == null) {
      return Motivo.CANTIDAD;
    }
    if (!coincide(k.razon(), r.razon())) {
      return Motivo.RAZON;
    }
    if (!coincide(k.frente(), r.frente())) {
      return Motivo.FRENTE;
    }
    if (k.entregado() && !r.entregado()) {
      return Motivo.NO_ENTREGADO;
    }
    if (repartida) {
      return Motivo.REPARTO;
    }
    if (sinRenglonDeMaterial) {
      return Motivo.MATERIAL;
    }
    if (!clavesHoja.contains(lote)) {
      return Motivo.SIN_COLUMNA;
    }
    return Motivo.LOTE_CORREGIDO;
  }

  private static void anotar(
      Map<String, Map<Motivo, BigDecimal>> efectos,
      List<Detalle> detalle,
      Salida r,
      String lote,
      Motivo motivo,
      BigDecimal importe,
      BigDecimal cantidad,
      String nota) {
    if (importe.signum() == 0 && motivo != Motivo.SOLO_EN_HOJA) {
      return;
    }
    efectos.get(lote).merge(motivo, importe, BigDecimal::add);
    detalle.add(
        new Detalle(
            r.fila(),
            lote,
            r.material(),
            motivo,
            cantidad,
            importe.setScale(4, RoundingMode.HALF_EVEN),
            nota));
  }

  private static String lotesDondeCuenta(Map<String, BigDecimal[]> partes) {
    return partes.isEmpty() ? "ningún lote" : String.join(", ", partes.keySet());
  }

  /** Promedio de la hoja para un nombre de material ({@code AVERAGEIFS} con K > 0). */
  private static Optional<Precio> precio(String nombre, Map<String, BigDecimal[]> porNombre) {
    BigDecimal sinIva = BigDecimal.ZERO;
    BigDecimal iva = BigDecimal.ZERO;
    BigDecimal n = BigDecimal.ZERO;
    if (tieneComodines(nombre)) {
      for (var e : porNombre.entrySet()) {
        if (coincide(nombre, e.getKey())) {
          sinIva = sinIva.add(e.getValue()[0]);
          iva = iva.add(e.getValue()[1]);
          n = n.add(e.getValue()[2]);
        }
      }
    } else {
      BigDecimal[] s = porNombre.get(nombre.toLowerCase(Locale.ROOT));
      if (s != null) {
        sinIva = s[0];
        iva = s[1];
        n = s[2];
      }
    }
    if (n.signum() == 0) {
      return Optional.empty();
    }
    return Optional.of(new Precio(sinIva.divide(n, MC), iva.divide(n, MC)));
  }

  /**
   * Criterio de {@code SUMIFS}: un número coincide con el mismo número (aunque esté escrito como
   * texto); un texto, con el mismo texto sin importar mayúsculas, con {@code *} y {@code ?} como
   * comodines.
   */
  static boolean coincide(String criterio, String valor) {
    if (criterio == null || criterio.isEmpty()) {
      return valor == null || valor.isEmpty();
    }
    if (valor == null) {
      return false;
    }
    Optional<BigDecimal> a = numeroEstricto(criterio);
    Optional<BigDecimal> b = numeroEstricto(valor);
    if (a.isPresent() && b.isPresent()) {
      return a.get().compareTo(b.get()) == 0;
    }
    if (!tieneComodines(criterio)) {
      return criterio.equalsIgnoreCase(valor);
    }
    StringBuilder regex = new StringBuilder();
    for (int i = 0; i < criterio.length(); i++) {
      char c = criterio.charAt(i);
      if (c == '~' && i + 1 < criterio.length()) {
        regex.append(Pattern.quote(String.valueOf(criterio.charAt(++i))));
      } else if (c == '*') {
        regex.append(".*");
      } else if (c == '?') {
        regex.append('.');
      } else {
        regex.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return Pattern.compile(
            regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL)
        .matcher(valor)
        .matches();
  }

  /**
   * Llave para buscar sin comodines: un número sin ceros de más ({@code 20.0} y {@code 20} son lo
   * mismo); un texto, en minúsculas.
   */
  static String llave(String valor) {
    return numeroEstricto(valor)
        .map(n -> n.stripTrailingZeros().toPlainString())
        .orElse(valor.toLowerCase(Locale.ROOT));
  }

  private static Optional<BigDecimal> numeroEstricto(String texto) {
    if (texto == null || texto.isEmpty() || !texto.equals(texto.trim())) {
      return Optional.empty();
    }
    try {
      return Optional.of(new BigDecimal(texto));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static boolean tieneComodines(String texto) {
    return texto.indexOf('*') >= 0 || texto.indexOf('?') >= 0;
  }

  /** Criterios de un {@code SUMIFS}: columna de Salidas → criterio. */
  private static Map<String, String> criterios(String formula) {
    Map<String, String> mapa = new HashMap<>();
    Matcher m = CRITERIO.matcher(formula);
    while (m.find()) {
      mapa.put(m.group(1), m.group(2).trim());
    }
    return mapa;
  }

  private static Optional<String> literal(String criterio) {
    if (criterio != null && criterio.length() >= 2 && criterio.startsWith("\"")) {
      return Optional.of(criterio.substring(1, criterio.length() - 1));
    }
    return Optional.empty();
  }

  /** El valor de una celda del encabezado a la que apunta un criterio ({@code CORCELES!Q$3}). */
  private static String valorDeReferencia(String criterio, Map<Integer, Renglon> encabezado) {
    if (criterio == null) {
      return null;
    }
    Optional<String> texto = literal(criterio);
    if (texto.isPresent()) {
      return texto.get();
    }
    Matcher m = REFERENCIA.matcher(criterio);
    if (!m.matches()) {
      return null;
    }
    Renglon r = encabezado.get(Integer.parseInt(m.group(2)));
    return r == null ? null : valorCrudo(r.celda(m.group(1)));
  }

  /**
   * Lo que hay en la celda para un criterio: el valor capturado, o el de una fórmula aritmética.
   */
  private static String valorCrudo(Celda celda) {
    if (celda == null) {
      return null;
    }
    if (celda.esFormula()) {
      return Aritmetica.evaluar(celda.formula()).map(BigDecimal::toPlainString).orElse(null);
    }
    return celda.capturado();
  }

  /**
   * El número de la celda: lo capturado, o el resultado de una fórmula aritmética. Una fórmula
   * compartida o de matriz sin texto (Excel la guarda así en las copias: {@code =2/8} arrastrado)
   * no se puede evaluar: se toma su valor guardado y se avisa.
   */
  private static Optional<BigDecimal> numero(Celda celda) {
    return numero(celda, null);
  }

  private static Optional<BigDecimal> numero(Celda celda, List<String> copias) {
    if (celda == null) {
      return Optional.empty();
    }
    if (celda.esFormula()) {
      if (celda.formula().isBlank() && celda.tipo() == Celda.Tipo.NUMERO) {
        if (copias != null) {
          copias.add(celda.referencia());
        }
        return Comun.decimal(celda.valor());
      }
      return Aritmetica.evaluar(celda.formula());
    }
    if (celda.tipo() != Celda.Tipo.NUMERO) {
      return Optional.empty();
    }
    return Comun.decimal(celda.capturado());
  }

  private static Optional<String> clave(String mza, String lote) {
    if (mza == null || lote == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(new ClaveUnidad(sinPunto(mza), sinPunto(lote)).clave());
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** {@code 20.0} → {@code 20}. */
  private static String sinPunto(String numero) {
    return numero.trim().replaceFirst("\\.0+$", "");
  }

  private static String texto(String valor) {
    return valor == null ? "" : valor.trim();
  }

  private static BigDecimal importe(SalidaSistema s) {
    return s.importe() == null ? BigDecimal.ZERO : s.importe();
  }

  private static BigDecimal centavos(BigDecimal v) {
    return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_EVEN);
  }

  private static int compararColumnas(String a, String b) {
    return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
  }

  /** M2-L5 antes que M10-L1. */
  static int compararLotes(String a, String b) {
    int[] x = numeros(a);
    int[] y = numeros(b);
    for (int i = 0; i < 2; i++) {
      if (x[i] != y[i]) {
        return Integer.compare(x[i], y[i]);
      }
    }
    return a.compareTo(b);
  }

  private static int[] numeros(String clave) {
    Matcher m = Pattern.compile("^M([0-9]+)[A-Z]?-L([0-9]+)").matcher(clave);
    return m.find()
        ? new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))}
        : new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE};
  }
}

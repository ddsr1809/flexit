package mx.constructora.importacion.dominio.xlsx;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Lector de {@code .xlsx} en streaming. Recorre el XML de cada hoja con StAX, renglón por renglón,
 * sin cargar el libro en memoria: la hoja CORCELES del almacén pesa 12 MB descomprimida.
 *
 * <p>Lee el valor exacto de cada celda y sabe si es fórmula, que es lo que necesita la migración:
 * se sube lo capturado y nunca el resultado de una fórmula. No evalúa fórmulas.
 *
 * <p>Defensas: no resuelve entidades ni DTD del XML (XXE), limita el número de entradas del zip, el
 * tamaño descomprimido de cada una y del total, y la proporción de compresión (bombas zip).
 */
public final class LibroXlsx implements AutoCloseable {

  /** Recibe cada renglón; devolver {@code false} deja de leer la hoja. */
  @FunctionalInterface
  public interface PorRenglon {
    boolean renglon(Renglon renglon);
  }

  static final int MAXIMO_ENTRADAS = 10_000;
  static final long MAXIMO_POR_ENTRADA = 128L * 1024 * 1024;
  static final long MAXIMO_TOTAL = 512L * 1024 * 1024;
  static final long PROPORCION_MAXIMA = 200;

  private static final String REL_HOJA =
      "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet";
  private static final String REL_TEXTOS =
      "http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings";
  private static final String REL_ESTILOS =
      "http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles";

  private final ZipFile zip;
  private final Map<String, String> rutaPorHoja = new LinkedHashMap<>();
  private final List<String> textos = new ArrayList<>();
  private final List<Boolean> estiloEsFecha = new ArrayList<>();
  private boolean fecha1904;
  private long leidoTotal;

  private LibroXlsx(ZipFile zip) {
    this.zip = zip;
  }

  /**
   * Abre el libro y lee su índice (hojas, textos compartidos y estilos).
   *
   * @throws ArchivoInvalido si no es un .xlsx válido o excede los límites
   */
  public static LibroXlsx abrir(Path archivo) {
    ZipFile zip;
    try {
      zip = new ZipFile(archivo.toFile());
    } catch (ZipException e) {
      throw new ArchivoInvalido("El archivo no es un libro de Excel (.xlsx)", e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    LibroXlsx libro = new LibroXlsx(zip);
    try {
      libro.leerIndice();
      return libro;
    } catch (RuntimeException e) {
      libro.close();
      throw e;
    }
  }

  /** Nombres de las hojas, en el orden del libro. */
  public List<String> hojas() {
    return List.copyOf(rutaPorHoja.keySet());
  }

  public boolean tieneHoja(String nombre) {
    return rutaPorHoja.containsKey(nombre);
  }

  /** Lee toda la hoja. */
  public void leer(String hoja, PorRenglon porRenglon) {
    leer(hoja, null, porRenglon);
  }

  /**
   * Lee la hoja renglón por renglón.
   *
   * @param columnas solo estas columnas ({@code null}: todas)
   * @throws ArchivoInvalido si la hoja no existe o su XML está dañado
   */
  public void leer(String hoja, Set<String> columnas, PorRenglon porRenglon) {
    String ruta = rutaPorHoja.get(hoja);
    if (ruta == null) {
      throw new ArchivoInvalido("El libro no tiene la hoja \"" + hoja + "\"");
    }
    try (InputStream entrada = abrirEntrada(ruta)) {
      XMLStreamReader xml = fabrica().createXMLStreamReader(entrada);
      try {
        new LectorHoja(xml, columnas, porRenglon).leer();
      } finally {
        xml.close();
      }
    } catch (XMLStreamException e) {
      throw new ArchivoInvalido("La hoja \"" + hoja + "\" está dañada", e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    try {
      zip.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ------------------------------------------------------------------------------------ índice

  private void leerIndice() {
    if (zip.size() > MAXIMO_ENTRADAS) {
      throw new ArchivoInvalido("El archivo tiene demasiadas partes para ser un libro de Excel");
    }
    if (zip.getEntry("xl/workbook.xml") == null) {
      throw new ArchivoInvalido("El archivo no es un libro de Excel (.xlsx)");
    }
    Map<String, String> relaciones = new HashMap<>();
    String rutaTextos = null;
    String rutaEstilos = null;
    for (String[] rel : relacionesDelLibro()) {
      String destino = resolver(rel[2]);
      if (REL_HOJA.equals(rel[1])) {
        relaciones.put(rel[0], destino);
      } else if (REL_TEXTOS.equals(rel[1])) {
        rutaTextos = destino;
      } else if (REL_ESTILOS.equals(rel[1])) {
        rutaEstilos = destino;
      }
    }
    leerXml(
        "xl/workbook.xml",
        xml -> {
          if (xml.getLocalName().equals("sheet")) {
            String nombre = atributo(xml, "name");
            String id = atributoConPrefijo(xml, "id");
            String ruta = relaciones.get(id);
            if (nombre != null && ruta != null) {
              rutaPorHoja.put(nombre, ruta);
            }
          } else if (xml.getLocalName().equals("workbookPr")) {
            String valor = atributo(xml, "date1904");
            fecha1904 = "1".equals(valor) || "true".equalsIgnoreCase(valor);
          }
        });
    if (rutaTextos != null && zip.getEntry(rutaTextos) != null) {
      leerTextos(rutaTextos);
    }
    if (rutaEstilos != null && zip.getEntry(rutaEstilos) != null) {
      leerEstilos(rutaEstilos);
    }
  }

  private List<String[]> relacionesDelLibro() {
    List<String[]> relaciones = new ArrayList<>();
    if (zip.getEntry("xl/_rels/workbook.xml.rels") == null) {
      return relaciones;
    }
    leerXml(
        "xl/_rels/workbook.xml.rels",
        xml -> {
          if (xml.getLocalName().equals("Relationship")) {
            relaciones.add(
                new String[] {atributo(xml, "Id"), atributo(xml, "Type"), atributo(xml, "Target")});
          }
        });
    return relaciones;
  }

  private static String resolver(String destino) {
    if (destino == null) {
      return null;
    }
    if (destino.startsWith("/")) {
      return destino.substring(1);
    }
    return destino.startsWith("xl/") ? destino : "xl/" + destino;
  }

  /**
   * Textos compartidos: {@code <si><t>…</t></si>} o con formato {@code <si><r><t>…</t></r></si>}.
   */
  private void leerTextos(String ruta) {
    try (InputStream entrada = abrirEntrada(ruta)) {
      XMLStreamReader xml = fabrica().createXMLStreamReader(entrada);
      try {
        StringBuilder actual = null;
        int fonetico = 0;
        while (xml.hasNext()) {
          int evento = xml.next();
          if (evento == XMLStreamConstants.START_ELEMENT) {
            switch (xml.getLocalName()) {
              case "si" -> actual = new StringBuilder();
              case "rPh" -> fonetico++;
              case "t" -> {
                if (actual != null && fonetico == 0) {
                  actual.append(xml.getElementText());
                }
              }
              default -> {}
            }
          } else if (evento == XMLStreamConstants.END_ELEMENT) {
            if (xml.getLocalName().equals("si") && actual != null) {
              textos.add(actual.toString());
              actual = null;
            } else if (xml.getLocalName().equals("rPh")) {
              fonetico--;
            }
          }
        }
      } finally {
        xml.close();
      }
    } catch (XMLStreamException e) {
      throw new ArchivoInvalido("Los textos del libro están dañados", e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Qué estilos de celda son de fecha: formatos internos 14–22, 45–47 y los propios con d/m/a. */
  private void leerEstilos(String ruta) {
    Map<Integer, String> formatos = new HashMap<>();
    List<Integer> formatoPorEstilo = new ArrayList<>();
    boolean[] enCeldas = {false};
    leerXml(
        ruta,
        xml -> {
          switch (xml.getLocalName()) {
            case "numFmt" -> {
              String id = atributo(xml, "numFmtId");
              if (id != null) {
                formatos.put(entero(id, -1), atributo(xml, "formatCode"));
              }
            }
            case "cellXfs" -> enCeldas[0] = true;
            case "cellStyleXfs", "dxfs" -> enCeldas[0] = false;
            case "xf" -> {
              if (enCeldas[0]) {
                formatoPorEstilo.add(entero(atributo(xml, "numFmtId"), 0));
              }
            }
            default -> {}
          }
        });
    for (int formato : formatoPorEstilo) {
      estiloEsFecha.add(esFormatoFecha(formato, formatos.get(formato)));
    }
  }

  static boolean esFormatoFecha(int id, String codigo) {
    if ((id >= 14 && id <= 22) || (id >= 45 && id <= 47) || (id >= 27 && id <= 36)) {
      return true;
    }
    if (codigo == null || id < 164) {
      return false;
    }
    String limpio =
        codigo
            .replaceAll("\"[^\"]*\"", "")
            .replaceAll("\\\\.", "")
            .replaceAll("\\[(?![hHmMsS]+\\])[^\\]]*\\]", "")
            .toLowerCase();
    if (limpio.contains("general")) {
      return false;
    }
    return limpio.matches(".*[dmyhs].*") && !limpio.matches(".*[#0?].*[dmy].*");
  }

  // ------------------------------------------------------------------------------- utilidades

  @FunctionalInterface
  private interface PorElemento {
    void inicio(XMLStreamReader xml) throws XMLStreamException;
  }

  private void leerXml(String ruta, PorElemento porElemento) {
    try (InputStream entrada = abrirEntrada(ruta)) {
      XMLStreamReader xml = fabrica().createXMLStreamReader(entrada);
      try {
        while (xml.hasNext()) {
          if (xml.next() == XMLStreamConstants.START_ELEMENT) {
            porElemento.inicio(xml);
          }
        }
      } finally {
        xml.close();
      }
    } catch (XMLStreamException e) {
      throw new ArchivoInvalido("El libro está dañado (" + ruta + ")", e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private InputStream abrirEntrada(String ruta) throws IOException {
    ZipEntry entrada = zip.getEntry(ruta);
    if (entrada == null) {
      throw new ArchivoInvalido("Al libro le falta una parte: " + ruta);
    }
    long comprimido = Math.max(entrada.getCompressedSize(), 1);
    return new Limitado(zip.getInputStream(entrada), ruta, comprimido);
  }

  /** Cuenta lo descomprimido y corta antes de que una bomba zip llene la memoria o el disco. */
  private final class Limitado extends FilterInputStream {
    private final String ruta;
    private final long comprimido;
    private long leido;

    Limitado(InputStream entrada, String ruta, long comprimido) {
      super(entrada);
      this.ruta = ruta;
      this.comprimido = comprimido;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b >= 0) {
        contar(1);
      }
      return b;
    }

    @Override
    public int read(byte[] buffer, int desde, int largo) throws IOException {
      int n = super.read(buffer, desde, largo);
      if (n > 0) {
        contar(n);
      }
      return n;
    }

    private void contar(int n) {
      leido += n;
      leidoTotal += n;
      if (leido > MAXIMO_POR_ENTRADA || leidoTotal > MAXIMO_TOTAL) {
        throw new ArchivoInvalido("El libro es demasiado grande para cargarlo (" + ruta + ")");
      }
      if (leido > 1024 * 1024 && leido / comprimido > PROPORCION_MAXIMA) {
        throw new ArchivoInvalido("El archivo parece una bomba zip (" + ruta + ")");
      }
    }
  }

  static XMLInputFactory fabrica() {
    XMLInputFactory fabrica = XMLInputFactory.newFactory();
    fabrica.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    fabrica.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    fabrica.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    fabrica.setProperty(XMLInputFactory.IS_COALESCING, true);
    return fabrica;
  }

  private static String atributo(XMLStreamReader xml, String nombre) {
    for (int i = 0; i < xml.getAttributeCount(); i++) {
      if (xml.getAttributeLocalName(i).equals(nombre)
          && (xml.getAttributePrefix(i) == null || xml.getAttributePrefix(i).isEmpty())) {
        return xml.getAttributeValue(i);
      }
    }
    return null;
  }

  /** {@code r:id} de la hoja: atributo con prefijo de relaciones. */
  private static String atributoConPrefijo(XMLStreamReader xml, String nombre) {
    for (int i = 0; i < xml.getAttributeCount(); i++) {
      if (xml.getAttributeLocalName(i).equals(nombre)
          && xml.getAttributePrefix(i) != null
          && !xml.getAttributePrefix(i).isEmpty()) {
        return xml.getAttributeValue(i);
      }
    }
    return null;
  }

  private static int entero(String texto, int porDefecto) {
    if (texto == null) {
      return porDefecto;
    }
    try {
      return Integer.parseInt(texto.trim());
    } catch (NumberFormatException e) {
      return porDefecto;
    }
  }

  /** {@code "AB12"} → {@code "AB"}. */
  static String columnaDe(String referencia) {
    int i = 0;
    while (i < referencia.length() && Character.isLetter(referencia.charAt(i))) {
      i++;
    }
    return referencia.substring(0, i).toUpperCase();
  }

  /** {@code "AB12"} → 12; 0 si no trae fila. */
  static int filaDe(String referencia) {
    String numero = referencia.substring(columnaDe(referencia).length());
    return numero.isEmpty() ? 0 : entero(numero, 0);
  }

  /** {@code A} → 1, {@code Z} → 26, {@code AA} → 27. */
  public static int indiceColumna(String columna) {
    int indice = 0;
    for (int i = 0; i < columna.length(); i++) {
      indice = indice * 26 + (Character.toUpperCase(columna.charAt(i)) - 'A' + 1);
    }
    return indice;
  }

  /** 1 → {@code A}, 27 → {@code AA}. */
  public static String letraColumna(int indice) {
    StringBuilder letras = new StringBuilder();
    int n = indice;
    while (n > 0) {
      int resto = (n - 1) % 26;
      letras.insert(0, (char) ('A' + resto));
      n = (n - 1) / 26;
    }
    return letras.toString();
  }

  // ---------------------------------------------------------------------------- lector de hoja

  private final class LectorHoja {
    private final XMLStreamReader xml;
    private final Set<String> columnas;
    private final PorRenglon porRenglon;

    LectorHoja(XMLStreamReader xml, Set<String> columnas, PorRenglon porRenglon) {
      this.xml = xml;
      this.columnas = columnas;
      this.porRenglon = porRenglon;
    }

    void leer() throws XMLStreamException {
      int ultimaFila = 0;
      while (xml.hasNext()) {
        if (xml.next() == XMLStreamConstants.START_ELEMENT && xml.getLocalName().equals("row")) {
          int fila = entero(atributo(xml, "r"), ultimaFila + 1);
          ultimaFila = fila;
          Map<String, Celda> celdas = celdasDelRenglon(fila);
          if (!celdas.isEmpty() && !porRenglon.renglon(new Renglon(fila, celdas))) {
            return;
          }
        }
      }
    }

    private Map<String, Celda> celdasDelRenglon(int fila) throws XMLStreamException {
      Map<String, Celda> celdas = new LinkedHashMap<>();
      int ultimaColumna = 0;
      while (xml.hasNext()) {
        int evento = xml.next();
        if (evento == XMLStreamConstants.END_ELEMENT && xml.getLocalName().equals("row")) {
          return celdas;
        }
        if (evento == XMLStreamConstants.START_ELEMENT && xml.getLocalName().equals("c")) {
          String referencia = atributo(xml, "r");
          String columna;
          if (referencia == null) {
            columna = letraColumna(ultimaColumna + 1);
            referencia = columna + fila;
          } else {
            columna = columnaDe(referencia);
          }
          ultimaColumna = indiceColumna(columna);
          Celda celda = celda(referencia, columna);
          if (celda != null && (columnas == null || columnas.contains(columna))) {
            celdas.put(columna, celda);
          }
        }
      }
      return celdas;
    }

    /** Lee {@code <c>} hasta su cierre. Devuelve {@code null} si está vacía. */
    private Celda celda(String referencia, String columna) throws XMLStreamException {
      String tipo = atributo(xml, "t");
      int estilo = entero(atributo(xml, "s"), 0);
      String valor = null;
      String formula = null;
      StringBuilder enLinea = null;
      while (xml.hasNext()) {
        int evento = xml.next();
        if (evento == XMLStreamConstants.END_ELEMENT && xml.getLocalName().equals("c")) {
          break;
        }
        if (evento != XMLStreamConstants.START_ELEMENT) {
          continue;
        }
        switch (xml.getLocalName()) {
          case "v" -> valor = xml.getElementText();
          case "f" -> {
            String texto = xml.getElementText();
            // Una fórmula compartida o de matriz puede venir sin texto: sigue siendo fórmula.
            formula = texto == null ? "" : texto;
          }
          case "is" -> enLinea = new StringBuilder();
          case "t" -> {
            if (enLinea != null) {
              enLinea.append(xml.getElementText());
            }
          }
          default -> {}
        }
      }
      if (enLinea != null) {
        valor = enLinea.toString();
      }
      if (valor == null && formula == null) {
        return null;
      }
      return interpretar(referencia, columna, tipo, estilo, valor, formula);
    }
  }

  private Celda interpretar(
      String referencia, String columna, String tipo, int estilo, String valor, String formula) {
    String t = tipo == null ? "n" : tipo;
    return switch (t) {
      case "s" -> {
        int indice = entero(valor, -1);
        String texto = indice >= 0 && indice < textos.size() ? textos.get(indice) : null;
        yield new Celda(referencia, columna, Celda.Tipo.TEXTO, texto, formula);
      }
      case "inlineStr", "str" -> new Celda(referencia, columna, Celda.Tipo.TEXTO, valor, formula);
      case "b" ->
          new Celda(
              referencia,
              columna,
              Celda.Tipo.LOGICO,
              valor == null ? null : ("1".equals(valor) ? "TRUE" : "FALSE"),
              formula);
      case "e" -> new Celda(referencia, columna, Celda.Tipo.ERROR, valor, formula);
      case "d" -> new Celda(referencia, columna, Celda.Tipo.FECHA, valor, formula);
      default -> {
        if (valor != null && esFecha(estilo)) {
          String fecha = fechaDeSerie(valor, fecha1904);
          if (fecha != null) {
            yield new Celda(referencia, columna, Celda.Tipo.FECHA, fecha, formula);
          }
        }
        yield new Celda(referencia, columna, Celda.Tipo.NUMERO, valor, formula);
      }
    };
  }

  private boolean esFecha(int estilo) {
    return estilo >= 0 && estilo < estiloEsFecha.size() && estiloEsFecha.get(estilo);
  }

  /**
   * Número de serie de Excel → ISO. En el sistema 1900, Excel cuenta el 29 de febrero de 1900 que
   * no existió: a partir del día 61 la fecha es 30/12/1899 + serie.
   */
  static String fechaDeSerie(String serie, boolean desde1904) {
    java.math.BigDecimal numero;
    try {
      numero = new java.math.BigDecimal(serie.trim());
    } catch (NumberFormatException e) {
      return null;
    }
    if (numero.signum() < 0) {
      return null;
    }
    long dias = numero.longValue();
    long segundos =
        numero
            .subtract(java.math.BigDecimal.valueOf(dias))
            .multiply(java.math.BigDecimal.valueOf(86_400))
            .setScale(0, java.math.RoundingMode.HALF_UP)
            .longValue();
    if (segundos >= 86_400) {
      dias++;
      segundos -= 86_400;
    }
    LocalTime hora = LocalTime.ofSecondOfDay(segundos);
    if (dias == 0 && !desde1904) {
      return hora.format(DateTimeFormatter.ISO_LOCAL_TIME);
    }
    LocalDate fecha;
    if (desde1904) {
      fecha = LocalDate.of(1904, 1, 1).plusDays(dias);
    } else if (dias >= 61) {
      fecha = LocalDate.of(1899, 12, 30).plusDays(dias);
    } else {
      fecha = LocalDate.of(1899, 12, 31).plusDays(dias);
    }
    if (segundos == 0) {
      return fecha.toString();
    }
    return LocalDateTime.of(fecha, hora).toString();
  }

  /** Para pruebas: las hojas en el orden del libro. */
  Map<String, String> rutas() {
    return Collections.unmodifiableMap(rutaPorHoja);
  }
}

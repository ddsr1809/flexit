package mx.constructora.compartido;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Escribe un .xlsx sencillo (SpreadsheetML): hojas con encabezado fijo y filtro, números con
 * formato de moneda, cantidad, porcentaje o fecha, y una fila de totales en negritas. Sin
 * dependencias: los reportes solo exportan tablas, y un .xlsx es un zip con unos cuantos XML.
 *
 * <p>Los textos van como cadenas en línea ({@code inlineStr}): Excel nunca los evalúa, así que un
 * texto que empieza con {@code =} no se vuelve fórmula. No se escribe ninguna fórmula: los totales
 * van calculados.
 */
public final class LibroExcel {

  /** Tipo MIME de un .xlsx. */
  public static final String TIPO =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  /** Cómo se muestra una columna. */
  public enum Formato {
    TEXTO(0, 7),
    ENTERO(5, 11),
    CANTIDAD(3, 9),
    MONEDA(2, 8),
    /** De 0 a 100 (no de 0 a 1): {@code 85.5} se ve {@code 85.5%}. */
    PORCENTAJE(6, 12),
    FECHA(4, 10);

    private final int estilo;
    private final int estiloTotal;

    Formato(int estilo, int estiloTotal) {
      this.estilo = estilo;
      this.estiloTotal = estiloTotal;
    }
  }

  /** Una columna: título, formato y ancho en caracteres. */
  public record Columna(String titulo, Formato formato, int ancho) {

    public static Columna texto(String titulo, int ancho) {
      return new Columna(titulo, Formato.TEXTO, ancho);
    }

    public static Columna moneda(String titulo) {
      return new Columna(titulo, Formato.MONEDA, 15);
    }

    public static Columna cantidad(String titulo) {
      return new Columna(titulo, Formato.CANTIDAD, 12);
    }
  }

  /**
   * Un valor lógico de Excel (VERDADERO/FALSO), no el texto "Sí"/"No" que se escribe con un {@code
   * Boolean}: lo usa la exportación con el formato de la hoja Salidas, cuya columna Entregado suman
   * las fórmulas del Excel.
   */
  public record Logico(boolean valor) {}

  /** Una hoja del libro. */
  public final class Hoja {
    private final String nombre;
    private final List<Columna> columnas;
    private final StringBuilder filas = new StringBuilder();
    private int numero = 1;

    private Hoja(String nombre, List<Columna> columnas) {
      this.nombre = nombre;
      this.columnas = List.copyOf(columnas);
      StringBuilder encabezado = new StringBuilder();
      for (int i = 0; i < columnas.size(); i++) {
        celdaTexto(encabezado, i, columnas.get(i).titulo(), 1);
      }
      agregar(encabezado);
    }

    /** Agrega una fila; {@code null} deja la celda vacía. */
    public Hoja fila(Object... valores) {
      agregar(celdas(valores, false));
      return this;
    }

    /** Agrega una fila en negritas (totales). */
    public Hoja totales(Object... valores) {
      agregar(celdas(valores, true));
      return this;
    }

    private StringBuilder celdas(Object[] valores, boolean negritas) {
      if (valores.length > columnas.size()) {
        throw new IllegalArgumentException("Más valores que columnas en la hoja " + nombre);
      }
      StringBuilder xml = new StringBuilder();
      for (int i = 0; i < valores.length; i++) {
        Object v = valores[i];
        if (v == null) {
          continue;
        }
        Formato f = columnas.get(i).formato();
        int estilo = negritas ? f.estiloTotal : f.estilo;
        switch (v) {
          case BigDecimal n -> celdaNumero(xml, i, n.toPlainString(), estilo);
          case Integer n -> celdaNumero(xml, i, n.toString(), estilo);
          case Long n -> celdaNumero(xml, i, n.toString(), estilo);
          case LocalDate d -> celdaNumero(xml, i, Long.toString(serial(d)), estilo);
          case OffsetDateTime d ->
              celdaNumero(xml, i, Long.toString(serial(d.toLocalDate())), estilo);
          case Boolean b -> celdaTexto(xml, i, b ? "Sí" : "No", negritas ? 7 : 0);
          case Logico l -> celdaLogica(xml, i, l.valor());
          default -> celdaTexto(xml, i, v.toString(), negritas ? 7 : 0);
        }
      }
      return xml;
    }

    private void agregar(StringBuilder celdas) {
      filas.append("<row r=\"").append(numero).append("\">").append(celdas).append("</row>");
      numero++;
    }

    private void celdaTexto(StringBuilder xml, int columna, String texto, int estilo) {
      xml.append("<c r=\"")
          .append(referencia(columna, numero))
          .append("\" t=\"inlineStr\"")
          .append(estilo == 0 ? "" : " s=\"" + estilo + "\"")
          .append("><is><t xml:space=\"preserve\">")
          .append(escapar(texto))
          .append("</t></is></c>");
    }

    private void celdaLogica(StringBuilder xml, int columna, boolean valor) {
      xml.append("<c r=\"")
          .append(referencia(columna, this.numero))
          .append("\" t=\"b\"><v>")
          .append(valor ? 1 : 0)
          .append("</v></c>");
    }

    private void celdaNumero(StringBuilder xml, int columna, String numero, int estilo) {
      xml.append("<c r=\"")
          .append(referencia(columna, this.numero))
          .append("\" s=\"")
          .append(estilo)
          .append("\"><v>")
          .append(numero)
          .append("</v></c>");
    }

    private String xml() {
      String ultima = referencia(columnas.size() - 1, Math.max(numero - 1, 1));
      StringBuilder anchos = new StringBuilder("<cols>");
      for (int i = 0; i < columnas.size(); i++) {
        anchos
            .append("<col min=\"")
            .append(i + 1)
            .append("\" max=\"")
            .append(i + 1)
            .append("\" width=\"")
            .append(Math.max(4, columnas.get(i).ancho()))
            .append("\" customWidth=\"1\"/>");
      }
      anchos.append("</cols>");
      return """
      <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
      <sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" \
      activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>\
      %s<sheetData>%s</sheetData><autoFilter ref="A1:%s"/></worksheet>\
      """
          .formatted(anchos, filas, ultima);
    }
  }

  private final List<Hoja> hojas = new ArrayList<>();

  /** Hoja nueva con su fila de encabezados. */
  public Hoja hoja(String nombre, List<Columna> columnas) {
    if (columnas.isEmpty()) {
      throw new IllegalArgumentException("Una hoja necesita al menos una columna");
    }
    String limpio = nombre.replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
    if (limpio.isEmpty()) {
      limpio = "Hoja" + (hojas.size() + 1);
    }
    if (limpio.length() > 31) {
      limpio = limpio.substring(0, 31);
    }
    for (Hoja h : hojas) {
      if (h.nombre.equalsIgnoreCase(limpio)) {
        limpio =
            (limpio.length() > 28 ? limpio.substring(0, 28) : limpio) + " " + (hojas.size() + 1);
      }
    }
    Hoja hoja = new Hoja(limpio, columnas);
    hojas.add(hoja);
    return hoja;
  }

  /** El archivo .xlsx. */
  public byte[] bytes() {
    if (hojas.isEmpty()) {
      throw new IllegalStateException("El libro no tiene hojas");
    }
    ByteArrayOutputStream salida = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(salida, StandardCharsets.UTF_8)) {
      StringBuilder tipos = new StringBuilder();
      StringBuilder relaciones = new StringBuilder();
      StringBuilder listaHojas = new StringBuilder();
      for (int i = 1; i <= hojas.size(); i++) {
        tipos
            .append("<Override PartName=\"/xl/worksheets/sheet")
            .append(i)
            .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.")
            .append("spreadsheetml.worksheet+xml\"/>");
        relaciones
            .append("<Relationship Id=\"rId")
            .append(i)
            .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/")
            .append("relationships/worksheet\" Target=\"worksheets/sheet")
            .append(i)
            .append(".xml\"/>");
        listaHojas
            .append("<sheet name=\"")
            .append(escapar(hojas.get(i - 1).nombre))
            .append("\" sheetId=\"")
            .append(i)
            .append("\" r:id=\"rId")
            .append(i)
            .append("\"/>");
      }
      int estilos = hojas.size() + 1;
      escribir(
          zip,
          "[Content_Types].xml",
          """
          <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
          <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
          <Default Extension="xml" ContentType="application/xml"/>\
          <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
          <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>\
          %s</Types>\
          """
              .formatted(tipos));
      escribir(
          zip,
          "_rels/.rels",
          """
          <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
          <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
          </Relationships>\
          """);
      escribir(
          zip,
          "xl/workbook.xml",
          """
          <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
          <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">\
          <sheets>%s</sheets></workbook>\
          """
              .formatted(listaHojas));
      escribir(
          zip,
          "xl/_rels/workbook.xml.rels",
          """
          <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
          <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
          %s<Relationship Id="rId%d" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>\
          </Relationships>\
          """
              .formatted(relaciones, estilos));
      escribir(zip, "xl/styles.xml", ESTILOS);
      for (int i = 1; i <= hojas.size(); i++) {
        escribir(zip, "xl/worksheets/sheet" + i + ".xml", hojas.get(i - 1).xml());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return salida.toByteArray();
  }

  /**
   * Estilos (índice en cellXfs): 0 normal, 1 encabezado, 2 moneda, 3 cantidad, 4 fecha, 5 entero, 6
   * porcentaje; 7 a 13, los mismos en negritas para totales.
   */
  private static final String ESTILOS =
      """
      <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
      <numFmts count="4">\
      <numFmt numFmtId="164" formatCode="&quot;$&quot;#,##0.00"/>\
      <numFmt numFmtId="165" formatCode="#,##0.00##"/>\
      <numFmt numFmtId="166" formatCode="yyyy\\-mm\\-dd"/>\
      <numFmt numFmtId="167" formatCode="0.0&quot;%&quot;"/>\
      </numFmts>\
      <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font>\
      <font><b/><sz val="11"/><name val="Calibri"/></font></fonts>\
      <fills count="3"><fill><patternFill patternType="none"/></fill>\
      <fill><patternFill patternType="gray125"/></fill>\
      <fill><patternFill patternType="solid"><fgColor rgb="FFE8EEF5"/><bgColor indexed="64"/></patternFill></fill></fills>\
      <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>\
      <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>\
      <cellXfs count="14">\
      <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>\
      <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>\
      <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>\
      <xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>\
      <xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>\
      <xf numFmtId="3" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>\
      <xf numFmtId="167" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>\
      <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>\
      <xf numFmtId="164" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyNumberFormat="1"/>\
      <xf numFmtId="165" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyNumberFormat="1"/>\
      <xf numFmtId="166" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyNumberFormat="1"/>\
      <xf numFmtId="3" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyNumberFormat="1"/>\
      <xf numFmtId="167" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyNumberFormat="1"/>\
      <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>\
      </cellXfs>\
      <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>\
      </styleSheet>\
      """;

  private static void escribir(ZipOutputStream zip, String nombre, String contenido)
      throws IOException {
    zip.putNextEntry(new ZipEntry(nombre));
    zip.write(contenido.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  /** Días desde el 30 de diciembre de 1899: así guarda Excel las fechas. */
  static long serial(LocalDate fecha) {
    return ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), fecha);
  }

  static String referencia(int columna, int fila) {
    StringBuilder letras = new StringBuilder();
    int n = columna + 1;
    while (n > 0) {
      int resto = (n - 1) % 26;
      letras.insert(0, (char) ('A' + resto));
      n = (n - 1) / 26;
    }
    return letras.toString() + fila;
  }

  /** Escapa XML y quita los caracteres de control que el formato no admite. */
  static String escapar(String texto) {
    StringBuilder s = new StringBuilder(texto.length());
    for (int i = 0; i < texto.length(); i++) {
      char c = texto.charAt(i);
      switch (c) {
        case '&' -> s.append("&amp;");
        case '<' -> s.append("&lt;");
        case '>' -> s.append("&gt;");
        case '"' -> s.append("&quot;");
        default -> {
          if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
            s.append(c);
          }
        }
      }
    }
    return s.toString();
  }
}

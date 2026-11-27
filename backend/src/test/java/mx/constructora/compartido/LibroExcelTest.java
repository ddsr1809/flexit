package mx.constructora.compartido;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import mx.constructora.compartido.LibroExcel.Columna;
import mx.constructora.compartido.LibroExcel.Formato;
import org.junit.jupiter.api.Test;

/** El .xlsx de los reportes: XML válido, números con formato, textos que nunca son fórmulas. */
class LibroExcelTest {

  @Test
  void escribeUnLibroValido() throws Exception {
    LibroExcel libro = new LibroExcel();
    libro
        .hoja(
            "Costo: por lote [CORCELES]",
            List.of(
                Columna.texto("Lote", 12),
                Columna.moneda("Material"),
                new Columna("Avance", Formato.PORCENTAJE, 10),
                new Columna("Fecha", Formato.FECHA, 12)))
        .fila(
            "M20-L29",
            new BigDecimal("1234.5678"),
            new BigDecimal("85.5"),
            LocalDate.of(2026, 11, 26))
        .fila("=HYPERLINK(\"x\") <&>", null, null, null)
        .totales("Total", new BigDecimal("1234.57"));
    Map<String, String> partes = descomprimir(libro.bytes());

    assertThat(partes)
        .containsKeys(
            "[Content_Types].xml",
            "_rels/.rels",
            "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels",
            "xl/styles.xml",
            "xl/worksheets/sheet1.xml");
    for (var parte : partes.entrySet()) {
      DocumentBuilderFactory.newInstance()
          .newDocumentBuilder()
          .parse(new ByteArrayInputStream(parte.getValue().getBytes(StandardCharsets.UTF_8)));
    }
    // Los caracteres no permitidos en el nombre de la hoja se cambian por espacios.
    assertThat(partes.get("xl/workbook.xml")).contains("name=\"Costo  por lote  CORCELES\"");
    String hoja = partes.get("xl/worksheets/sheet1.xml");
    assertThat(hoja).contains("<v>1234.5678</v>").contains("<v>46352</v>"); // 2026-11-26
    // El texto que empieza con "=" va como texto en línea, nunca como fórmula.
    assertThat(hoja).contains("t=\"inlineStr\"").doesNotContain("<f>");
    assertThat(hoja).contains("=HYPERLINK(&quot;x&quot;) &lt;&amp;&gt;");
    assertThat(hoja).contains("<pane ySplit=\"1\"").contains("<autoFilter ref=\"A1:D4\"/>");
  }

  @Test
  void referenciasYFechas() {
    assertThat(LibroExcel.referencia(0, 1)).isEqualTo("A1");
    assertThat(LibroExcel.referencia(25, 3)).isEqualTo("Z3");
    assertThat(LibroExcel.referencia(26, 3)).isEqualTo("AA3");
    assertThat(LibroExcel.referencia(217, 304)).isEqualTo("HJ304");
    assertThat(LibroExcel.serial(LocalDate.of(1900, 3, 1))).isEqualTo(61);
    assertThat(LibroExcel.escapar("a\u0001b")).isEqualTo("ab");
  }

  @Test
  void hojasConElMismoNombreNoChocan() throws Exception {
    LibroExcel libro = new LibroExcel();
    libro.hoja("Lotes", List.of(Columna.texto("A", 5))).fila("uno");
    libro.hoja("lotes", List.of(Columna.texto("A", 5))).fila("dos");
    String indice = descomprimir(libro.bytes()).get("xl/workbook.xml");
    assertThat(indice).contains("name=\"Lotes\"").contains("name=\"lotes 2\"");
  }

  private static Map<String, String> descomprimir(byte[] xlsx) throws Exception {
    Map<String, String> partes = new HashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
      ZipEntry entrada;
      while ((entrada = zip.getNextEntry()) != null) {
        partes.put(entrada.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return partes;
  }
}

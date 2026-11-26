package mx.constructora.importacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import mx.constructora.importacion.dominio.xlsx.ArchivoInvalido;
import mx.constructora.importacion.dominio.xlsx.Celda;
import mx.constructora.importacion.dominio.xlsx.LibroXlsx;
import mx.constructora.importacion.dominio.xlsx.Renglon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** El lector en streaming: valores exactos, fechas, fórmulas que no se leen y defensas. */
class LibroXlsxTest {

  @TempDir Path temporal;

  @Test
  void leeLoCapturadoYReconoceLasFormulas() {
    try (LibroXlsx libro = LibroXlsx.abrir(Muestras.ruta("muestra-almacen.xlsx"))) {
      assertThat(libro.hojas()).startsWith("Existencias", "Entradas", "Salidas");
      List<Renglon> renglones = new ArrayList<>();
      libro.leer("Existencias", r -> renglones.add(r) && r.numero() < 5);
      Renglon cinco = renglones.getLast();
      assertThat(cinco.numero()).isEqualTo(5);
      assertThat(cinco.capturado("E")).isEqualTo("001");
      assertThat(cinco.capturado("F")).isEqualTo("Abrazadera omega de 3/4\"");
      assertThat(cinco.esFormula("A")).isTrue();
      assertThat(cinco.capturado("A")).as("una fórmula nunca se lee como dato").isNull();
      assertThat(cinco.celda("O").tipo()).isEqualTo(Celda.Tipo.NUMERO);
    }
  }

  @Test
  void lasFechasSalenEnIsoYLosNumerosSinRedondear() {
    try (LibroXlsx libro = LibroXlsx.abrir(Muestras.ruta("muestra-sua.xlsx"))) {
      List<Renglon> renglones = new ArrayList<>();
      libro.leer("CONTRATOS ACTIVOS", r -> renglones.add(r));
      Renglon contrato = renglones.stream().filter(r -> r.numero() == 4).findFirst().orElseThrow();
      assertThat(contrato.celda("D").tipo()).isEqualTo(Celda.Tipo.FECHA);
      assertThat(contrato.capturado("D")).isEqualTo("2026-07-12");
      assertThat(contrato.capturado("G")).isEqualTo("4882033.109999998");
    }
  }

  @Test
  void leerSoloAlgunasColumnasYDetenerse() {
    try (LibroXlsx libro = LibroXlsx.abrir(Muestras.ruta("muestra-almacen.xlsx"))) {
      List<Renglon> renglones = new ArrayList<>();
      libro.leer("Salidas", java.util.Set.of("J"), r -> renglones.add(r) && renglones.size() < 2);
      assertThat(renglones).hasSize(2);
      assertThat(renglones.getLast().celdas()).containsOnlyKeys("J");
    }
  }

  @Test
  void numeroDeSerieDeExcelAFecha() {
    assertThat(fecha("46195")).isEqualTo("2026-06-22");
    assertThat(fecha("46195.5")).isEqualTo("2026-06-22T12:00");
    assertThat(fecha("0.4166666666666667")).isEqualTo("10:00:00");
    assertThat(fecha("61")).as("después del 29/02/1900 que no existió").isEqualTo("1900-03-01");
    assertThat(fecha("59")).isEqualTo("1900-02-28");
  }

  private static String fecha(String serie) {
    try {
      var metodo = LibroXlsx.class.getDeclaredMethod("fechaDeSerie", String.class, boolean.class);
      metodo.setAccessible(true);
      return (String) metodo.invoke(null, serie, false);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void unArchivoQueNoEsExcelSeRechaza() throws IOException {
    Path texto = Files.writeString(temporal.resolve("no-es.xlsx"), "hola");
    assertThatThrownBy(() -> LibroXlsx.abrir(texto)).isInstanceOf(ArchivoInvalido.class);
  }

  @Test
  void unaBombaZipSeDetieneSinLlenarLaMemoria() throws IOException {
    Path bomba = temporal.resolve("bomba.xlsx");
    try (OutputStream salida = Files.newOutputStream(bomba);
        ZipOutputStream zip = new ZipOutputStream(salida)) {
      zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
      zip.write("<workbook>".getBytes(StandardCharsets.UTF_8));
      byte[] espacios = new byte[1024 * 1024];
      java.util.Arrays.fill(espacios, (byte) ' ');
      for (int i = 0; i < 64; i++) {
        zip.write(espacios);
      }
      zip.write("</workbook>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    assertThatThrownBy(() -> LibroXlsx.abrir(bomba))
        .isInstanceOf(ArchivoInvalido.class)
        .hasMessageContaining("bomba");
  }

  @Test
  void noResuelveEntidadesExternas() throws IOException {
    Path xxe = temporal.resolve("xxe.xlsx");
    Path secreto = Files.writeString(temporal.resolve("secreto.txt"), "CONTRASENA");
    String libroXml =
        """
        <?xml version="1.0"?>
        <!DOCTYPE w [<!ENTITY x SYSTEM "%s">]>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <sheets><sheet name="&x;" sheetId="1" r:id="rId1"/></sheets>
        </workbook>
        """
            .formatted(secreto.toUri());
    try (OutputStream salida = Files.newOutputStream(xxe);
        ZipOutputStream zip = new ZipOutputStream(salida)) {
      zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
      zip.write(libroXml.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    try (LibroXlsx libro = LibroXlsx.abrir(xxe)) {
      assertThat(libro.hojas()).noneMatch(h -> h.contains("CONTRASENA"));
    } catch (ArchivoInvalido e) {
      assertThat(e.getMessage()).doesNotContain("CONTRASENA");
    }
  }
}

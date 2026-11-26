package mx.constructora.inventario.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static mx.constructora.inventario.aplicacion.Sesion.CONSULTAR;
import static mx.constructora.inventario.aplicacion.Sesion.empresa;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.constructora.catalogo.CatalogoApi.MaterialInfo;
import mx.constructora.compartido.Autorizacion;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Permisos;
import mx.constructora.inventario.infraestructura.RepositorioSaldos;
import mx.constructora.inventario.infraestructura.RepositorioSaldos.FilaKardex;
import mx.constructora.inventario.infraestructura.RepositorioSaldos.FilaSaldo;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Existencias por almacén y kardex por material. Las cantidades las ve todo el que consulta
 * existencias; el costo promedio y el valor, solo quien ve precios o todos los costos.
 */
@Service
public class ServicioExistencias {

  /** Renglones del kardex por consulta, como máximo. */
  public static final int KARDEX_MAXIMO = 1000;

  /** Existencia de un material en un almacén. */
  public record Existencia(
      @Schema(requiredMode = REQUIRED) long materialId,
      @Schema(requiredMode = REQUIRED) String clave,
      @Schema(requiredMode = REQUIRED) String material,
      @Schema(requiredMode = REQUIRED) String unidad,
      @Schema(requiredMode = REQUIRED) BigDecimal existencia,
      @Schema(requiredMode = REQUIRED) BigDecimal reservado,
      @Schema(requiredMode = REQUIRED) BigDecimal prestado,
      @Schema(requiredMode = REQUIRED) BigDecimal disponible,
      @Schema(description = "Sin IVA; solo para quien ve costos") BigDecimal costoPromedio,
      @Schema(description = "Solo para quien ve costos") BigDecimal valor,
      @Schema(requiredMode = REQUIRED) OffsetDateTime actualizado) {}

  /** Renglón del kardex. */
  public record RenglonKardex(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) long almacenId,
      @Schema(requiredMode = REQUIRED) String almacen,
      @Schema(requiredMode = REQUIRED) long movimientoId,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(requiredMode = REQUIRED) String tipo,
      @Schema(requiredMode = REQUIRED) LocalDate fecha,
      @Schema(requiredMode = REQUIRED, description = "Con signo: + entra, − sale")
          BigDecimal cantidad,
      BigDecimal costoUnitario,
      BigDecimal importe,
      @Schema(requiredMode = REQUIRED) BigDecimal existencia,
      BigDecimal valor,
      BigDecimal costoPromedio,
      @Schema(requiredMode = REQUIRED) OffsetDateTime registrado) {}

  private final RepositorioSaldos saldos;
  private final ServicioAlmacenes almacenes;
  private final Nombres nombres;

  public ServicioExistencias(
      RepositorioSaldos saldos, ServicioAlmacenes almacenes, Nombres nombres) {
    this.saldos = saldos;
    this.almacenes = almacenes;
    this.nombres = nombres;
  }

  /**
   * Existencias del almacén, en orden de nombre.
   *
   * @param texto palabras del nombre o la clave exacta; vacío: todos
   */
  @PreAuthorize(CONSULTAR)
  @Transactional(readOnly = true)
  public List<Existencia> existencias(long almacenId, String texto, boolean soloConExistencia) {
    almacenes.existente(almacenId);
    List<FilaSaldo> filas = saldos.saldos(empresa(), almacenId, null, soloConExistencia);
    Map<Long, MaterialInfo> materiales =
        nombres.materiales(filas.stream().map(FilaSaldo::materialId).toList());
    boolean costos = veCostos();
    String[] palabras = palabras(texto);
    return filas.stream()
        .filter(f -> materiales.containsKey(f.materialId()))
        .filter(f -> coincide(materiales.get(f.materialId()), palabras, texto))
        .map(
            f -> {
              MaterialInfo m = materiales.get(f.materialId());
              return new Existencia(
                  f.materialId(),
                  m.clave(),
                  m.nombre(),
                  m.unidadControl(),
                  f.existencia(),
                  f.reservado(),
                  f.prestado(),
                  f.disponible(),
                  costos ? f.costoPromedio() : null,
                  costos ? f.valor() : null,
                  f.actualizado());
            })
        .sorted(Comparator.comparing(Existencia::material, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  /** Kardex de un material (todos los almacenes o uno), del más reciente al más antiguo. */
  @PreAuthorize(CONSULTAR)
  @Transactional(readOnly = true)
  public List<RenglonKardex> kardex(
      long materialId, Long almacenId, LocalDate desde, LocalDate hasta) {
    if (nombres.materiales(List.of(materialId)).isEmpty()) {
      throw ErrorNegocio.noEncontrado("MATERIAL_NO_ENCONTRADO", "No existe el material");
    }
    if (almacenId != null) {
      almacenes.existente(almacenId);
    }
    Map<Long, String> claves = nombres.almacenes();
    boolean costos = veCostos();
    return saldos.kardex(empresa(), materialId, almacenId, desde, hasta, KARDEX_MAXIMO).stream()
        .map(
            (FilaKardex k) ->
                new RenglonKardex(
                    k.id(),
                    k.almacenId(),
                    claves.getOrDefault(k.almacenId(), ""),
                    k.movimientoId(),
                    k.folio(),
                    k.tipo(),
                    k.fecha(),
                    k.cantidad(),
                    costos ? k.costoUnitario() : null,
                    costos ? k.importe() : null,
                    k.existenciaDespues(),
                    costos ? k.valorDespues() : null,
                    costos ? k.costoPromedioDespues() : null,
                    k.registrado()))
        .toList();
  }

  private static boolean veCostos() {
    return Autorizacion.tieneAlguno(Permisos.PRECIO_VER, Permisos.COSTO_VER_TODO);
  }

  private static boolean coincide(MaterialInfo m, String[] palabras, String texto) {
    if (palabras.length == 0) {
      return true;
    }
    if (m.clave().equalsIgnoreCase(texto.strip())) {
      return true;
    }
    String nombre = normal(m.nombre());
    return Arrays.stream(palabras).allMatch(nombre::contains);
  }

  private static String[] palabras(String texto) {
    String limpio = texto == null ? "" : normal(texto);
    return limpio.isEmpty() ? new String[0] : limpio.split(" ");
  }

  /** Como {@code compartido.normalizar} de la base: sin acentos, mayúsculas, espacios simples. */
  static String normal(String texto) {
    String sinAcentos = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return sinAcentos.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
  }
}

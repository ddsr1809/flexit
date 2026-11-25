package mx.constructora.catalogo.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;
import mx.constructora.catalogo.infraestructura.RepositorioCatalogo;
import mx.constructora.catalogo.infraestructura.RepositorioMateriales;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Permisos;
import mx.constructora.compartido.Textos;
import mx.constructora.compartido.Versiones;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unidades de medida, conversiones, categorías y clasificaciones de costo. Las unidades y
 * conversiones del sistema las ve todo mundo y nadie las cambia desde la app; cada empresa agrega
 * las suyas. Escribir requiere {@code catalogo:editar} (compras y administración).
 */
@Service
public class ServicioCatalogo {

  static final String EDITAR = "hasAuthority('" + Permisos.CATALOGO_EDITAR + "')";

  /** Unidad de medida. {@code delSistema}: viene con el sistema y no se puede cambiar. */
  public record UnidadMedida(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String clave,
      @Schema(requiredMode = REQUIRED) String nombre,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"conteo", "masa", "volumen", "longitud", "superficie"})
          String magnitud,
      @Schema(requiredMode = REQUIRED) boolean fraccionable,
      @Schema(requiredMode = REQUIRED) boolean delSistema,
      @Schema(requiredMode = REQUIRED) boolean activa,
      @Schema(requiredMode = REQUIRED) int version) {}

  /** Alta o cambio de una unidad de medida propia de la empresa. */
  public record DatosUnidadMedida(
      @NotBlank
          @Pattern(regexp = "^\\s*[A-Za-z0-9]{1,6}\\s*$", message = "De 1 a 6 letras o números")
          String clave,
      @NotBlank @Size(max = 40) String nombre,
      @NotBlank @Pattern(regexp = "conteo|masa|volumen|longitud|superficie") String magnitud,
      Boolean fraccionable,
      Boolean activa) {}

  /** 1 {@code origen} = {@code factor} × {@code destino}. Con material: solo para ese material. */
  public record Conversion(
      @Schema(requiredMode = REQUIRED) long id,
      Long materialId,
      @Schema(requiredMode = REQUIRED) long origenId,
      @Schema(requiredMode = REQUIRED) String origen,
      @Schema(requiredMode = REQUIRED) long destinoId,
      @Schema(requiredMode = REQUIRED) String destino,
      @Schema(requiredMode = REQUIRED) BigDecimal factor,
      @Schema(requiredMode = REQUIRED) boolean delSistema,
      @Schema(requiredMode = REQUIRED) int version) {}

  /**
   * Alta de conversión. La de un material va siempre hacia su unidad de control (1 CJA = 4 PZA).
   */
  public record DatosConversion(
      Long materialId,
      @NotNull Long origenId,
      @NotNull Long destinoId,
      @NotNull @Positive @Digits(integer = 10, fraction = 8) BigDecimal factor) {}

  /** Cambio del factor de una conversión propia. */
  public record DatosFactor(
      @NotNull @Positive @Digits(integer = 10, fraction = 8) BigDecimal factor) {}

  /** Categoría de material. */
  public record Categoria(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String nombre,
      @Schema(requiredMode = REQUIRED) boolean activa,
      @Schema(requiredMode = REQUIRED) int version) {}

  /** Alta o cambio de categoría. */
  public record DatosCategoria(@NotBlank @Size(max = 80) String nombre, Boolean activa) {}

  /** Razón de una salida: Programa, Garantía, Retrabajo… (catálogo del sistema). */
  public record ClasificacionCosto(
      @Schema(requiredMode = REQUIRED) int id,
      @Schema(requiredMode = REQUIRED) String clave,
      @Schema(requiredMode = REQUIRED) String nombre,
      @Schema(requiredMode = REQUIRED) boolean consumePrograma) {}

  private final RepositorioCatalogo repositorio;
  private final RepositorioMateriales materiales;

  public ServicioCatalogo(RepositorioCatalogo repositorio, RepositorioMateriales materiales) {
    this.repositorio = repositorio;
    this.materiales = materiales;
  }

  // ------------------------------------------------------------------------------- Unidades

  @Transactional(readOnly = true)
  public List<UnidadMedida> unidades() {
    return repositorio.unidades(empresa());
  }

  @PreAuthorize(EDITAR)
  @Transactional
  public UnidadMedida crearUnidad(DatosUnidadMedida datos) {
    String clave = Textos.mayusculas(datos.clave());
    if (repositorio.unidades(empresa()).stream().anyMatch(u -> u.clave().equals(clave))) {
      throw ErrorNegocio.conflicto("UNIDAD_MEDIDA_DUPLICADA", "Ya existe la unidad " + clave);
    }
    long id =
        unico(
            () ->
                repositorio.insertarUnidad(
                    empresa(),
                    clave,
                    Textos.limpio(datos.nombre()),
                    datos.magnitud(),
                    Boolean.TRUE.equals(datos.fraccionable())),
            "UNIDAD_MEDIDA_DUPLICADA",
            "Ya existe la unidad " + clave);
    return repositorio.unidad(empresa(), id).orElseThrow();
  }

  @PreAuthorize(EDITAR)
  @Transactional
  public UnidadMedida cambiarUnidad(long id, int version, DatosUnidadMedida datos) {
    UnidadMedida actual =
        repositorio
            .unidad(empresa(), id)
            .orElseThrow(
                () ->
                    ErrorNegocio.noEncontrado(
                        "UNIDAD_MEDIDA_NO_ENCONTRADA", "No existe la unidad de medida"));
    if (actual.delSistema()) {
      throw ErrorNegocio.conflicto(
          "UNIDAD_DEL_SISTEMA", "Las unidades del sistema no se modifican");
    }
    String clave = Textos.mayusculas(datos.clave());
    exigirActualizado(
        unico(
            () ->
                repositorio.actualizarUnidad(
                    empresa(),
                    id,
                    version,
                    clave,
                    Textos.limpio(datos.nombre()),
                    datos.magnitud(),
                    datos.fraccionable() == null ? actual.fraccionable() : datos.fraccionable(),
                    datos.activa() == null ? actual.activa() : datos.activa()),
            "UNIDAD_MEDIDA_DUPLICADA",
            "Ya existe la unidad " + clave));
    return repositorio.unidad(empresa(), id).orElseThrow();
  }

  // ------------------------------------------------------------------------------- Conversiones

  /** Conversiones generales y, si se pide, las de un material. */
  @Transactional(readOnly = true)
  public List<Conversion> conversiones(Long materialId) {
    return repositorio.conversiones(empresa(), materialId);
  }

  @PreAuthorize(EDITAR)
  @Transactional
  public Conversion crearConversion(DatosConversion datos) {
    UnidadMedida origen = unidadVisible(datos.origenId());
    UnidadMedida destino = unidadVisible(datos.destinoId());
    if (origen.id() == destino.id()) {
      throw ErrorNegocio.invalido(
          "CONVERSION_MISMA_UNIDAD", "Origen y destino son la misma unidad");
    }
    if (datos.materialId() != null) {
      var material =
          materiales
              .material(empresa(), datos.materialId())
              .orElseThrow(
                  () -> ErrorNegocio.invalido("MATERIAL_NO_ENCONTRADO", "No existe el material"));
      if (material.unidadControlId() != destino.id()) {
        throw ErrorNegocio.invalido(
            "CONVERSION_A_UNIDAD_CONTROL",
            "La conversión de un material va hacia su unidad de control ("
                + material.unidadControl()
                + ")");
      }
    } else if (!origen.magnitud().equals(destino.magnitud())) {
      throw ErrorNegocio.invalido(
          "CONVERSION_DISTINTA_MAGNITUD",
          "Una conversión general solo une unidades de la misma magnitud; para caja → pieza"
              + " o saco → kilo, hazla en el material");
    }
    long id =
        unico(
            () ->
                repositorio.insertarConversion(
                    empresa(), datos.materialId(), origen.id(), destino.id(), datos.factor()),
            "CONVERSION_DUPLICADA",
            "Ya existe esa conversión");
    return repositorio.conversion(empresa(), id).orElseThrow();
  }

  @PreAuthorize(EDITAR)
  @Transactional
  public Conversion cambiarConversion(long id, int version, DatosFactor datos) {
    Conversion actual =
        repositorio
            .conversion(empresa(), id)
            .orElseThrow(
                () ->
                    ErrorNegocio.noEncontrado(
                        "CONVERSION_NO_ENCONTRADA", "No existe la conversión"));
    if (actual.delSistema()) {
      throw ErrorNegocio.conflicto(
          "CONVERSION_DEL_SISTEMA", "Las conversiones del sistema no se modifican");
    }
    exigirActualizado(repositorio.actualizarFactor(empresa(), id, version, datos.factor()));
    return repositorio.conversion(empresa(), id).orElseThrow();
  }

  // ------------------------------------------------------------------------------- Categorías

  @Transactional(readOnly = true)
  public List<Categoria> categorias() {
    return repositorio.categorias(empresa());
  }

  @PreAuthorize(EDITAR)
  @Transactional
  public Categoria crearCategoria(DatosCategoria datos) {
    long id =
        unico(
            () -> repositorio.insertarCategoria(empresa(), Textos.limpio(datos.nombre())),
            "CATEGORIA_DUPLICADA",
            "Ya existe esa categoría");
    return repositorio.categoria(empresa(), id).orElseThrow();
  }

  @PreAuthorize(EDITAR)
  @Transactional
  public Categoria cambiarCategoria(long id, int version, DatosCategoria datos) {
    Categoria actual =
        repositorio
            .categoria(empresa(), id)
            .orElseThrow(
                () ->
                    ErrorNegocio.noEncontrado("CATEGORIA_NO_ENCONTRADA", "No existe la categoría"));
    exigirActualizado(
        unico(
            () ->
                repositorio.actualizarCategoria(
                    empresa(),
                    id,
                    version,
                    Textos.limpio(datos.nombre()),
                    datos.activa() == null ? actual.activa() : datos.activa()),
            "CATEGORIA_DUPLICADA",
            "Ya existe esa categoría"));
    return repositorio.categoria(empresa(), id).orElseThrow();
  }

  @Transactional(readOnly = true)
  public List<ClasificacionCosto> clasificaciones() {
    return repositorio.clasificaciones();
  }

  // ------------------------------------------------------------------------------- Apoyo

  UnidadMedida unidadVisible(long id) {
    return repositorio
        .unidad(empresa(), id)
        .orElseThrow(
            () ->
                ErrorNegocio.invalido(
                    "UNIDAD_MEDIDA_NO_ENCONTRADA", "No existe la unidad de medida"));
  }

  static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }

  static void exigirActualizado(int filas) {
    if (filas == 0) {
      throw Versiones.desactualizada();
    }
  }

  static <T> T unico(Supplier<T> escritura, String codigo, String mensaje) {
    try {
      return escritura.get();
    } catch (DuplicateKeyException e) {
      throw ErrorNegocio.conflicto(codigo, mensaje);
    }
  }
}

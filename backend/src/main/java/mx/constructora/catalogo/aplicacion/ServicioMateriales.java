package mx.constructora.catalogo.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static mx.constructora.catalogo.aplicacion.ServicioCatalogo.EDITAR;
import static mx.constructora.catalogo.aplicacion.ServicioCatalogo.empresa;
import static mx.constructora.catalogo.aplicacion.ServicioCatalogo.exigirActualizado;
import static mx.constructora.catalogo.aplicacion.ServicioCatalogo.unico;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import mx.constructora.catalogo.infraestructura.RepositorioCatalogo;
import mx.constructora.catalogo.infraestructura.RepositorioMateriales;
import mx.constructora.compartido.Autorizacion;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Permisos;
import mx.constructora.compartido.Textos;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materiales. La búsqueda es por clave exacta o por palabras sueltas del nombre, sin importar
 * acentos ni mayúsculas: {@code "cement gris"} encuentra {@code Cemento gris 50 kg}. El precio de
 * referencia solo lo ve quien ve precios o costos.
 */
@Service
public class ServicioMateriales {

  /** Tamaño máximo de página (regla de la API). */
  public static final int PAGINA_MAXIMA = 200;

  /** Resultados de la búsqueda rápida (autocompletado). */
  public static final int SUGERENCIAS = 20;

  /** Material con su categoría y unidad de control. */
  public record Material(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String clave,
      @Schema(requiredMode = REQUIRED) String nombre,
      Long categoriaId,
      String categoria,
      @Schema(requiredMode = REQUIRED) long unidadControlId,
      @Schema(requiredMode = REQUIRED) String unidadControl,
      @Schema(description = "Sin IVA. Solo para quien ve precios o costos")
          BigDecimal precioReferencia,
      @Schema(requiredMode = REQUIRED) boolean activo,
      @Schema(requiredMode = REQUIRED) int version) {

    Material sinPrecio() {
      return new Material(
          id,
          clave,
          nombre,
          categoriaId,
          categoria,
          unidadControlId,
          unidadControl,
          null,
          activo,
          version);
    }
  }

  /** Una página de materiales. */
  public record PaginaMateriales(
      @Schema(requiredMode = REQUIRED) List<Material> elementos,
      @Schema(requiredMode = REQUIRED) long total,
      @Schema(requiredMode = REQUIRED) int pagina,
      @Schema(requiredMode = REQUIRED) int tamano) {}

  /** Alta o cambio de material. Sin clave, se asigna la siguiente numérica (001, 002…). */
  public record DatosMaterial(
      @Pattern(
              regexp = "^\\s*$|^\\s*[A-Za-z0-9][A-Za-z0-9-]{0,19}\\s*$",
              message = "Hasta 20 letras, números o guiones")
          String clave,
      @NotBlank @Size(max = 200) String nombre,
      Long categoriaId,
      @NotNull Long unidadControlId,
      @PositiveOrZero @Digits(integer = 10, fraction = 6) BigDecimal precioReferencia,
      Boolean activo) {}

  private final RepositorioMateriales repositorio;
  private final RepositorioCatalogo catalogo;

  public ServicioMateriales(RepositorioMateriales repositorio, RepositorioCatalogo catalogo) {
    this.repositorio = repositorio;
    this.catalogo = catalogo;
  }

  /**
   * Materiales filtrados y paginados.
   *
   * @param texto clave exacta o palabras del nombre; vacío: todos
   * @param pagina desde 0
   */
  @Transactional(readOnly = true)
  public PaginaMateriales materiales(
      String texto, Long categoriaId, boolean soloActivos, int pagina, int tamano) {
    int tamanoValido = Math.clamp(tamano, 1, PAGINA_MAXIMA);
    int paginaValida = Math.max(pagina, 0);
    String busqueda = Textos.limpio(texto);
    List<Material> elementos =
        repositorio.buscar(
            empresa(),
            busqueda,
            categoriaId,
            soloActivos,
            tamanoValido,
            (long) paginaValida * tamanoValido);
    long total = repositorio.contar(empresa(), busqueda, categoriaId, soloActivos);
    return new PaginaMateriales(
        elementos.stream().map(ServicioMateriales::segunPermiso).toList(),
        total,
        paginaValida,
        tamanoValido);
  }

  /** Búsqueda rápida para capturar: los 20 materiales activos que mejor coinciden. */
  @Transactional(readOnly = true)
  public List<Material> sugerencias(String texto) {
    String busqueda = Textos.limpio(texto);
    if (busqueda == null) {
      return List.of();
    }
    return repositorio.buscar(empresa(), busqueda, null, true, SUGERENCIAS, 0).stream()
        .map(ServicioMateriales::segunPermiso)
        .toList();
  }

  @Transactional(readOnly = true)
  public Material material(long id) {
    return segunPermiso(existente(id));
  }

  @PreAuthorize(EDITAR)
  @Transactional
  public Material crear(DatosMaterial datos) {
    validar(datos);
    String clave = Textos.mayusculas(datos.clave());
    String claveFinal = clave == null ? repositorio.siguienteClave(empresa()) : clave;
    long id =
        unico(
            () ->
                repositorio.insertar(
                    empresa(),
                    claveFinal,
                    Textos.limpio(datos.nombre()),
                    datos.categoriaId(),
                    datos.unidadControlId(),
                    datos.precioReferencia()),
            "MATERIAL_DUPLICADO",
            "Ya existe un material con esa clave o ese nombre");
    return repositorio.material(empresa(), id).orElseThrow();
  }

  @PreAuthorize(EDITAR)
  @Transactional
  public Material cambiar(long id, int version, DatosMaterial datos) {
    Material actual = existente(id);
    validar(datos);
    String clave = Textos.mayusculas(datos.clave());
    exigirActualizado(
        unico(
            () ->
                repositorio.actualizar(
                    empresa(),
                    id,
                    version,
                    clave == null ? actual.clave() : clave,
                    Textos.limpio(datos.nombre()),
                    datos.categoriaId(),
                    datos.unidadControlId(),
                    datos.precioReferencia(),
                    datos.activo() == null ? actual.activo() : datos.activo()),
            "MATERIAL_DUPLICADO",
            "Ya existe un material con esa clave o ese nombre"));
    return repositorio.material(empresa(), id).orElseThrow();
  }

  private void validar(DatosMaterial datos) {
    if (catalogo.unidad(empresa(), datos.unidadControlId()).isEmpty()) {
      throw ErrorNegocio.invalido(
          "UNIDAD_MEDIDA_NO_ENCONTRADA", "No existe la unidad de medida");
    }
    if (datos.categoriaId() != null
        && catalogo.categoria(empresa(), datos.categoriaId()).isEmpty()) {
      throw ErrorNegocio.invalido("CATEGORIA_NO_ENCONTRADA", "No existe la categoría");
    }
  }

  private Material existente(long id) {
    return repositorio
        .material(empresa(), id)
        .orElseThrow(
            () -> ErrorNegocio.noEncontrado("MATERIAL_NO_ENCONTRADO", "No existe el material"));
  }

  private static Material segunPermiso(Material material) {
    return Autorizacion.tieneAlguno(
            Permisos.PRECIO_VER, Permisos.COSTO_VER_TODO, Permisos.CATALOGO_EDITAR)
        ? material
        : material.sinPrecio();
  }
}

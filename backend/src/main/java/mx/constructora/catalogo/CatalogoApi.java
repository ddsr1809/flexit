package mx.constructora.catalogo;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import mx.constructora.catalogo.infraestructura.RepositorioCatalogo;
import mx.constructora.catalogo.infraestructura.RepositorioMateriales;
import mx.constructora.catalogo.infraestructura.RepositorioProveedores;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.ErrorNegocio;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lo que otros módulos (inventario, abastecimiento, importación) consultan del catálogo. */
@Service
public class CatalogoApi {

  /** Material con su unidad de control y su precio de referencia (sin IVA). */
  public record MaterialInfo(
      long id,
      String clave,
      String nombre,
      long unidadControlId,
      String unidadControl,
      BigDecimal precioReferencia,
      boolean activo) {}

  /** Unidad de medida: id y clave. */
  public record UnidadInfo(long id, String clave, boolean fraccionable) {}

  /** Proveedor con su tasa de IVA por defecto. */
  public record ProveedorInfo(long id, String nombre, BigDecimal tasaIva, boolean activo) {}

  /** Clasificación de costo (la "Razón" de una salida). */
  public record ClasificacionInfo(int id, String clave, String nombre) {}

  private final RepositorioMateriales materiales;
  private final RepositorioCatalogo catalogo;
  private final RepositorioProveedores proveedores;

  CatalogoApi(
      RepositorioMateriales materiales,
      RepositorioCatalogo catalogo,
      RepositorioProveedores proveedores) {
    this.materiales = materiales;
    this.catalogo = catalogo;
    this.proveedores = proveedores;
  }

  @Transactional(readOnly = true)
  public Optional<MaterialInfo> material(long id) {
    return materiales(List.of(id)).stream().findFirst();
  }

  /** Los materiales de la empresa en curso con esos ids; los que no existen se omiten. */
  @Transactional(readOnly = true)
  public List<MaterialInfo> materiales(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return materiales.infos(empresa(), ids);
  }

  /** Unidades de medida visibles (del sistema y de la empresa). */
  @Transactional(readOnly = true)
  public List<UnidadInfo> unidades() {
    return catalogo.unidades(empresa()).stream()
        .map(u -> new UnidadInfo(u.id(), u.clave(), u.fraccionable()))
        .toList();
  }

  @Transactional(readOnly = true)
  public Optional<ProveedorInfo> proveedor(long id) {
    return proveedores
        .proveedor(empresa(), id)
        .map(p -> new ProveedorInfo(p.id(), p.nombre(), p.tasaIva(), p.activo()));
  }

  /** Los proveedores de la empresa en curso con esos ids. */
  @Transactional(readOnly = true)
  public List<ProveedorInfo> proveedores(Collection<Long> ids) {
    return ids.stream().distinct().map(this::proveedor).flatMap(Optional::stream).toList();
  }

  @Transactional(readOnly = true)
  public List<ClasificacionInfo> clasificaciones() {
    return catalogo.clasificaciones().stream()
        .map(c -> new ClasificacionInfo(c.id(), c.clave(), c.nombre()))
        .toList();
  }

  /**
   * Pasa {@code cantidad} capturada en {@code unidadId} a la unidad de control del material.
   *
   * @throws ErrorNegocio {@code SIN_CONVERSION} si no hay conversión de esa unidad
   */
  @Transactional(readOnly = true)
  public BigDecimal aUnidadControl(long materialId, long unidadId, BigDecimal cantidad) {
    MaterialInfo material =
        material(materialId)
            .orElseThrow(
                () -> ErrorNegocio.invalido("MATERIAL_NO_ENCONTRADO", "No existe el material"));
    var reglas =
        catalogo.conversiones(empresa(), materialId).stream()
            .map(
                c ->
                    new Conversiones.Regla(c.materialId(), c.origenId(), c.destinoId(), c.factor()))
            .toList();
    BigDecimal factor =
        Conversiones.factor(reglas, materialId, unidadId, material.unidadControlId())
            .orElseThrow(
                () ->
                    ErrorNegocio.invalido(
                        "SIN_CONVERSION",
                        material.nombre()
                            + " no tiene conversión a "
                            + material.unidadControl()
                            + " desde esa unidad"));
    return Conversiones.convertir(cantidad, factor);
  }

  private static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }
}

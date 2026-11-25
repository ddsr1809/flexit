package mx.constructora.catalogo;

import java.math.BigDecimal;
import java.util.Optional;
import mx.constructora.catalogo.infraestructura.RepositorioCatalogo;
import mx.constructora.catalogo.infraestructura.RepositorioMateriales;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.ErrorNegocio;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lo que otros módulos (inventario, abastecimiento, importación) consultan del catálogo. */
@Service
public class CatalogoApi {

  /** Material con su unidad de control. */
  public record MaterialInfo(
      long id,
      String clave,
      String nombre,
      long unidadControlId,
      String unidadControl,
      boolean activo) {}

  private final RepositorioMateriales materiales;
  private final RepositorioCatalogo catalogo;

  CatalogoApi(RepositorioMateriales materiales, RepositorioCatalogo catalogo) {
    this.materiales = materiales;
    this.catalogo = catalogo;
  }

  @Transactional(readOnly = true)
  public Optional<MaterialInfo> material(long id) {
    return materiales
        .material(empresa(), id)
        .map(
            m ->
                new MaterialInfo(
                    m.id(),
                    m.clave(),
                    m.nombre(),
                    m.unidadControlId(),
                    m.unidadControl(),
                    m.activo()));
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

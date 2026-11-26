package mx.constructora.catalogo;

import java.math.BigDecimal;
import java.util.List;
import mx.constructora.catalogo.infraestructura.RepositorioImportacionCatalogo;
import mx.constructora.catalogo.infraestructura.RepositorioMateriales;
import mx.constructora.compartido.ContextoEmpresa;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que la migración desde Excel lee y escribe del catálogo. Lo creado queda marcado con la carga
 * ({@code origen_carga}) para poder revertirla. No revisa permisos: lo llama solo el servicio de
 * importación, que exige {@code sistema:administrar}.
 */
@Service
public class ImportacionCatalogo {

  public record UnidadDato(long id, String clave, String nombre) {}

  public record MaterialDato(long id, String clave, String nombre, String nombreNormal) {}

  public record ProveedorDato(long id, String nombre, String nombreNormal) {}

  private final RepositorioImportacionCatalogo repositorio;
  private final RepositorioMateriales materiales;

  ImportacionCatalogo(
      RepositorioImportacionCatalogo repositorio, RepositorioMateriales materiales) {
    this.repositorio = repositorio;
    this.materiales = materiales;
  }

  /** Unidades del sistema y de la empresa. */
  @Transactional(readOnly = true)
  public List<UnidadDato> unidades() {
    return repositorio.unidades(empresa());
  }

  @Transactional(readOnly = true)
  public List<MaterialDato> materiales() {
    return repositorio.materiales(empresa());
  }

  @Transactional(readOnly = true)
  public List<ProveedorDato> proveedores() {
    return repositorio.proveedores(empresa());
  }

  /**
   * @param clave {@code null}: la siguiente numérica (001, 002…)
   */
  @Transactional
  public long crearMaterial(
      long carga, String clave, String nombre, long unidadControlId, BigDecimal precio) {
    String claveFinal = clave == null ? materiales.siguienteClave(empresa()) : clave;
    return repositorio.insertarMaterial(
        empresa(), carga, claveFinal, nombre, unidadControlId, precio);
  }

  @Transactional
  public long crearProveedor(long carga, String nombre, BigDecimal tasaIva) {
    return repositorio.insertarProveedor(empresa(), carga, nombre, tasaIva);
  }

  /**
   * Borra lo que creó la carga.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException si algo ya los usa
   */
  @Transactional
  public int revertirCarga(long carga) {
    return repositorio.revertir(carga);
  }

  private static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }
}

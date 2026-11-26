package mx.constructora.estructura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.estructura.infraestructura.RepositorioImportacionEstructura;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que la migración desde Excel (módulo importacion) lee y escribe de la estructura. Todo lo que
 * crea queda marcado con la carga ({@code origen_carga}) para poder revertirla completa.
 *
 * <p>No revisa permisos: lo llama solo el servicio de importación, que exige {@code
 * sistema:administrar}. Las reglas de la base (un lote por frente, FK compuestas, RLS) aplican
 * igual que en la captura a mano.
 */
@Service
public class ImportacionEstructura {

  public record FrenteDato(
      long id, long clienteId, String clave, String nombre, String nombreNormal) {}

  public record ContratoDato(long id, String codigo, Long frenteId, long clienteId) {}

  public record PrototipoDato(long id, String clave) {}

  public record UnidadDato(
      long id, Long frenteId, long contratoId, String clave, Long prototipoId) {}

  public record PaqueteDato(
      long id,
      Long clienteId,
      Long padreId,
      String clave,
      String nombre,
      String nombreNormal,
      int orden) {}

  public record EquivalenciaDato(long paqueteClienteId, long paquetePropioId) {}

  public record PrecioDato(long contratoId, long paqueteId, Long prototipoId) {}

  private final RepositorioImportacionEstructura repositorio;

  ImportacionEstructura(RepositorioImportacionEstructura repositorio) {
    this.repositorio = repositorio;
  }

  @Transactional(readOnly = true)
  public boolean existeCliente(long id) {
    return repositorio.existe(empresa(), "cliente", id);
  }

  @Transactional(readOnly = true)
  public List<FrenteDato> frentes() {
    return repositorio.frentes(empresa());
  }

  @Transactional(readOnly = true)
  public List<ContratoDato> contratos() {
    return repositorio.contratos(empresa());
  }

  @Transactional(readOnly = true)
  public List<PrototipoDato> prototipos() {
    return repositorio.prototipos(empresa());
  }

  @Transactional(readOnly = true)
  public List<UnidadDato> unidades() {
    return repositorio.unidades(empresa());
  }

  @Transactional(readOnly = true)
  public List<PaqueteDato> paquetes() {
    return repositorio.paquetes(empresa());
  }

  @Transactional(readOnly = true)
  public List<EquivalenciaDato> equivalencias() {
    return repositorio.equivalencias(empresa());
  }

  @Transactional(readOnly = true)
  public List<PrecioDato> precios() {
    return repositorio.precios(empresa());
  }

  @Transactional
  public long crearFrente(long carga, long clienteId, String clave, String nombre) {
    return repositorio.insertarFrente(empresa(), carga, clienteId, clave, nombre);
  }

  @Transactional
  public long crearContrato(
      long carga,
      long clienteId,
      Long frenteId,
      String codigo,
      String numeroObra,
      LocalDate inicio,
      LocalDate fin,
      BigDecimal monto) {
    String normalizado =
        CodigoContrato.normalizar(codigo)
            .orElseThrow(() -> new IllegalArgumentException("Código inválido: " + codigo));
    return repositorio.insertarContrato(
        empresa(), carga, clienteId, frenteId, normalizado, numeroObra, inicio, fin, monto);
  }

  @Transactional
  public long crearPrototipo(long carga, String clave, String nombre) {
    return repositorio.insertarPrototipo(empresa(), carga, clave, nombre);
  }

  /** Un lote (tipo LOTE) con clave {@code M16-L34}. */
  @Transactional
  public long crearLote(
      long carga, long contratoId, Long frenteId, String manzana, String lote, Long prototipoId) {
    ClaveUnidad clave = new ClaveUnidad(manzana, lote);
    return repositorio.insertarLote(
        empresa(),
        carga,
        contratoId,
        frenteId,
        clave.clave(),
        clave.manzana(),
        clave.lote(),
        prototipoId);
  }

  @Transactional
  public long crearPaquete(
      long carga,
      Long clienteId,
      Long padreId,
      String clave,
      String nombre,
      String unidad,
      int orden) {
    return repositorio.insertarPaquete(
        empresa(), carga, clienteId, padreId, clave, nombre, unidad, orden);
  }

  @Transactional
  public void agregarEquivalencia(long carga, long paqueteClienteId, long paquetePropioId) {
    repositorio.insertarEquivalencia(empresa(), carga, paqueteClienteId, paquetePropioId);
  }

  /** Alta del precio; si ya hay uno para ese contrato, paquete y prototipo, no lo cambia. */
  @Transactional
  public void fijarPrecio(
      long carga,
      long contratoId,
      long paqueteId,
      Long prototipoId,
      BigDecimal cobro,
      BigDecimal destajo) {
    repositorio.insertarPrecio(
        empresa(), carga, contratoId, paqueteId, prototipoId, cobro, destajo);
  }

  /**
   * Borra lo que creó la carga.
   *
   * @return cuántos registros borró
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

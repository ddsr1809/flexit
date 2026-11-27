package mx.constructora.estructura;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.estructura.aplicacion.Alcance;
import mx.constructora.estructura.infraestructura.RepositorioClientes;
import mx.constructora.estructura.infraestructura.RepositorioContratos;
import mx.constructora.estructura.infraestructura.RepositorioPaquetes;
import mx.constructora.estructura.infraestructura.RepositorioUnidades;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que otros módulos (inventario en H4, importación en H3) consultan de la estructura sin tocar
 * sus tablas. Las consultas por id no aplican el alcance del residente: quien las usa decide con
 * {@link #contratoVisible} o {@link #contratosVisibles} qué puede ver el usuario.
 */
@Service
public class EstructuraApi {

  /** Unidad de obra con su contrato y frente: a dónde se carga un costo. */
  public record UnidadInfo(
      long id, long contratoId, Long frenteId, String clave, Long prototipoId, boolean activa) {}

  /** Contrato: código, frente y estado ({@code activo}, {@code terminado}, {@code cancelado}). */
  public record ContratoInfo(long id, String codigo, Long frenteId, String estado) {

    public boolean activo() {
      return "activo".equals(estado);
    }
  }

  /** Paquete; {@code clienteId} nulo si es del catálogo propio. */
  public record PaqueteInfo(long id, Long clienteId, String clave, String nombre, boolean activo) {}

  /** Unidad de obra con su prototipo y su presupuesto de material (pesos sin IVA). */
  public record UnidadDetalle(
      long id,
      long contratoId,
      String clave,
      String manzana,
      String lote,
      Long prototipoId,
      String prototipo,
      BigDecimal presupuesto,
      boolean activa) {}

  /** Frente (fraccionamiento u obra). */
  public record FrenteInfo(long id, String clave, String nombre, boolean activo) {}

  private final RepositorioUnidades unidades;
  private final RepositorioContratos contratos;
  private final RepositorioPaquetes paquetes;
  private final RepositorioClientes clientes;

  EstructuraApi(
      RepositorioUnidades unidades,
      RepositorioContratos contratos,
      RepositorioPaquetes paquetes,
      RepositorioClientes clientes) {
    this.unidades = unidades;
    this.contratos = contratos;
    this.paquetes = paquetes;
    this.clientes = clientes;
  }

  /** La unidad de obra de la empresa en curso, si existe. */
  @Transactional(readOnly = true)
  public Optional<UnidadInfo> unidad(long id) {
    return unidades(List.of(id)).stream().findFirst();
  }

  /** Las unidades de obra de la empresa en curso con esos ids; las que no existen se omiten. */
  @Transactional(readOnly = true)
  public List<UnidadInfo> unidades(Collection<Long> ids) {
    return ids.isEmpty() ? List.of() : unidades.infos(empresa(), ids);
  }

  /** Las unidades de obra del contrato en orden natural (M2 antes que M10), sin revisar alcance. */
  @Transactional(readOnly = true)
  public List<UnidadDetalle> unidadesDeContrato(long contratoId) {
    return unidades.unidades(empresa(), contratoId).stream()
        .map(
            u ->
                new UnidadDetalle(
                    u.id(),
                    u.contratoId(),
                    u.clave(),
                    u.manzana(),
                    u.lote(),
                    u.prototipoId(),
                    u.prototipo(),
                    u.presupuestoMaterial(),
                    u.activa()))
        .toList();
  }

  /** Contratos de la empresa en curso (todos los estados), sin revisar alcance. */
  @Transactional(readOnly = true)
  public List<ContratoInfo> todosLosContratos() {
    return contratos.infosTodos(empresa());
  }

  @Transactional(readOnly = true)
  public List<ContratoInfo> contratos(Collection<Long> ids) {
    return ids.isEmpty() ? List.of() : contratos.infos(empresa(), ids);
  }

  @Transactional(readOnly = true)
  public List<PaqueteInfo> paquetes(Collection<Long> ids) {
    return ids.isEmpty() ? List.of() : paquetes.infos(empresa(), ids);
  }

  @Transactional(readOnly = true)
  public List<FrenteInfo> frentes() {
    return clientes.frentes(empresa()).stream()
        .map(f -> new FrenteInfo(f.id(), f.clave(), f.nombre(), f.activo()))
        .toList();
  }

  @Transactional(readOnly = true)
  public Optional<FrenteInfo> frente(long id) {
    return clientes
        .frente(empresa(), id)
        .map(f -> new FrenteInfo(f.id(), f.clave(), f.nombre(), f.activo()));
  }

  /**
   * {@code true} si el usuario en curso puede ver el contrato (residente: si lo tiene asignado).
   */
  @Transactional(readOnly = true)
  public boolean contratoVisible(long contratoId) {
    return contratos.contrato(empresa(), Alcance.restringidoA(), contratoId).isPresent();
  }

  /**
   * Contratos que ve el usuario en curso: vacío si ve todos (no es lo mismo que no ver ninguno; ver
   * {@link #veTodosLosContratos}).
   */
  @Transactional(readOnly = true)
  public Set<Long> contratosVisibles() {
    Long usuario = Alcance.restringidoA();
    return usuario == null ? Set.of() : Set.copyOf(contratos.asignados(empresa(), usuario));
  }

  /** {@code true} si el usuario en curso ve todos los contratos de la empresa. */
  public boolean veTodosLosContratos() {
    return Alcance.restringidoA() == null;
  }

  private static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }
}

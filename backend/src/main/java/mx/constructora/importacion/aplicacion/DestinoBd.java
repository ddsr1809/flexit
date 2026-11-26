package mx.constructora.importacion.aplicacion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import mx.constructora.catalogo.ImportacionCatalogo;
import mx.constructora.estructura.ImportacionEstructura;
import mx.constructora.historico.ImportacionHistorico;
import mx.constructora.historico.Registros;
import mx.constructora.importacion.dominio.Destino;
import mx.constructora.importacion.infraestructura.RepositorioMigracion;
import mx.constructora.organizacion.Personas;

/**
 * El {@link Destino} de una confirmación: escribe por las APIs públicas de cada módulo con la marca
 * de la carga y deja en {@code migracion.registro} qué creó. Corre dentro de la transacción de
 * {@code confirmar}: si algo falla, no queda nada.
 */
final class DestinoBd implements Destino {

  private final long empresa;
  private final long carga;
  private final long usuario;
  private final ImportacionEstructura estructura;
  private final ImportacionCatalogo catalogo;
  private final Personas personas;
  private final ImportacionHistorico historico;
  private final RepositorioMigracion repositorio;
  private final Map<String, Long> creados = new LinkedHashMap<>();

  DestinoBd(
      long empresa,
      long carga,
      long usuario,
      ImportacionEstructura estructura,
      ImportacionCatalogo catalogo,
      Personas personas,
      ImportacionHistorico historico,
      RepositorioMigracion repositorio) {
    this.empresa = empresa;
    this.carga = carga;
    this.usuario = usuario;
    this.estructura = estructura;
    this.catalogo = catalogo;
    this.personas = personas;
    this.historico = historico;
    this.repositorio = repositorio;
  }

  /** Cuántos registros de cada entidad se crearon. */
  Map<String, Long> creados() {
    return creados;
  }

  private long registrar(String entidad, long id) {
    repositorio.registrar(empresa, carga, entidad, id);
    creados.merge(entidad, 1L, Long::sum);
    return id;
  }

  @Override
  public long crearFrente(long clienteId, String clave, String nombre) {
    return registrar("frente", estructura.crearFrente(carga, clienteId, clave, nombre));
  }

  @Override
  public long crearContrato(
      long clienteId,
      Long frenteId,
      String codigo,
      String numeroObra,
      LocalDate inicio,
      LocalDate fin,
      BigDecimal monto) {
    return registrar(
        "contrato",
        estructura.crearContrato(
            carga, clienteId, frenteId, codigo, numeroObra, inicio, fin, monto));
  }

  @Override
  public long crearPrototipo(String clave, String nombre) {
    return registrar("prototipo", estructura.crearPrototipo(carga, clave, nombre));
  }

  @Override
  public long crearUnidad(
      long contratoId, Long frenteId, String clave, String manzana, String lote, Long prototipoId) {
    return registrar(
        "unidad_obra",
        estructura.crearLote(carga, contratoId, frenteId, manzana, lote, prototipoId));
  }

  @Override
  public long crearPaquete(
      Long clienteId, Long padreId, String clave, String nombre, String unidad, int orden) {
    return registrar(
        "paquete",
        estructura.crearPaquete(carga, clienteId, padreId, clave, nombre, unidad, orden));
  }

  @Override
  public void agregarEquivalencia(long paqueteClienteId, long paquetePropioId) {
    estructura.agregarEquivalencia(carga, paqueteClienteId, paquetePropioId);
    creados.merge("equivalencia", 1L, Long::sum);
  }

  @Override
  public void fijarPrecio(
      long contratoId, long paqueteId, Long prototipoId, BigDecimal cobro, BigDecimal destajo) {
    estructura.fijarPrecio(carga, contratoId, paqueteId, prototipoId, cobro, destajo);
    creados.merge("precio", 1L, Long::sum);
  }

  @Override
  public long crearMaterial(String clave, String nombre, long unidadControlId, BigDecimal precio) {
    return registrar(
        "material", catalogo.crearMaterial(carga, clave, nombre, unidadControlId, precio));
  }

  @Override
  public long crearProveedor(String nombre, BigDecimal tasaIva) {
    return registrar("proveedor", catalogo.crearProveedor(carga, nombre, tasaIva));
  }

  @Override
  public long crearPersona(String nombre, boolean revisar, String nota) {
    return registrar("persona", personas.crearDesdeCarga(carga, nombre, revisar, nota));
  }

  @Override
  public void alias(String entidad, String texto, long destinoId) {
    repositorio.guardarAlias(empresa, entidad, texto, destinoId, "regla", carga, usuario);
    creados.merge("alias", 1L, Long::sum);
  }

  @Override
  public void historico(Registros.Lote lote) {
    if (lote.total() == 0) {
      return;
    }
    historico.guardar(carga, lote).forEach((tabla, n) -> creados.merge(tabla, n, Long::sum));
  }
}

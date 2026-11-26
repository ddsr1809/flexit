package mx.constructora.importacion.aplicacion;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import mx.constructora.catalogo.CatalogoApi;
import mx.constructora.catalogo.ImportacionCatalogo;
import mx.constructora.estructura.ImportacionEstructura;
import mx.constructora.historico.ImportacionHistorico;
import mx.constructora.historico.Registros.EntradaValorada;
import mx.constructora.importacion.dominio.Contexto;
import mx.constructora.organizacion.Personas;

/**
 * El {@link Contexto} de una validación, leído de las APIs públicas de cada módulo. Cada lista se
 * lee una vez por validación (perezosamente) y los alias cuyo destino ya no existe se ignoran.
 */
final class ContextoBd implements Contexto {

  private final ImportacionEstructura estructura;
  private final ImportacionCatalogo catalogo;
  private final Personas personas;
  private final Map<String, Long> alias;
  private final Map<String, String> parametros;

  private final Perezosa<List<Frente>> frentes;
  private final Perezosa<List<Contrato>> contratos;
  private final Perezosa<List<Prototipo>> prototipos;
  private final Perezosa<List<Unidad>> unidades;
  private final Perezosa<List<Paquete>> paquetes;
  private final Perezosa<List<Equivalencia>> equivalencias;
  private final Perezosa<List<Precio>> precios;
  private final Perezosa<List<UnidadMedida>> unidadesMedida;
  private final Perezosa<List<Material>> materiales;
  private final Perezosa<List<Proveedor>> proveedores;
  private final Perezosa<List<Persona>> listaPersonas;
  private final Perezosa<List<Clasificacion>> clasificaciones;
  private final Perezosa<Map<Long, BigDecimal>> tasasIva;
  private final Perezosa<Map<Long, BigDecimal>> preciosReferencia;
  private final Perezosa<List<EntradaValorada>> entradasHistoricas;

  ContextoBd(
      ImportacionEstructura estructura,
      ImportacionCatalogo catalogo,
      CatalogoApi catalogoApi,
      Personas personas,
      ImportacionHistorico historico,
      Map<String, Long> alias,
      Map<String, String> parametros) {
    this.estructura = estructura;
    this.catalogo = catalogo;
    this.personas = personas;
    this.alias = alias;
    this.parametros = parametros;
    frentes =
        new Perezosa<>(
            () ->
                estructura.frentes().stream()
                    .map(
                        f ->
                            new Frente(
                                f.id(), f.clienteId(), f.clave(), f.nombre(), f.nombreNormal()))
                    .toList());
    contratos =
        new Perezosa<>(
            () ->
                estructura.contratos().stream()
                    .map(c -> new Contrato(c.id(), c.codigo(), c.frenteId(), c.clienteId()))
                    .toList());
    prototipos =
        new Perezosa<>(
            () ->
                estructura.prototipos().stream()
                    .map(p -> new Prototipo(p.id(), p.clave()))
                    .toList());
    unidades =
        new Perezosa<>(
            () ->
                estructura.unidades().stream()
                    .map(
                        u ->
                            new Unidad(
                                u.id(), u.frenteId(), u.contratoId(), u.clave(), u.prototipoId()))
                    .toList());
    paquetes =
        new Perezosa<>(
            () ->
                estructura.paquetes().stream()
                    .map(
                        p ->
                            new Paquete(
                                p.id(),
                                p.clienteId(),
                                p.padreId(),
                                p.clave(),
                                p.nombreNormal(),
                                p.orden()))
                    .toList());
    equivalencias =
        new Perezosa<>(
            () ->
                estructura.equivalencias().stream()
                    .map(e -> new Equivalencia(e.paqueteClienteId(), e.paquetePropioId()))
                    .toList());
    precios =
        new Perezosa<>(
            () ->
                estructura.precios().stream()
                    .map(p -> new Precio(p.contratoId(), p.paqueteId(), p.prototipoId()))
                    .toList());
    unidadesMedida =
        new Perezosa<>(
            () ->
                catalogo.unidades().stream()
                    .map(u -> new UnidadMedida(u.id(), u.clave(), u.nombre()))
                    .toList());
    materiales =
        new Perezosa<>(
            () ->
                catalogo.materiales().stream()
                    .map(m -> new Material(m.id(), m.clave(), m.nombre(), m.nombreNormal()))
                    .toList());
    proveedores =
        new Perezosa<>(
            () ->
                catalogo.proveedores().stream()
                    .map(p -> new Proveedor(p.id(), p.nombre(), p.nombreNormal()))
                    .toList());
    listaPersonas =
        new Perezosa<>(
            () ->
                personas.activas().stream()
                    .map(p -> new Persona(p.id(), p.nombre(), p.nombreNormal()))
                    .toList());
    clasificaciones =
        new Perezosa<>(
            () ->
                catalogoApi.clasificaciones().stream()
                    .map(c -> new Clasificacion(c.id(), c.clave(), c.nombre()))
                    .toList());
    tasasIva =
        new Perezosa<>(
            () -> {
              Map<Long, BigDecimal> tasas = new HashMap<>();
              catalogoApi.proveedores(proveedores().stream().map(Proveedor::id).toList()).stream()
                  .filter(p -> p.tasaIva() != null)
                  .forEach(p -> tasas.put(p.id(), p.tasaIva()));
              return tasas;
            });
    preciosReferencia =
        new Perezosa<>(
            () -> {
              Map<Long, BigDecimal> precios = new HashMap<>();
              catalogoApi.materiales(materiales().stream().map(Material::id).toList()).stream()
                  .filter(m -> m.precioReferencia() != null)
                  .forEach(m -> precios.put(m.id(), m.precioReferencia()));
              return precios;
            });
    entradasHistoricas = new Perezosa<>(historico::entradasValoradas);
  }

  /** Un valor que se calcula la primera vez que se pide. */
  private static final class Perezosa<T> {
    private final Supplier<T> proveedor;
    private T valor;

    Perezosa(Supplier<T> proveedor) {
      this.proveedor = proveedor;
    }

    T get() {
      if (valor == null) {
        valor = proveedor.get();
      }
      return valor;
    }
  }

  @Override
  public Optional<Long> alias(String entidad, String texto) {
    return Optional.ofNullable(alias.get(entidad + "|" + texto));
  }

  @Override
  public List<Frente> frentes() {
    return frentes.get();
  }

  @Override
  public List<Contrato> contratos() {
    return contratos.get();
  }

  @Override
  public List<Prototipo> prototipos() {
    return prototipos.get();
  }

  @Override
  public List<Unidad> unidades() {
    return unidades.get();
  }

  @Override
  public List<Paquete> paquetes() {
    return paquetes.get();
  }

  @Override
  public List<Equivalencia> equivalencias() {
    return equivalencias.get();
  }

  @Override
  public List<Precio> precios() {
    return precios.get();
  }

  @Override
  public List<UnidadMedida> unidadesMedida() {
    return unidadesMedida.get();
  }

  @Override
  public List<Material> materiales() {
    return materiales.get();
  }

  @Override
  public List<Proveedor> proveedores() {
    return proveedores.get();
  }

  @Override
  public List<Persona> personas() {
    return listaPersonas.get();
  }

  @Override
  public Map<String, String> parametros() {
    return parametros;
  }

  @Override
  public List<Clasificacion> clasificaciones() {
    return clasificaciones.get();
  }

  @Override
  public Map<Long, BigDecimal> tasasIva() {
    return tasasIva.get();
  }

  @Override
  public Map<Long, BigDecimal> preciosReferencia() {
    return preciosReferencia.get();
  }

  @Override
  public List<EntradaValorada> entradasHistoricas() {
    return entradasHistoricas.get();
  }

  /** {@code true} si existe el registro {@code id} de la entidad (para validar un alias). */
  boolean existe(String entidad, long id) {
    return switch (entidad) {
      case "frente" -> frentes().stream().anyMatch(f -> f.id() == id);
      case "contrato" -> contratos().stream().anyMatch(c -> c.id() == id);
      case "prototipo" -> prototipos().stream().anyMatch(p -> p.id() == id);
      case "unidad_medida" -> unidadesMedida().stream().anyMatch(u -> u.id() == id);
      case "material" -> materiales().stream().anyMatch(m -> m.id() == id);
      case "proveedor" -> proveedores().stream().anyMatch(p -> p.id() == id);
      case "persona" -> personas().stream().anyMatch(p -> p.id() == id);
      case "paquete" -> paquetes().stream().anyMatch(p -> p.id() == id && p.clienteId() == null);
      case "unidad_obra" -> unidades().stream().anyMatch(u -> u.id() == id);
      default -> false;
    };
  }

  /** Cómo se muestra el registro {@code id} de la entidad; {@code null} si no existe. */
  String nombre(String entidad, long id) {
    return switch (entidad) {
      case "frente" ->
          frentes().stream().filter(f -> f.id() == id).map(Frente::nombre).findFirst().orElse(null);
      case "contrato" ->
          contratos().stream()
              .filter(c -> c.id() == id)
              .map(Contrato::codigo)
              .findFirst()
              .orElse(null);
      case "prototipo" ->
          prototipos().stream()
              .filter(p -> p.id() == id)
              .map(Prototipo::clave)
              .findFirst()
              .orElse(null);
      case "unidad_medida" ->
          unidadesMedida().stream()
              .filter(u -> u.id() == id)
              .map(u -> u.clave() + " · " + u.nombre())
              .findFirst()
              .orElse(null);
      case "material" ->
          materiales().stream()
              .filter(m -> m.id() == id)
              .map(m -> m.clave() + " · " + m.nombre())
              .findFirst()
              .orElse(null);
      case "proveedor" ->
          proveedores().stream()
              .filter(p -> p.id() == id)
              .map(Proveedor::nombre)
              .findFirst()
              .orElse(null);
      case "persona" ->
          personas().stream()
              .filter(p -> p.id() == id)
              .map(Persona::nombre)
              .findFirst()
              .orElse(null);
      case "paquete" ->
          paquetes().stream()
              .filter(p -> p.id() == id)
              .map(p -> p.clave() + " · " + p.nombreNormal())
              .findFirst()
              .orElse(null);
      case "unidad_obra" ->
          unidades().stream().filter(u -> u.id() == id).map(Unidad::clave).findFirst().orElse(null);
      default -> null;
    };
  }

  ImportacionEstructura estructura() {
    return estructura;
  }

  ImportacionCatalogo catalogo() {
    return catalogo;
  }

  Personas personasApi() {
    return personas;
  }
}

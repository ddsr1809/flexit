package mx.constructora.importacion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mx.constructora.historico.Registros;
import mx.constructora.historico.Registros.EntradaValorada;
import mx.constructora.importacion.dominio.Contexto;
import mx.constructora.importacion.dominio.Destino;
import mx.constructora.importacion.dominio.Normal;

/**
 * Catálogos y alias en memoria, para probar los importadores sin base de datos. Implementa las dos
 * caras: lo que la validación lee ({@link Contexto}) y lo que la confirmación escribe ({@link
 * Destino}). Las unidades de medida son las del sistema (V005).
 */
public final class SistemaEnMemoria implements Contexto, Destino {

  private long siguiente = 1;
  final List<Frente> frentes = new ArrayList<>();
  final List<Contrato> contratos = new ArrayList<>();
  final List<Prototipo> prototipos = new ArrayList<>();
  final List<Unidad> unidades = new ArrayList<>();
  final List<Paquete> paquetes = new ArrayList<>();
  final List<Precio> precios = new ArrayList<>();
  final List<long[]> equivalencias = new ArrayList<>();
  final Map<String, BigDecimal[]> valoresPrecio = new LinkedHashMap<>();
  final List<UnidadMedida> unidadesMedida = new ArrayList<>();
  final List<Material> materiales = new ArrayList<>();
  final Map<Long, BigDecimal> preciosMaterial = new HashMap<>();
  final List<Proveedor> proveedores = new ArrayList<>();
  final Map<Long, BigDecimal> ivaProveedor = new HashMap<>();
  final List<Persona> personas = new ArrayList<>();
  final Map<Long, Boolean> personaRevisar = new HashMap<>();
  final Map<String, Long> alias = new HashMap<>();
  final Map<String, String> parametros = new HashMap<>();
  final List<Clasificacion> clasificaciones = new ArrayList<>();

  /** Lo que escribieron las cargas del histórico, en orden. */
  final Registros.Lote historico = new Registros.Lote();

  public SistemaEnMemoria() {
    for (String[] u :
        new String[][] {
          {"PZA", "Pieza"},
          {"CJA", "Caja"},
          {"PAQ", "Paquete"},
          {"ROL", "Rollo"},
          {"SAC", "Saco"},
          {"BOL", "Bolsa"},
          {"CUB", "Cubeta"},
          {"TAM", "Tambo"},
          {"TRA", "Tramo"},
          {"KG", "Kilogramo"},
          {"G", "Gramo"},
          {"L", "Litro"},
          {"M", "Metro"},
          {"M2", "Metro cuadrado"},
          {"M3", "Metro cúbico"}
        }) {
      unidadesMedida.add(new UnidadMedida(siguiente++, u[0], u[1]));
    }
    String[][] razones = {
      {"PROGRAMA", "Programa"},
      {"GARANTIA_CLIENTE", "Garantía Ruba"},
      {"GARANTIA_PROPIA", "Garantía RM"},
      {"RETRABAJO", "Retrabajo"},
      {"ESCRITURABLES", "Escriturables"},
      {"ENTREGA", "Entrega"},
      {"RESCATE", "Rescate"},
      {"REEMPLAZO_FALLA", "Reemplazo por falla"}
    };
    for (int i = 0; i < razones.length; i++) {
      clasificaciones.add(new Clasificacion(i + 1, razones[i][0], razones[i][1]));
    }
  }

  public long id() {
    return siguiente++;
  }

  public SistemaEnMemoria parametro(String clave, Object valor) {
    parametros.put(clave, valor.toString());
    return this;
  }

  public long cliente() {
    return id();
  }

  public void ponerAlias(String entidad, String texto, long destino) {
    alias.put(entidad + "|" + Normal.texto(texto), destino);
  }

  // ---------------------------------------------------------------------------------- Contexto

  @Override
  public Optional<Long> alias(String entidad, String texto) {
    return Optional.ofNullable(alias.get(entidad + "|" + texto));
  }

  @Override
  public List<Frente> frentes() {
    return List.copyOf(frentes);
  }

  @Override
  public List<Contrato> contratos() {
    return List.copyOf(contratos);
  }

  @Override
  public List<Prototipo> prototipos() {
    return List.copyOf(prototipos);
  }

  @Override
  public List<Unidad> unidades() {
    return List.copyOf(unidades);
  }

  @Override
  public List<Paquete> paquetes() {
    return List.copyOf(paquetes);
  }

  @Override
  public List<Equivalencia> equivalencias() {
    return equivalencias.stream().map(e -> new Equivalencia(e[0], e[1])).toList();
  }

  @Override
  public List<Precio> precios() {
    return List.copyOf(precios);
  }

  @Override
  public List<UnidadMedida> unidadesMedida() {
    return List.copyOf(unidadesMedida);
  }

  @Override
  public List<Material> materiales() {
    return List.copyOf(materiales);
  }

  @Override
  public List<Proveedor> proveedores() {
    return List.copyOf(proveedores);
  }

  @Override
  public List<Persona> personas() {
    return List.copyOf(personas);
  }

  @Override
  public Map<String, String> parametros() {
    return Map.copyOf(parametros);
  }

  @Override
  public List<Clasificacion> clasificaciones() {
    return List.copyOf(clasificaciones);
  }

  @Override
  public Map<Long, BigDecimal> tasasIva() {
    return Map.copyOf(ivaProveedor);
  }

  @Override
  public Map<Long, BigDecimal> preciosReferencia() {
    return Map.copyOf(preciosMaterial);
  }

  @Override
  public List<EntradaValorada> entradasHistoricas() {
    return historico.entradas.stream()
        .filter(e -> e.importe() != null)
        .map(e -> new EntradaValorada(e.materialId(), e.fecha(), e.cantidad(), e.importe()))
        .toList();
  }

  // ----------------------------------------------------------------------------------- Destino

  @Override
  public long crearFrente(long clienteId, String clave, String nombre) {
    long id = id();
    frentes.add(new Frente(id, clienteId, clave, nombre, Normal.texto(nombre)));
    return id;
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
    if (contratos.stream().anyMatch(c -> c.codigo().equals(codigo))) {
      throw new IllegalStateException("Contrato duplicado " + codigo);
    }
    long id = id();
    contratos.add(new Contrato(id, codigo, frenteId, clienteId));
    return id;
  }

  @Override
  public long crearPrototipo(String clave, String nombre) {
    long id = id();
    prototipos.add(new Prototipo(id, clave));
    return id;
  }

  @Override
  public long crearUnidad(
      long contratoId, Long frenteId, String clave, String manzana, String lote, Long prototipoId) {
    if (unidades.stream()
        .anyMatch(
            u -> u.clave().equals(clave) && java.util.Objects.equals(u.frenteId(), frenteId))) {
      throw new IllegalStateException("Lote duplicado en el frente " + clave);
    }
    long id = id();
    unidades.add(new Unidad(id, frenteId, contratoId, clave, prototipoId));
    return id;
  }

  @Override
  public long crearPaquete(
      Long clienteId, Long padreId, String clave, String nombre, String unidad, int orden) {
    if (paquetes.stream()
        .anyMatch(
            p -> java.util.Objects.equals(p.clienteId(), clienteId) && p.clave().equals(clave))) {
      throw new IllegalStateException("Clave de paquete repetida " + clave);
    }
    long id = id();
    paquetes.add(new Paquete(id, clienteId, padreId, clave, Normal.texto(nombre), orden));
    return id;
  }

  @Override
  public void agregarEquivalencia(long paqueteClienteId, long paquetePropioId) {
    equivalencias.add(new long[] {paqueteClienteId, paquetePropioId});
  }

  @Override
  public void fijarPrecio(
      long contratoId, long paqueteId, Long prototipoId, BigDecimal cobro, BigDecimal destajo) {
    String llave = contratoId + "|" + paqueteId + "|" + prototipoId;
    if (valoresPrecio.containsKey(llave)) {
      throw new IllegalStateException("Precio repetido " + llave);
    }
    precios.add(new Precio(contratoId, paqueteId, prototipoId));
    valoresPrecio.put(llave, new BigDecimal[] {cobro, destajo});
  }

  @Override
  public long crearMaterial(String clave, String nombre, long unidadControlId, BigDecimal precio) {
    String claveFinal =
        clave != null
            ? clave
            : "%03d"
                .formatted(
                    materiales.stream()
                            .map(Material::clave)
                            .filter(c -> c.matches("\\d+"))
                            .mapToInt(Integer::parseInt)
                            .max()
                            .orElse(0)
                        + 1);
    if (materiales.stream()
        .anyMatch(
            m -> m.clave().equals(claveFinal) || m.nombreNormal().equals(Normal.texto(nombre)))) {
      throw new IllegalStateException("Material duplicado " + claveFinal + " " + nombre);
    }
    long id = id();
    materiales.add(new Material(id, claveFinal, nombre, Normal.texto(nombre)));
    if (precio != null) {
      preciosMaterial.put(id, precio);
    }
    return id;
  }

  @Override
  public long crearProveedor(String nombre, BigDecimal tasaIva) {
    if (proveedores.stream().anyMatch(p -> p.nombreNormal().equals(Normal.texto(nombre)))) {
      throw new IllegalStateException("Proveedor duplicado " + nombre);
    }
    long id = id();
    proveedores.add(new Proveedor(id, nombre, Normal.texto(nombre)));
    if (tasaIva != null) {
      ivaProveedor.put(id, tasaIva);
    }
    return id;
  }

  @Override
  public long crearPersona(String nombre, boolean revisar, String nota) {
    long id = id();
    personas.add(new Persona(id, nombre, Normal.texto(nombre)));
    personaRevisar.put(id, revisar);
    return id;
  }

  @Override
  public void alias(String entidad, String texto, long destinoId) {
    alias.put(entidad + "|" + texto, destinoId);
  }

  @Override
  public void historico(Registros.Lote lote) {
    historico.entradas.addAll(lote.entradas);
    historico.salidas.addAll(lote.salidas);
    historico.requisiciones.addAll(lote.requisiciones);
    historico.prestamos.addAll(lote.prestamos);
    historico.altas.addAll(lote.altas);
    historico.asistencias.addAll(lote.asistencias);
    historico.asignaciones.addAll(lote.asignaciones);
    historico.pagos.addAll(lote.pagos);
    historico.saldos.addAll(lote.saldos);
  }
}

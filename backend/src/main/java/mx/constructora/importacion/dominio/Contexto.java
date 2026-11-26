package mx.constructora.importacion.dominio;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mx.constructora.historico.Registros.EntradaValorada;

/**
 * Lo que la validación necesita saber del sistema: los catálogos actuales y la tabla de alias. Solo
 * lectura. En la aplicación lo llenan las APIs públicas de cada módulo; en las pruebas, listas en
 * memoria.
 */
public interface Contexto {

  /** Frente con su cliente. {@code nombreNormal} como lo deja {@link Normal#texto}. */
  record Frente(long id, long clienteId, String clave, String nombre, String nombreNormal) {}

  record Contrato(long id, String codigo, Long frenteId, long clienteId) {}

  record Prototipo(long id, String clave) {}

  record Unidad(long id, Long frenteId, long contratoId, String clave, Long prototipoId) {}

  /** Paquete de un catálogo: propio ({@code clienteId} nulo) o de un cliente. */
  record Paquete(
      long id, Long clienteId, Long padreId, String clave, String nombreNormal, int orden) {}

  /** Equivalencia paquete del cliente ↔ paquete propio. */
  record Equivalencia(long paqueteClienteId, long paquetePropioId) {}

  /** Precio de un paquete en un contrato, por prototipo. */
  record Precio(long contratoId, long paqueteId, Long prototipoId) {}

  record UnidadMedida(long id, String clave, String nombre) {}

  record Material(long id, String clave, String nombre, String nombreNormal) {}

  record Proveedor(long id, String nombre, String nombreNormal) {}

  record Persona(long id, String nombre, String nombreNormal) {}

  /** Clasificación de costo: la "Razón" de una salida (Programa, Garantía Ruba…). */
  record Clasificacion(int id, String clave, String nombre) {}

  /** Destino de un alias guardado ({@code entidad} y texto normalizado), si existe. */
  Optional<Long> alias(String entidad, String texto);

  List<Frente> frentes();

  List<Contrato> contratos();

  List<Prototipo> prototipos();

  List<Unidad> unidades();

  List<Paquete> paquetes();

  List<Equivalencia> equivalencias();

  List<Precio> precios();

  List<UnidadMedida> unidadesMedida();

  List<Material> materiales();

  List<Proveedor> proveedores();

  List<Persona> personas();

  /** Parámetros con los que se subió la carga ({@code clienteId}). */
  Map<String, String> parametros();

  // ------------------------------------------------------------------- lo que pide el histórico

  /** Clasificaciones de costo (catálogo fijo del sistema). */
  default List<Clasificacion> clasificaciones() {
    return List.of();
  }

  /** Tasa de IVA por defecto de cada proveedor que la tiene (proveedor → tasa). */
  default Map<Long, BigDecimal> tasasIva() {
    return Map.of();
  }

  /** Precio de referencia sin IVA de cada material que lo tiene (material → precio). */
  default Map<Long, BigDecimal> preciosReferencia() {
    return Map.of();
  }

  /** Entradas del histórico ya confirmadas, con importe: la base para valuar las salidas. */
  default List<EntradaValorada> entradasHistoricas() {
    return List.of();
  }
}

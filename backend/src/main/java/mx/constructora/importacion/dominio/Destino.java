package mx.constructora.importacion.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import mx.constructora.historico.Registros;

/**
 * A dónde escribe una carga al confirmarse. En la aplicación cada método llama a la API pública del
 * módulo dueño (estructura, catálogo, organización) y deja la marca de la carga para poder
 * revertirla; en las pruebas guarda en memoria. Todo pasa en una sola transacción.
 */
public interface Destino {

  long crearFrente(long clienteId, String clave, String nombre);

  long crearContrato(
      long clienteId,
      Long frenteId,
      String codigo,
      String numeroObra,
      LocalDate inicio,
      LocalDate fin,
      BigDecimal monto);

  long crearPrototipo(String clave, String nombre);

  long crearUnidad(
      long contratoId, Long frenteId, String clave, String manzana, String lote, Long prototipoId);

  long crearPaquete(
      Long clienteId, Long padreId, String clave, String nombre, String unidad, int orden);

  void agregarEquivalencia(long paqueteClienteId, long paquetePropioId);

  /** Precio de cobro (paquete del cliente) o de destajo (paquete propio) en el contrato. */
  void fijarPrecio(
      long contratoId, long paqueteId, Long prototipoId, BigDecimal cobro, BigDecimal destajo);

  /**
   * @param clave {@code null}: la siguiente numérica (001, 002…)
   */
  long crearMaterial(String clave, String nombre, long unidadControlId, BigDecimal precio);

  long crearProveedor(String nombre, BigDecimal tasaIva);

  long crearPersona(String nombre, boolean revisar, String nota);

  /** Guarda que {@code texto} (normalizado) en el Excel es el registro {@code destinoId}. */
  void alias(String entidad, String texto, long destinoId);

  /** Escribe en el histórico todo lo que produjo la carga (H5). */
  void historico(Registros.Lote lote);
}

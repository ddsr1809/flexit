/**
 * Abastecimiento (hito H6): requisición con autorización, reserva de existencia, faltantes por
 * proveedor, pedido y recepción contra pedido. Reemplaza la hoja Semanal.
 *
 * <ul>
 *   <li>{@code dominio}: Java puro. El avance de un renglón (surtido, apartado, por recibir y lo
 *       que falta comprar) y la asignación en orden de lo pedido y lo recibido.
 *   <li>{@code aplicacion}: requisiciones ({@code ServicioRequisiciones}) y pedidos ({@code
 *       ServicioPedidos}). Usa el inventario solo por {@code InventarioApi}: la reserva, el vale
 *       que surte y la recepción viven allá, en la misma transacción (ADR-012).
 *   <li>{@code infraestructura}: el esquema {@code abastecimiento} (V009).
 * </ul>
 *
 * <p>Solo puede usar los módulos listados en {@code allowedDependencies}; del histórico, solo el
 * último proveedor de cada material ({@code ConsultaHistorico}).
 */
@ApplicationModule(
    allowedDependencies = {
      "inventario",
      "estructura",
      "catalogo",
      "historico",
      "organizacion",
      "compartido"
    })
package mx.constructora.abastecimiento;

import org.springframework.modulith.ApplicationModule;

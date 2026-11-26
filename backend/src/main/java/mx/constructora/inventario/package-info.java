/**
 * Movimientos, kardex, saldo y costo promedio: el núcleo (hito H4). Solo puede usar los módulos
 * listados en {@code allowedDependencies}, y solo por sus APIs públicas ({@code CatalogoApi},
 * {@code EstructuraApi}, {@code Personas}, {@code DirectorioUsuarios}).
 *
 * <ul>
 *   <li>{@code dominio}: Java puro. Valuación por valor y existencia, costo de recepción con
 *       descuento y flete, repartos que suman exacto y el ajuste de costo por factura.
 *   <li>{@code aplicacion}: captura y ciclo de los documentos ({@code ServicioMovimientos}), y
 *       aplicarlos ({@code ServicioAplicacion}, lo único que mueve existencia y costo). También
 *       existencias, préstamos, conteo físico y cierre de mes.
 *   <li>{@code infraestructura}: el esquema {@code inventario} (V007).
 * </ul>
 *
 * <p>El costo de obra vive en {@code inventario.cargo} (ADR-010).
 */
@ApplicationModule(allowedDependencies = {"estructura", "catalogo", "organizacion", "compartido"})
package mx.constructora.inventario;

import org.springframework.modulith.ApplicationModule;

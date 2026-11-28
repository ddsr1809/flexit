/**
 * Movimientos, kardex, saldo y costo promedio: el núcleo (hito H4). Solo puede usar los módulos
 * listados en {@code allowedDependencies}, y solo por sus APIs públicas ({@code CatalogoApi},
 * {@code EstructuraApi}, {@code Personas}, {@code DirectorioUsuarios} y, para el corte (H8),
 * {@code ConsultaHistorico}).
 *
 * <ul>
 *   <li>{@code dominio}: Java puro. Valuación por valor y existencia, costo de recepción con
 *       descuento y flete, repartos que suman exacto y el ajuste de costo por factura.
 *   <li>{@code aplicacion}: captura y ciclo de los documentos ({@code ServicioMovimientos}), y
 *       aplicarlos ({@code ServicioAplicacion}, lo único que mueve existencia y costo). También
 *       existencias, préstamos, conteo físico, cierre de mes y la apertura del almacén en el
 *       corte ({@code ServicioAperturas}).
 *   <li>{@code infraestructura}: el esquema {@code inventario} (V007, V009 y V011).
 * </ul>
 *
 * <p>El costo de obra vive en {@code inventario.cargo} (ADR-010).
 */
@ApplicationModule(
    allowedDependencies = {"estructura", "catalogo", "organizacion", "historico", "compartido"})
package mx.constructora.inventario;

import org.springframework.modulith.ApplicationModule;

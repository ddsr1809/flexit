/**
 * Histórico del Excel (hito H5): lo que se capturó en los Excel de almacén, SUA y destajo desde
 * junio de 2026, tal como pasó, ligado a catálogos reales. No toca el inventario vivo: el kardex
 * arranca del conteo físico (H8) y los reportes de costo suman las dos fuentes (ADR-011).
 *
 * <ul>
 *   <li>API para otros módulos: {@link ImportacionHistorico} (solo la migración escribe aquí, con
 *       los records de {@link Registros}).
 *   <li>{@code aplicacion} y {@code web}: la historia de un lote (vales, pagos de destajo y altas
 *       del IMSS de su contrato) con el alcance del residente.
 *   <li>{@code infraestructura}: el esquema {@code historico} (V008).
 * </ul>
 */
@ApplicationModule(allowedDependencies = {"estructura", "catalogo", "organizacion", "compartido"})
package mx.constructora.historico;

import org.springframework.modulith.ApplicationModule;

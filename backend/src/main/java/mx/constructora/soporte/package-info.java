/**
 * Soporte del piloto (hito H9): los incidentes que reporta cualquier usuario y que administración
 * atiende hasta resolverlos. El criterio de H9 pide cero incidentes críticos abiertos; {@link
 * SoporteApi} se lo da al reporte de estabilización.
 *
 * <ul>
 *   <li>{@code aplicacion}: reportar, clasificar, resolver, descartar, reabrir y anotar ({@code
 *       ServicioIncidentes}).
 *   <li>{@code infraestructura}: el esquema {@code soporte} (V012).
 * </ul>
 *
 * <p>Usa el inventario solo para validar y mostrar el almacén ({@code InventarioApi}) y la
 * organización para el nombre de quien reporta ({@code DirectorioUsuarios}).
 */
@ApplicationModule(allowedDependencies = {"inventario", "organizacion", "compartido"})
package mx.constructora.soporte;

import org.springframework.modulith.ApplicationModule;

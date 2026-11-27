/**
 * Reportes de solo lectura sobre todos los módulos (hito H6): costo de obra por contrato, lote,
 * paquete, clasificación, material o mes (histórico del Excel + sistema), presupuesto por lote con
 * semáforo, valor del inventario y requisición contra surtido. Todos se exportan a Excel.
 *
 * <p>Lee las vistas del esquema {@code reportes} (V008 y V009, con el RLS de quien consulta) y los
 * nombres por las APIs públicas de cada módulo; nunca escribe.
 */
@ApplicationModule
package mx.constructora.reportes;

import org.springframework.modulith.ApplicationModule;

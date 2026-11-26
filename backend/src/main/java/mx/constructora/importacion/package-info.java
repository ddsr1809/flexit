/**
 * Centro de migración desde Excel (hitos H3 y H5): subir, normalizar, validar, cuarentena,
 * conciliar, confirmar y revertir. Solo puede usar los módulos listados en {@code
 * allowedDependencies} y solo por sus APIs públicas: nunca escribe en tablas de otro módulo.
 *
 * <ul>
 *   <li>{@code dominio}: Java puro (sin Spring ni base). El lector de .xlsx en streaming, el motor
 *       de reglas y un importador por tipo de carga. Se prueba con las muestras anonimizadas.
 *   <li>{@code aplicacion}: el servicio con transacciones y permisos; lee y escribe en los demás
 *       módulos por {@code ImportacionEstructura}, {@code ImportacionCatalogo}, {@code Personas} e
 *       {@code ImportacionHistorico} (el histórico del Excel, H5).
 *   <li>{@code infraestructura}: el esquema {@code migracion}.
 * </ul>
 */
@ApplicationModule(
    allowedDependencies = {
      "estructura",
      "catalogo",
      "inventario",
      "historico",
      "organizacion",
      "compartido"
    })
package mx.constructora.importacion;

import org.springframework.modulith.ApplicationModule;

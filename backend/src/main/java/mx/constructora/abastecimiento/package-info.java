/**
 * Requisición, reserva, pedido y recepción contra pedido (hito H6). Solo puede usar los módulos
 * listados en {@code allowedDependencies}.
 */
@ApplicationModule(
    allowedDependencies = {"inventario", "estructura", "catalogo", "organizacion", "compartido"})
package mx.constructora.abastecimiento;

import org.springframework.modulith.ApplicationModule;

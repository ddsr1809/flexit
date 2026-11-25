/**
 * Cliente, frente, contrato, unidad de obra, prototipo y paquete (hito H2). Solo puede usar los
 * módulos listados en {@code allowedDependencies}.
 */
@ApplicationModule(allowedDependencies = {"organizacion", "compartido"})
package mx.constructora.estructura;

import org.springframework.modulith.ApplicationModule;

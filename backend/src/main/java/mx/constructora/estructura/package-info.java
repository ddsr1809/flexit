/**
 * Cliente, frente, contrato, unidad de obra, prototipo y paquete (hito H2): la cadena a la que se
 * carga todo costo e ingreso. Solo puede usar los módulos listados en {@code allowedDependencies}.
 *
 * <p>API para otros módulos: {@link EstructuraApi}, {@link ClaveUnidad} y {@link CodigoContrato}.
 */
@ApplicationModule(allowedDependencies = {"organizacion", "compartido"})
package mx.constructora.estructura;

import org.springframework.modulith.ApplicationModule;

/**
 * Materiales, unidades de medida, conversiones, categorías, proveedores y clasificaciones de costo
 * (hito H2). Solo puede usar los módulos listados en {@code allowedDependencies}.
 *
 * <p>API para otros módulos: {@link CatalogoApi} y {@link Conversiones}.
 */
@ApplicationModule(allowedDependencies = {"organizacion", "compartido"})
package mx.constructora.catalogo;

import org.springframework.modulith.ApplicationModule;

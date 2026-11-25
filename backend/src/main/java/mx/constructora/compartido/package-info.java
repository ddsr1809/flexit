/**
 * Piezas transversales: contexto de empresa, permisos, folios, errores, seguridad web y bitácora.
 *
 * <p>Los tipos de este paquete son la API que pueden usar los demás módulos. Los subpaquetes
 * ({@code infraestructura}, {@code seguridad}, {@code web}) son internos. Este módulo no depende de
 * ningún otro.
 */
@ApplicationModule(allowedDependencies = {})
package mx.constructora.compartido;

import org.springframework.modulith.ApplicationModule;

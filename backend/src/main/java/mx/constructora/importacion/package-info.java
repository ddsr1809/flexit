/**
 * Cargas desde Excel: subir, validar, cuarentena y confirmar (hitos H3 y H5). Solo puede usar los
 * módulos listados en {@code allowedDependencies}.
 */
@ApplicationModule(
    allowedDependencies = {"estructura", "catalogo", "inventario", "organizacion", "compartido"})
package mx.constructora.importacion;

import org.springframework.modulith.ApplicationModule;

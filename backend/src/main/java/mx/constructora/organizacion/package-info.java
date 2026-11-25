/**
 * Empresas, usuarios, roles, inicio de sesión y (desde H2) alcance por contrato.
 *
 * <p>Otros módulos no usan este paquete: leen al usuario en curso con {@code
 * compartido.ContextoEmpresa} y revisan permisos con {@code compartido.Permisos}.
 */
@ApplicationModule(allowedDependencies = {"compartido"})
package mx.constructora.organizacion;

import org.springframework.modulith.ApplicationModule;

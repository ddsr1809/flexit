/**
 * Empresas, usuarios, roles e inicio de sesión. El alcance por contrato (qué contratos ve un
 * residente) vive en {@code estructura.contrato_usuario}, porque este módulo no depende de
 * estructura; para mostrar nombres, los demás módulos usan {@link DirectorioUsuarios}.
 *
 * <p>Para el usuario en curso, los demás módulos usan {@code compartido.ContextoEmpresa} y revisan
 * permisos con {@code compartido.Permisos}; de aquí solo usan {@link Rol} y {@link
 * DirectorioUsuarios}.
 */
@ApplicationModule(allowedDependencies = {"compartido"})
package mx.constructora.organizacion;

import org.springframework.modulith.ApplicationModule;

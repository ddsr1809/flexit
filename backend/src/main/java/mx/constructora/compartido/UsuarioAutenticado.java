package mx.constructora.compartido;

/**
 * Lo que cualquier módulo necesita saber del usuario en sesión. El principal de Spring Security lo
 * implementa (ver {@code organizacion}); {@link ContextoEmpresa} lo lee para fijar la empresa en
 * cada conexión a la base.
 */
public interface UsuarioAutenticado {

  long usuarioId();

  long empresaId();
}

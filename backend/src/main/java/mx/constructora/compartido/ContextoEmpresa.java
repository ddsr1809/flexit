package mx.constructora.compartido;

import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Empresa y usuario de la operación en curso.
 *
 * <p>En una petición web sale del usuario en sesión. En tareas en segundo plano, cargas y pruebas
 * se fija explícitamente con {@link #como}. Cada conexión que la app toma del pool recibe estos
 * valores en {@code app.empresa_id} y {@code app.usuario_id}, y las políticas de PostgreSQL filtran
 * con ellos.
 *
 * <p>Importante: el valor se aplica al <em>tomar</em> la conexión. Cambiar de empresa dentro de una
 * transacción abierta no cambia lo que ve esa transacción; {@code como} se llama por fuera.
 */
public final class ContextoEmpresa {

  /** Empresa y usuario en curso. */
  public record Identidad(long empresaId, long usuarioId) {}

  private static final ThreadLocal<Identidad> FORZADA = new ThreadLocal<>();

  private ContextoEmpresa() {}

  public static Optional<Identidad> actual() {
    Identidad forzada = FORZADA.get();
    if (forzada != null) {
      return Optional.of(forzada);
    }
    Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
    if (autenticacion != null
        && autenticacion.isAuthenticated()
        && autenticacion.getPrincipal() instanceof UsuarioAutenticado usuario) {
      return Optional.of(new Identidad(usuario.empresaId(), usuario.usuarioId()));
    }
    return Optional.empty();
  }

  public static Identidad requerida() {
    return actual()
        .orElseThrow(
            () -> new IllegalStateException("No hay empresa en el contexto de la operación"));
  }

  /** Ejecuta {@code accion} como si la hiciera {@code usuarioId} dentro de {@code empresaId}. */
  public static <T> T como(long empresaId, long usuarioId, Supplier<T> accion) {
    Identidad anterior = FORZADA.get();
    FORZADA.set(new Identidad(empresaId, usuarioId));
    try {
      return accion.get();
    } finally {
      if (anterior == null) {
        FORZADA.remove();
      } else {
        FORZADA.set(anterior);
      }
    }
  }

  /** Igual que {@link #como} para acciones sin resultado. */
  public static void ejecutarComo(long empresaId, long usuarioId, Runnable accion) {
    como(
        empresaId,
        usuarioId,
        () -> {
          accion.run();
          return null;
        });
  }
}

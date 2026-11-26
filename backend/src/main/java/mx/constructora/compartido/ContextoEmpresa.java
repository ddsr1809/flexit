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
 *
 * <p>Leer el usuario en sesión puede requerir la base: Spring Security carga el contexto de forma
 * diferida y Spring Session lo lee de {@code organizacion.sesion}. Esa lectura pide una conexión, y
 * la conexión vuelve a preguntar aquí por la empresa. Sin protección, cada vuelta toma otra
 * conexión hasta agotar el pool. Por eso, mientras se resuelve el contexto en este hilo, una
 * pregunta anidada responde "sin empresa": la tabla de sesiones no tiene RLS y no la necesita.
 */
public final class ContextoEmpresa {

  /** Empresa y usuario en curso. */
  public record Identidad(long empresaId, long usuarioId) {}

  private static final ThreadLocal<Identidad> FORZADA = new ThreadLocal<>();

  /** Marca que este hilo está leyendo el contexto de seguridad (ver la nota de la clase). */
  private static final ThreadLocal<Boolean> RESOLVIENDO = new ThreadLocal<>();

  private ContextoEmpresa() {}

  public static Optional<Identidad> actual() {
    Identidad forzada = FORZADA.get();
    if (forzada != null) {
      return Optional.of(forzada);
    }
    if (RESOLVIENDO.get() != null) {
      return Optional.empty();
    }
    Authentication autenticacion;
    RESOLVIENDO.set(Boolean.TRUE);
    try {
      autenticacion = SecurityContextHolder.getContext().getAuthentication();
    } finally {
      RESOLVIENDO.remove();
    }
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

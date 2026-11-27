package mx.constructora.organizacion.aplicacion;

import java.time.Instant;
import java.util.OptionalLong;
import java.util.Set;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Permisos;
import mx.constructora.organizacion.Rol;
import mx.constructora.organizacion.infraestructura.RepositorioSegundoFactor;
import mx.constructora.organizacion.infraestructura.RepositorioSegundoFactor.FilaSegundoFactor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Segundo factor (TOTP). Obligatorio para administración y dirección cuando {@code
 * APP_SEGUNDO_FACTOR_EXIGIR=true}; quien lo tiene confirmado lo usa siempre, tenga el rol que
 * tenga.
 *
 * <p>El inicio de sesión todavía no tiene empresa en el contexto: cada consulta corre con {@link
 * ContextoEmpresa#como} con la empresa y el usuario que ya validaron su contraseña. Por eso estos
 * métodos no son transaccionales: cada sentencia es atómica por sí misma.
 */
@Service
public class ServicioSegundoFactor {

  /** Qué falta después de la contraseña. */
  public enum Paso {
    /** Nada: la sesión queda iniciada. */
    NINGUNO("listo"),
    /** El código de 6 dígitos de la app del teléfono. */
    CODIGO("codigo"),
    /** Dar de alta el segundo factor (rol que lo exige y todavía no lo tiene). */
    ALTA("alta");

    private final String clave;

    Paso(String clave) {
      this.clave = clave;
    }

    public String clave() {
      return clave;
    }
  }

  /** Lo que se muestra para darlo de alta: el secreto para capturarlo a mano y el QR. */
  public record Alta(String secreto, String uri) {}

  private static final Set<Rol> OBLIGATORIO = Set.of(Rol.ADMIN, Rol.DIRECCION);

  private final RepositorioSegundoFactor repositorio;
  private final PropiedadesSegundoFactor propiedades;

  ServicioSegundoFactor(
      RepositorioSegundoFactor repositorio, PropiedadesSegundoFactor propiedades) {
    this.repositorio = repositorio;
    this.propiedades = propiedades;
  }

  /** {@code true} si el usuario está obligado a usar segundo factor. */
  public boolean obligatorio(UsuarioSesion usuario) {
    return propiedades.exigir() && usuario.roles().stream().anyMatch(OBLIGATORIO::contains);
  }

  public Paso pasoPara(UsuarioSesion usuario) {
    boolean confirmado = buscar(usuario).map(FilaSegundoFactor::confirmado).orElse(Boolean.FALSE);
    if (confirmado) {
      return Paso.CODIGO;
    }
    return obligatorio(usuario) ? Paso.ALTA : Paso.NINGUNO;
  }

  /** Genera un secreto nuevo (reemplaza un alta sin confirmar). */
  public Alta iniciarAlta(UsuarioSesion usuario) {
    byte[] secreto = CifradoSecreto.nuevoSecreto();
    byte[] cifrado = cifrado().cifrar(secreto, usuario.usuarioId());
    boolean iniciada =
        ContextoEmpresa.como(
            usuario.empresaId(),
            usuario.usuarioId(),
            () -> repositorio.iniciarAlta(usuario.empresaId(), usuario.usuarioId(), cifrado));
    if (!iniciada) {
      throw ErrorNegocio.conflicto(
          "SEGUNDO_FACTOR_ACTIVO", "Ya tienes el segundo factor activo: escribe el código.");
    }
    return new Alta(
        Totp.base32(secreto), Totp.uri(propiedades.emisor(), usuario.getUsername(), secreto));
  }

  /** Confirma el alta con el primer código. {@code false} si el código no es válido. */
  public boolean confirmarAlta(UsuarioSesion usuario, String codigo) {
    FilaSegundoFactor fila = buscar(usuario).orElse(null);
    if (fila == null || fila.confirmado()) {
      return false;
    }
    OptionalLong paso = validar(usuario, fila, codigo);
    return paso.isPresent()
        && ContextoEmpresa.como(
            usuario.empresaId(),
            usuario.usuarioId(),
            () ->
                repositorio.confirmar(usuario.empresaId(), usuario.usuarioId(), paso.getAsLong()));
  }

  /** Revisa el código de quien ya lo tiene. Un código usado no vuelve a servir. */
  public boolean verificar(UsuarioSesion usuario, String codigo) {
    FilaSegundoFactor fila = buscar(usuario).orElse(null);
    if (fila == null || !fila.confirmado()) {
      return false;
    }
    OptionalLong paso = validar(usuario, fila, codigo);
    return paso.isPresent()
        && ContextoEmpresa.como(
            usuario.empresaId(),
            usuario.usuarioId(),
            () -> repositorio.usarPaso(usuario.empresaId(), usuario.usuarioId(), paso.getAsLong()));
  }

  /**
   * Borra el segundo factor de otro usuario (perdió el teléfono): en su siguiente inicio de sesión
   * lo vuelve a dar de alta. Nadie restablece el suyo: lo hace otra persona de administración.
   */
  @PreAuthorize("hasAuthority('" + Permisos.USUARIOS_ADMINISTRAR + "')")
  public void restablecer(long usuarioId) {
    var identidad = ContextoEmpresa.requerida();
    if (identidad.usuarioId() == usuarioId) {
      throw ErrorNegocio.conflicto(
          "SEGUNDO_FACTOR_PROPIO",
          "Tu propio segundo factor lo restablece otra persona de administración.");
    }
    if (!repositorio.borrar(identidad.empresaId(), usuarioId)) {
      throw ErrorNegocio.noEncontrado(
          "SIN_SEGUNDO_FACTOR", "El usuario no tiene segundo factor dado de alta.");
    }
  }

  /** Usuarios de la empresa en sesión con el segundo factor confirmado. */
  @PreAuthorize("hasAuthority('" + Permisos.USUARIOS_ADMINISTRAR + "')")
  public Set<Long> conSegundoFactor() {
    return Set.copyOf(repositorio.conSegundoFactor(ContextoEmpresa.requerida().empresaId()));
  }

  private java.util.Optional<FilaSegundoFactor> buscar(UsuarioSesion usuario) {
    return ContextoEmpresa.como(
        usuario.empresaId(),
        usuario.usuarioId(),
        () -> repositorio.buscar(usuario.empresaId(), usuario.usuarioId()));
  }

  private OptionalLong validar(UsuarioSesion usuario, FilaSegundoFactor fila, String codigo) {
    byte[] secreto = cifrado().descifrar(fila.secreto(), usuario.usuarioId());
    OptionalLong paso = Totp.verificar(secreto, codigo, Instant.now());
    if (paso.isPresent() && paso.getAsLong() <= fila.ultimoPaso()) {
      return OptionalLong.empty();
    }
    return paso;
  }

  private CifradoSecreto cifrado() {
    return CifradoSecreto.desde(propiedades.llave());
  }
}

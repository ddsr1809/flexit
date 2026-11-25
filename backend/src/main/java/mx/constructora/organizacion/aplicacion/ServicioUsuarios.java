package mx.constructora.organizacion.aplicacion;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Permisos;
import mx.constructora.organizacion.Rol;
import mx.constructora.organizacion.infraestructura.RepositorioUsuarios;
import mx.constructora.organizacion.infraestructura.RepositorioUsuarios.FilaUsuario;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Alta y consulta de usuarios de la empresa en curso. */
@Service
public class ServicioUsuarios {

  /** Longitud mínima de contraseña. */
  public static final int CONTRASENA_MINIMA = 10;

  /** Datos para dar de alta un usuario. */
  public record NuevoUsuario(
      String usuario, String nombre, String correo, String contrasena, Set<String> roles) {}

  private final RepositorioUsuarios repositorio;
  private final PasswordEncoder codificador;

  public ServicioUsuarios(RepositorioUsuarios repositorio, PasswordEncoder codificador) {
    this.repositorio = repositorio;
    this.codificador = codificador;
  }

  @PreAuthorize("hasAuthority('" + Permisos.USUARIOS_ADMINISTRAR + "')")
  @Transactional(readOnly = true)
  public List<FilaUsuario> listar() {
    return repositorio.listar(ContextoEmpresa.requerida().empresaId());
  }

  @PreAuthorize("hasAuthority('" + Permisos.USUARIOS_ADMINISTRAR + "')")
  @Transactional
  public FilaUsuario crear(NuevoUsuario nuevo) {
    Set<Rol> roles = new LinkedHashSet<>();
    for (String clave : nuevo.roles()) {
      roles.add(
          Rol.deClave(clave)
              .orElseThrow(
                  () -> ErrorNegocio.invalido("ROL_DESCONOCIDO", "No existe el rol " + clave)));
    }
    if (nuevo.contrasena().length() < CONTRASENA_MINIMA) {
      throw ErrorNegocio.invalido(
          "CONTRASENA_CORTA",
          "La contraseña debe tener al menos " + CONTRASENA_MINIMA + " caracteres");
    }
    long empresa = ContextoEmpresa.requerida().empresaId();
    String usuario = nuevo.usuario().trim().toLowerCase(Locale.ROOT);
    long id = insertar(empresa, usuario, nuevo);
    roles.forEach(rol -> repositorio.asignarRol(empresa, id, rol));
    return new FilaUsuario(
        id, usuario, nuevo.nombre().trim(), blancoANulo(nuevo.correo()), true, List.copyOf(roles));
  }

  /** Lleva la cuenta de intentos de inicio de sesión (5 fallidos bloquean 15 minutos). */
  public void registrarIntento(String usuario, boolean exito) {
    repositorio.registrarIntento(usuario, exito);
  }

  private long insertar(long empresa, String usuario, NuevoUsuario nuevo) {
    try {
      return repositorio.insertar(
          empresa,
          usuario,
          nuevo.nombre().trim(),
          blancoANulo(nuevo.correo()),
          codificador.encode(nuevo.contrasena()));
    } catch (DuplicateKeyException e) {
      throw ErrorNegocio.conflicto("USUARIO_DUPLICADO", "Ya existe el usuario " + usuario);
    }
  }

  private static String blancoANulo(String texto) {
    return texto == null || texto.isBlank() ? null : texto.trim();
  }
}

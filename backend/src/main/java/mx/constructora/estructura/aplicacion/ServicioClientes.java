package mx.constructora.estructura.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.function.Supplier;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Permisos;
import mx.constructora.compartido.Textos;
import mx.constructora.compartido.Versiones;
import mx.constructora.estructura.infraestructura.RepositorioClientes;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catálogos que encabezan la estructura: clientes, frentes y prototipos, más los tipos de unidad
 * del sistema. Todos los ven; solo administración los cambia.
 */
@Service
public class ServicioClientes {

  /** RFC de persona moral (12) o física (13); vacío se guarda como sin RFC. */
  static final String RFC = "^\\s*$|^\\s*[A-ZÑ&]{3,4}[0-9]{6}[A-Z0-9]{3}\\s*$";

  private static final String ADMINISTRAR = "hasAuthority('" + Permisos.ADMINISTRAR + "')";

  /** Quien paga la obra: Ruba, gobierno, una cadena. */
  public record Cliente(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String nombre,
      String rfc,
      @Schema(requiredMode = REQUIRED) boolean activo,
      @Schema(requiredMode = REQUIRED) int version) {}

  /** Alta o cambio de cliente. */
  public record DatosCliente(
      @NotBlank @Size(max = 160) String nombre,
      @Pattern(regexp = RFC, flags = Pattern.Flag.CASE_INSENSITIVE, message = "RFC inválido")
          String rfc,
      Boolean activo) {}

  /** Fraccionamiento u obra de un cliente. */
  public record Frente(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) long clienteId,
      @Schema(requiredMode = REQUIRED) String cliente,
      @Schema(requiredMode = REQUIRED) String clave,
      @Schema(requiredMode = REQUIRED) String nombre,
      @Schema(requiredMode = REQUIRED) boolean activo,
      @Schema(requiredMode = REQUIRED) int version) {}

  /** Alta o cambio de frente. La clave corta encabeza las claves de lote al importar. */
  public record DatosFrente(
      @NotNull Long clienteId,
      @NotBlank
          @Pattern(
              regexp = "^\\s*[A-Za-z0-9]{2,12}\\s*$",
              message = "De 2 a 12 letras o números, sin espacios")
          String clave,
      @NotBlank @Size(max = 160) String nombre,
      Boolean activo) {}

  /** Prototipo de vivienda. */
  public record Prototipo(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String clave,
      @Schema(requiredMode = REQUIRED) String nombre,
      String descripcion,
      @Schema(requiredMode = REQUIRED) boolean activo,
      @Schema(requiredMode = REQUIRED) int version) {}

  /** Alta o cambio de prototipo. */
  public record DatosPrototipo(
      @NotBlank
          @Pattern(
              regexp = "^\\s*[A-Za-z0-9][A-Za-z0-9-]{0,19}\\s*$",
              message = "Hasta 20 letras, números o guiones, como P-65")
          String clave,
      @NotBlank @Size(max = 120) String nombre,
      @Size(max = 300) String descripcion,
      Boolean activo) {}

  /** Tipo de unidad de obra (catálogo del sistema). */
  public record TipoUnidad(
      @Schema(requiredMode = REQUIRED) int id,
      @Schema(requiredMode = REQUIRED) String clave,
      @Schema(requiredMode = REQUIRED) String nombre,
      @Schema(requiredMode = REQUIRED) boolean usaManzanaLote) {}

  private final RepositorioClientes repositorio;

  public ServicioClientes(RepositorioClientes repositorio) {
    this.repositorio = repositorio;
  }

  // ------------------------------------------------------------------------------- Clientes

  @Transactional(readOnly = true)
  public List<Cliente> clientes() {
    return repositorio.clientes(empresa());
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public Cliente crearCliente(DatosCliente datos) {
    long id =
        unico(
            () ->
                repositorio.insertarCliente(
                    empresa(), Textos.limpio(datos.nombre()), Textos.mayusculas(datos.rfc())),
            "CLIENTE_DUPLICADO",
            "Ya existe un cliente con ese nombre o RFC");
    return repositorio.cliente(empresa(), id).orElseThrow();
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public Cliente cambiarCliente(long id, int version, DatosCliente datos) {
    Cliente actual = clienteExistente(id);
    int filas =
        unico(
            () ->
                repositorio.actualizarCliente(
                    empresa(),
                    id,
                    version,
                    Textos.limpio(datos.nombre()),
                    Textos.mayusculas(datos.rfc()),
                    datos.activo() == null ? actual.activo() : datos.activo()),
            "CLIENTE_DUPLICADO",
            "Ya existe un cliente con ese nombre o RFC");
    exigirActualizado(filas);
    return repositorio.cliente(empresa(), id).orElseThrow();
  }

  // ------------------------------------------------------------------------------- Frentes

  @Transactional(readOnly = true)
  public List<Frente> frentes() {
    return repositorio.frentes(empresa());
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public Frente crearFrente(DatosFrente datos) {
    clienteExistente(datos.clienteId());
    long id =
        unico(
            () ->
                repositorio.insertarFrente(
                    empresa(),
                    datos.clienteId(),
                    Textos.mayusculas(datos.clave()),
                    Textos.limpio(datos.nombre())),
            "FRENTE_DUPLICADO",
            "Ya existe un frente con esa clave");
    return repositorio.frente(empresa(), id).orElseThrow();
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public Frente cambiarFrente(long id, int version, DatosFrente datos) {
    Frente actual =
        repositorio
            .frente(empresa(), id)
            .orElseThrow(() -> ErrorNegocio.noEncontrado("FRENTE_NO_ENCONTRADO", "No existe"));
    clienteExistente(datos.clienteId());
    if (actual.clienteId() != datos.clienteId()
        && repositorio.contratosDelFrente(empresa(), id) > 0) {
      throw ErrorNegocio.conflicto(
          "FRENTE_CON_CONTRATOS",
          "El frente ya tiene contratos: no se puede pasar a otro cliente");
    }
    int filas =
        unico(
            () ->
                repositorio.actualizarFrente(
                    empresa(),
                    id,
                    version,
                    datos.clienteId(),
                    Textos.mayusculas(datos.clave()),
                    Textos.limpio(datos.nombre()),
                    datos.activo() == null ? actual.activo() : datos.activo()),
            "FRENTE_DUPLICADO",
            "Ya existe un frente con esa clave");
    exigirActualizado(filas);
    return repositorio.frente(empresa(), id).orElseThrow();
  }

  // ------------------------------------------------------------------------------- Prototipos

  @Transactional(readOnly = true)
  public List<Prototipo> prototipos() {
    return repositorio.prototipos(empresa());
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public Prototipo crearPrototipo(DatosPrototipo datos) {
    long id =
        unico(
            () ->
                repositorio.insertarPrototipo(
                    empresa(),
                    Textos.mayusculas(datos.clave()),
                    Textos.limpio(datos.nombre()),
                    Textos.limpio(datos.descripcion())),
            "PROTOTIPO_DUPLICADO",
            "Ya existe un prototipo con esa clave");
    return repositorio.prototipo(empresa(), id).orElseThrow();
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public Prototipo cambiarPrototipo(long id, int version, DatosPrototipo datos) {
    Prototipo actual =
        repositorio
            .prototipo(empresa(), id)
            .orElseThrow(
                () ->
                    ErrorNegocio.noEncontrado(
                        "PROTOTIPO_NO_ENCONTRADO", "No existe el prototipo"));
    int filas =
        unico(
            () ->
                repositorio.actualizarPrototipo(
                    empresa(),
                    id,
                    version,
                    Textos.mayusculas(datos.clave()),
                    Textos.limpio(datos.nombre()),
                    Textos.limpio(datos.descripcion()),
                    datos.activo() == null ? actual.activo() : datos.activo()),
            "PROTOTIPO_DUPLICADO",
            "Ya existe un prototipo con esa clave");
    exigirActualizado(filas);
    return repositorio.prototipo(empresa(), id).orElseThrow();
  }

  @Transactional(readOnly = true)
  public List<TipoUnidad> tiposUnidad() {
    return repositorio.tiposUnidad();
  }

  // ------------------------------------------------------------------------------- Apoyo

  private Cliente clienteExistente(long id) {
    return repositorio
        .cliente(empresa(), id)
        .orElseThrow(() -> ErrorNegocio.invalido("CLIENTE_NO_ENCONTRADO", "No existe el cliente"));
  }

  private static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }

  /** Cero filas en un UPDATE con versión: el registro cambió desde que se leyó. */
  static void exigirActualizado(int filas) {
    if (filas == 0) {
      throw Versiones.desactualizada();
    }
  }

  /** Corre la escritura y traduce la llave duplicada a un error que el usuario entiende. */
  static <T> T unico(Supplier<T> escritura, String codigo, String mensaje) {
    try {
      return escritura.get();
    } catch (DuplicateKeyException e) {
      throw ErrorNegocio.conflicto(codigo, mensaje);
    }
  }
}

package mx.constructora.estructura.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static mx.constructora.estructura.aplicacion.ServicioClientes.exigirActualizado;
import static mx.constructora.estructura.aplicacion.ServicioClientes.unico;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import mx.constructora.compartido.Autorizacion;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Permisos;
import mx.constructora.compartido.Textos;
import mx.constructora.estructura.CodigoContrato;
import mx.constructora.estructura.infraestructura.RepositorioClientes;
import mx.constructora.estructura.infraestructura.RepositorioContratos;
import mx.constructora.estructura.infraestructura.RepositorioPaquetes;
import mx.constructora.organizacion.DirectorioUsuarios;
import mx.constructora.organizacion.DirectorioUsuarios.UsuarioResumen;
import mx.constructora.organizacion.Rol;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contratos: el centro de costo. Incluye quién los ve (residentes asignados) y el precio de cada
 * paquete en el contrato. Un usuario sin {@code estructura:ver-todo} solo ve sus contratos; para
 * él, un contrato ajeno "no existe" (404), igual que uno de otra empresa.
 */
@Service
public class ServicioContratos {

  private static final String ADMINISTRAR = "hasAuthority('" + Permisos.ADMINISTRAR + "')";

  /** Contrato con su cliente, frente y cuántas unidades de obra tiene. */
  public record Contrato(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) long clienteId,
      @Schema(requiredMode = REQUIRED) String cliente,
      Long frenteId,
      String frente,
      @Schema(requiredMode = REQUIRED) String codigo,
      String numeroObra,
      String descripcion,
      LocalDate inicio,
      LocalDate fin,
      @Schema(description = "Solo para quien ve costos") BigDecimal monto,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"activo", "terminado", "cancelado"})
          String estado,
      @Schema(requiredMode = REQUIRED) int unidades,
      @Schema(requiredMode = REQUIRED) int version) {

    Contrato sinMonto() {
      return new Contrato(
          id,
          clienteId,
          cliente,
          frenteId,
          frente,
          codigo,
          numeroObra,
          descripcion,
          inicio,
          fin,
          null,
          estado,
          unidades,
          version);
    }
  }

  /** Alta o cambio de contrato. El código se guarda normalizado: "/" → "-", mayúsculas. */
  public record DatosContrato(
      @NotNull Long clienteId,
      Long frenteId,
      @NotBlank @Size(max = 40) String codigo,
      @Pattern(
              regexp = "^\\s*$|^\\s*[A-Za-z0-9-]{1,20}\\s*$",
              message = "Hasta 20 letras, números o guiones")
          String numeroObra,
      @Size(max = 300) String descripcion,
      LocalDate inicio,
      LocalDate fin,
      @PositiveOrZero @Digits(integer = 14, fraction = 2) BigDecimal monto,
      @Pattern(regexp = "activo|terminado|cancelado") String estado) {}

  /** Usuarios asignados a un contrato. */
  public record Residentes(@NotNull @Size(max = 50) List<@NotNull Long> usuarioIds) {}

  /** Precio de un paquete en un contrato, opcionalmente por prototipo. */
  public record PrecioPaquete(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) long paqueteId,
      @Schema(requiredMode = REQUIRED) String paqueteClave,
      @Schema(requiredMode = REQUIRED) String paquete,
      @Schema(requiredMode = REQUIRED) boolean deCliente,
      Long prototipoId,
      String prototipo,
      BigDecimal precioCobro,
      BigDecimal precioDestajo,
      @Schema(requiredMode = REQUIRED) int version) {}

  /** Fija (alta o cambio) el precio de un paquete en el contrato. */
  public record DatosPrecio(
      @NotNull Long paqueteId,
      Long prototipoId,
      @PositiveOrZero @Digits(integer = 14, fraction = 2) BigDecimal precioCobro,
      @PositiveOrZero @Digits(integer = 14, fraction = 2) BigDecimal precioDestajo) {}

  private final RepositorioContratos repositorio;
  private final RepositorioClientes clientes;
  private final RepositorioPaquetes paquetes;
  private final DirectorioUsuarios usuarios;

  public ServicioContratos(
      RepositorioContratos repositorio,
      RepositorioClientes clientes,
      RepositorioPaquetes paquetes,
      DirectorioUsuarios usuarios) {
    this.repositorio = repositorio;
    this.clientes = clientes;
    this.paquetes = paquetes;
    this.usuarios = usuarios;
  }

  // ------------------------------------------------------------------------------- Contratos

  @Transactional(readOnly = true)
  public List<Contrato> contratos(String estado, Long frenteId) {
    return repositorio.contratos(empresa(), Alcance.restringidoA(), estado, frenteId).stream()
        .map(ServicioContratos::segunPermiso)
        .toList();
  }

  @Transactional(readOnly = true)
  public Contrato contrato(long id) {
    return segunPermiso(visible(id));
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public Contrato crearContrato(DatosContrato datos) {
    validar(datos);
    String codigo = codigo(datos.codigo());
    long id =
        unico(
            () ->
                repositorio.insertar(
                    empresa(),
                    datos.clienteId(),
                    datos.frenteId(),
                    codigo,
                    Textos.mayusculas(datos.numeroObra()),
                    Textos.limpio(datos.descripcion()),
                    datos.inicio(),
                    datos.fin(),
                    datos.monto(),
                    datos.estado() == null ? "activo" : datos.estado()),
            "CONTRATO_DUPLICADO",
            "Ya existe el contrato " + codigo);
    return repositorio.contrato(empresa(), null, id).orElseThrow();
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public Contrato cambiarContrato(long id, int version, DatosContrato datos) {
    Contrato actual = visible(id);
    validar(datos);
    boolean cambiaDueno =
        actual.clienteId() != datos.clienteId()
            || !Objects.equals(actual.frenteId(), datos.frenteId());
    if (cambiaDueno && actual.unidades() > 0) {
      throw ErrorNegocio.conflicto(
          "CONTRATO_CON_UNIDADES",
          "El contrato ya tiene lotes: no se puede cambiar de cliente ni de frente");
    }
    String codigo = codigo(datos.codigo());
    int filas =
        unico(
            () ->
                repositorio.actualizar(
                    empresa(),
                    id,
                    version,
                    datos.clienteId(),
                    datos.frenteId(),
                    codigo,
                    Textos.mayusculas(datos.numeroObra()),
                    Textos.limpio(datos.descripcion()),
                    datos.inicio(),
                    datos.fin(),
                    datos.monto(),
                    datos.estado() == null ? actual.estado() : datos.estado()),
            "CONTRATO_DUPLICADO",
            "Ya existe el contrato " + codigo);
    exigirActualizado(filas);
    return repositorio.contrato(empresa(), null, id).orElseThrow();
  }

  // ------------------------------------------------------------------------------- Residentes

  @Transactional(readOnly = true)
  public List<UsuarioResumen> residentes(long contratoId) {
    visible(contratoId);
    return usuarios.buscar(repositorio.residentes(empresa(), contratoId));
  }

  /** Usuarios activos con rol residente: los que se pueden asignar a un contrato. */
  @PreAuthorize(ADMINISTRAR)
  @Transactional(readOnly = true)
  public List<UsuarioResumen> candidatosResidente() {
    return usuarios.activosConRol(Rol.RESIDENTE);
  }

  /** Reemplaza la lista de usuarios asignados al contrato. */
  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public List<UsuarioResumen> asignarResidentes(long contratoId, Residentes datos) {
    visible(contratoId);
    Set<Long> pedidos = new LinkedHashSet<>(datos.usuarioIds());
    List<UsuarioResumen> encontrados = usuarios.buscar(pedidos);
    if (encontrados.size() != pedidos.size()) {
      throw ErrorNegocio.invalido("USUARIO_NO_ENCONTRADO", "Uno de los usuarios no existe");
    }
    Set<Long> actuales = new LinkedHashSet<>(repositorio.residentes(empresa(), contratoId));
    for (Long usuario : actuales) {
      if (!pedidos.contains(usuario)) {
        repositorio.quitarResidente(empresa(), contratoId, usuario);
      }
    }
    for (Long usuario : pedidos) {
      if (!actuales.contains(usuario)) {
        repositorio.agregarResidente(empresa(), contratoId, usuario);
      }
    }
    return encontrados;
  }

  // ------------------------------------------------------------------------------- Precios

  @PreAuthorize(
      "hasAnyAuthority('"
          + Permisos.COSTO_VER_TODO
          + "', '"
          + Permisos.COSTO_VER_PROPIO
          + "', '"
          + Permisos.ADMINISTRAR
          + "')")
  @Transactional(readOnly = true)
  public List<PrecioPaquete> precios(long contratoId) {
    visible(contratoId);
    return repositorio.precios(empresa(), contratoId);
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public PrecioPaquete fijarPrecio(long contratoId, DatosPrecio datos) {
    Contrato contrato = visible(contratoId);
    if (datos.precioCobro() == null && datos.precioDestajo() == null) {
      throw ErrorNegocio.invalido(
          "PRECIO_VACIO", "Escribe el precio de cobro, el de destajo o ambos");
    }
    var paquete =
        paquetes
            .paquete(empresa(), datos.paqueteId())
            .orElseThrow(
                () -> ErrorNegocio.invalido("PAQUETE_NO_ENCONTRADO", "No existe el paquete"));
    if (paquete.clienteId() != null && paquete.clienteId() != contrato.clienteId()) {
      throw ErrorNegocio.invalido(
          "PAQUETE_DE_OTRO_CLIENTE", "El paquete es del catálogo de otro cliente");
    }
    if (datos.prototipoId() != null
        && clientes.prototipo(empresa(), datos.prototipoId()).isEmpty()) {
      throw ErrorNegocio.invalido("PROTOTIPO_NO_ENCONTRADO", "No existe el prototipo");
    }
    long id =
        repositorio.fijarPrecio(
            empresa(),
            contratoId,
            datos.paqueteId(),
            datos.prototipoId(),
            datos.precioCobro(),
            datos.precioDestajo());
    return repositorio.precios(empresa(), contratoId).stream()
        .filter(precio -> precio.id() == id)
        .findFirst()
        .orElseThrow();
  }

  // ------------------------------------------------------------------------------- Apoyo

  /** El contrato, si existe y está al alcance del usuario; si no, 404. */
  Contrato visible(long id) {
    return repositorio
        .contrato(empresa(), Alcance.restringidoA(), id)
        .orElseThrow(
            () -> ErrorNegocio.noEncontrado("CONTRATO_NO_ENCONTRADO", "No existe el contrato"));
  }

  private void validar(DatosContrato datos) {
    if (clientes.cliente(empresa(), datos.clienteId()).isEmpty()) {
      throw ErrorNegocio.invalido("CLIENTE_NO_ENCONTRADO", "No existe el cliente");
    }
    if (datos.frenteId() != null) {
      var frente =
          clientes
              .frente(empresa(), datos.frenteId())
              .orElseThrow(
                  () -> ErrorNegocio.invalido("FRENTE_NO_ENCONTRADO", "No existe el frente"));
      if (frente.clienteId() != datos.clienteId()) {
        throw ErrorNegocio.invalido(
            "FRENTE_DE_OTRO_CLIENTE", "El frente " + frente.nombre() + " es de otro cliente");
      }
    }
    if (datos.inicio() != null && datos.fin() != null && datos.fin().isBefore(datos.inicio())) {
      throw ErrorNegocio.invalido(
          "FECHAS_INVERTIDAS", "La fecha de fin es anterior a la de inicio");
    }
  }

  private static String codigo(String texto) {
    return CodigoContrato.normalizar(texto)
        .orElseThrow(
            () ->
                ErrorNegocio.invalido(
                    "CODIGO_INVALIDO",
                    "El código del contrato solo lleva letras, números y guiones"));
  }

  private static Contrato segunPermiso(Contrato contrato) {
    return Autorizacion.tieneAlguno(Permisos.COSTO_VER_TODO, Permisos.COSTO_VER_PROPIO)
        ? contrato
        : contrato.sinMonto();
  }

  private static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }
}

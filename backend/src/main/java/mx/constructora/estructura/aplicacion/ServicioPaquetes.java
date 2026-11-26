package mx.constructora.estructura.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static mx.constructora.estructura.aplicacion.ServicioClientes.exigirActualizado;
import static mx.constructora.estructura.aplicacion.ServicioClientes.unico;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Permisos;
import mx.constructora.compartido.Textos;
import mx.constructora.estructura.infraestructura.RepositorioClientes;
import mx.constructora.estructura.infraestructura.RepositorioPaquetes;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paquetes: la unidad de trabajo con que se cobra al cliente, se paga destajo y se carga material.
 * Hay un catálogo por cliente (paquetes Ruba, con su precio de cobro) y uno propio (destajo RM); la
 * equivalencia dice qué paquetes propios forman cada paquete del cliente.
 */
@Service
public class ServicioPaquetes {

  private static final String ADMINISTRAR = "hasAuthority('" + Permisos.ADMINISTRAR + "')";

  /** Paquete de un catálogo. {@code clienteId} vacío: catálogo propio. */
  public record Paquete(
      @Schema(requiredMode = REQUIRED) long id,
      Long clienteId,
      String cliente,
      Long padreId,
      @Schema(requiredMode = REQUIRED) String clave,
      @Schema(requiredMode = REQUIRED) String nombre,
      String unidad,
      @Schema(requiredMode = REQUIRED) int orden,
      @Schema(requiredMode = REQUIRED) boolean activo,
      @Schema(requiredMode = REQUIRED) int version) {}

  /** Alta o cambio de paquete. El catálogo (cliente o propio) no cambia después del alta. */
  public record DatosPaquete(
      Long clienteId,
      Long padreId,
      @NotBlank
          @Pattern(
              regexp = "^\\s*[A-Za-z0-9]+([.-][A-Za-z0-9]+)*\\s*$",
              message = "Letras y números separados por punto o guion, como 2.1")
          @Size(max = 20)
          String clave,
      @NotBlank @Size(max = 160) String nombre,
      @Size(max = 20) String unidad,
      Integer orden,
      Boolean activo) {}

  /** Un paquete del cliente equivale (en parte) a un paquete propio. */
  public record Equivalencia(
      @Schema(requiredMode = REQUIRED) long paqueteClienteId,
      @Schema(requiredMode = REQUIRED) long paquetePropioId) {}

  /** Paquetes propios que forman un paquete del cliente. */
  public record Equivalencias(@NotNull @Size(max = 200) List<@NotNull Long> paquetePropioIds) {}

  private final RepositorioPaquetes repositorio;
  private final RepositorioClientes clientes;

  public ServicioPaquetes(RepositorioPaquetes repositorio, RepositorioClientes clientes) {
    this.repositorio = repositorio;
    this.clientes = clientes;
  }

  /**
   * Paquetes en orden de árbol (cada padre antes que sus hijos).
   *
   * @param clienteId solo el catálogo de ese cliente
   * @param propios solo el catálogo propio (se ignora si viene {@code clienteId})
   */
  @Transactional(readOnly = true)
  public List<Paquete> paquetes(Long clienteId, boolean propios) {
    return repositorio.paquetes(empresa(), clienteId, clienteId == null && propios);
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public Paquete crear(DatosPaquete datos) {
    if (datos.clienteId() != null && clientes.cliente(empresa(), datos.clienteId()).isEmpty()) {
      throw ErrorNegocio.invalido("CLIENTE_NO_ENCONTRADO", "No existe el cliente");
    }
    validarPadre(null, datos.clienteId(), datos.padreId());
    long id =
        unico(
            () ->
                repositorio.insertar(
                    empresa(),
                    datos.clienteId(),
                    datos.padreId(),
                    Textos.mayusculas(datos.clave()),
                    Textos.limpio(datos.nombre()),
                    Textos.limpio(datos.unidad()),
                    datos.orden() == null ? 0 : datos.orden()),
            "PAQUETE_DUPLICADO",
            "Ya existe un paquete con esa clave en el catálogo");
    return repositorio.paquete(empresa(), id).orElseThrow();
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public Paquete cambiar(long id, int version, DatosPaquete datos) {
    Paquete actual = existente(id);
    if (!Objects.equals(actual.clienteId(), datos.clienteId())) {
      throw ErrorNegocio.invalido(
          "CATALOGO_NO_CAMBIA", "Un paquete no se mueve de un catálogo a otro");
    }
    validarPadre(id, datos.clienteId(), datos.padreId());
    int filas =
        unico(
            () ->
                repositorio.actualizar(
                    empresa(),
                    id,
                    version,
                    datos.padreId(),
                    Textos.mayusculas(datos.clave()),
                    Textos.limpio(datos.nombre()),
                    Textos.limpio(datos.unidad()),
                    datos.orden() == null ? actual.orden() : datos.orden(),
                    datos.activo() == null ? actual.activo() : datos.activo()),
            "PAQUETE_DUPLICADO",
            "Ya existe un paquete con esa clave en el catálogo");
    exigirActualizado(filas);
    return repositorio.paquete(empresa(), id).orElseThrow();
  }

  @Transactional(readOnly = true)
  public List<Equivalencia> equivalencias(Long clienteId) {
    return repositorio.equivalencias(empresa(), clienteId);
  }

  /** Reemplaza los paquetes propios equivalentes al paquete del cliente {@code id}. */
  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public List<Equivalencia> fijarEquivalencias(long id, Equivalencias datos) {
    Paquete paquete = existente(id);
    if (paquete.clienteId() == null) {
      throw ErrorNegocio.invalido(
          "PAQUETE_NO_ES_DE_CLIENTE",
          "Las equivalencias se fijan desde el paquete del cliente hacia los propios");
    }
    Set<Long> pedidos = new LinkedHashSet<>(datos.paquetePropioIds());
    for (Long propio : pedidos) {
      Paquete otro =
          repositorio
              .paquete(empresa(), propio)
              .orElseThrow(
                  () -> ErrorNegocio.invalido("PAQUETE_NO_ENCONTRADO", "No existe el paquete"));
      if (otro.clienteId() != null) {
        throw ErrorNegocio.invalido(
            "PAQUETE_NO_ES_PROPIO", "El paquete " + otro.clave() + " no es del catálogo propio");
      }
    }
    Set<Long> actuales = new LinkedHashSet<>(repositorio.propiosEquivalentes(empresa(), id));
    for (Long propio : actuales) {
      if (!pedidos.contains(propio)) {
        repositorio.quitarEquivalencia(empresa(), id, propio);
      }
    }
    for (Long propio : pedidos) {
      if (!actuales.contains(propio)) {
        repositorio.agregarEquivalencia(empresa(), id, propio);
      }
    }
    return pedidos.stream().map(propio -> new Equivalencia(id, propio)).toList();
  }

  private void validarPadre(Long id, Long clienteId, Long padreId) {
    if (padreId == null) {
      return;
    }
    Paquete padre =
        repositorio
            .paquete(empresa(), padreId)
            .orElseThrow(
                () -> ErrorNegocio.invalido("PAQUETE_NO_ENCONTRADO", "No existe el paquete padre"));
    if (!Objects.equals(padre.clienteId(), clienteId)) {
      throw ErrorNegocio.invalido("PADRE_DE_OTRO_CATALOGO", "El paquete padre es de otro catálogo");
    }
    if (id != null
        && (padreId.equals(id) || repositorio.descendientes(empresa(), id).contains(padreId))) {
      throw ErrorNegocio.invalido("PADRE_CICLICO", "Un paquete no puede quedar dentro de sí mismo");
    }
  }

  private Paquete existente(long id) {
    return repositorio
        .paquete(empresa(), id)
        .orElseThrow(
            () -> ErrorNegocio.noEncontrado("PAQUETE_NO_ENCONTRADO", "No existe el paquete"));
  }

  private static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }
}

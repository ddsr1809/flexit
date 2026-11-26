package mx.constructora.organizacion.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Permisos;
import mx.constructora.compartido.Textos;
import mx.constructora.compartido.Versiones;
import mx.constructora.organizacion.Personas.PersonaNombre;
import mx.constructora.organizacion.infraestructura.RepositorioPersonas;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personas: trabajadores del SUA y del destajo, solicitantes del almacén. Son datos personales:
 * todo exige {@code personas:administrar}. Aquí se completa el nombre de quien se dio de alta desde
 * un apodo y se captura NSS o CURP cuando falte.
 */
@Service
public class ServicioPersonas {

  static final String ADMINISTRAR = "hasAuthority('" + Permisos.PERSONAS_ADMINISTRAR + "')";

  static final String ELEGIR =
      "hasAnyAuthority('"
          + Permisos.PERSONAS_ADMINISTRAR
          + "', '"
          + Permisos.MOVIMIENTO_CAPTURAR
          + "', '"
          + Permisos.VALE_AUTORIZAR
          + "', '"
          + Permisos.DOCUMENTO_AUTORIZAR
          + "')";

  /** Resultados por búsqueda. */
  public static final int LIMITE = 200;

  /** Resultados de la búsqueda rápida de nombres. */
  public static final int SUGERENCIAS = 20;

  /** Persona con sus datos. */
  public record Persona(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String nombre,
      String nss,
      String curp,
      @Schema(requiredMode = REQUIRED, description = "Alta desde un apodo o posible duplicado")
          boolean revisar,
      String nota,
      @Schema(requiredMode = REQUIRED) boolean activa,
      @Schema(requiredMode = REQUIRED, description = "La dio de alta una carga de Excel")
          boolean importada,
      @Schema(requiredMode = REQUIRED) int version) {}

  /** Cambio de una persona. */
  public record DatosPersona(
      @NotBlank @Size(max = 160) String nombre,
      @Pattern(regexp = "^\\s*$|^\\s*[0-9]{11}\\s*$", message = "11 dígitos") String nss,
      @Pattern(
              regexp =
                  "^\\s*$|^\\s*[A-Za-z][AEIOUXaeioux][A-Za-z]{2}[0-9]{6}[HMhm][A-Za-z]{5}[A-Za-z0-9][0-9]\\s*$",
              message = "CURP de 18 caracteres")
          String curp,
      Boolean revisar,
      @Size(max = 300) String nota,
      Boolean activa) {}

  private final RepositorioPersonas repositorio;

  public ServicioPersonas(RepositorioPersonas repositorio) {
    this.repositorio = repositorio;
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional(readOnly = true)
  public List<Persona> buscar(String texto, boolean soloRevisar) {
    return repositorio.buscar(empresa(), Textos.limpio(texto), soloRevisar, LIMITE);
  }

  /**
   * Para elegir al solicitante de un vale o al responsable de un préstamo: solo nombres de personas
   * activas, sin NSS, CURP ni notas. Lo usa quien captura o autoriza en el almacén.
   */
  @PreAuthorize(ELEGIR)
  @Transactional(readOnly = true)
  public List<PersonaNombre> nombres(String texto) {
    String busqueda = Textos.limpio(texto);
    if (busqueda == null) {
      return List.of();
    }
    return repositorio.buscarNombres(empresa(), busqueda, SUGERENCIAS);
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional(readOnly = true)
  public Persona persona(long id) {
    return existente(id);
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public Persona cambiar(long id, int version, DatosPersona datos) {
    Persona actual = existente(id);
    int cambiadas;
    try {
      cambiadas =
          repositorio.actualizar(
              empresa(),
              id,
              version,
              Textos.limpio(datos.nombre()),
              Textos.limpio(datos.nss()),
              Textos.mayusculas(datos.curp()),
              datos.revisar() == null ? actual.revisar() : datos.revisar(),
              Textos.limpio(datos.nota()),
              datos.activa() == null ? actual.activa() : datos.activa());
    } catch (DuplicateKeyException e) {
      throw ErrorNegocio.conflicto("PERSONA_DUPLICADA", "Otra persona ya tiene ese NSS o CURP");
    }
    if (cambiadas == 0) {
      throw Versiones.desactualizada();
    }
    return existente(id);
  }

  private Persona existente(long id) {
    return repositorio
        .persona(empresa(), id)
        .orElseThrow(
            () -> ErrorNegocio.noEncontrado("PERSONA_NO_ENCONTRADA", "No existe la persona"));
  }

  private static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }
}

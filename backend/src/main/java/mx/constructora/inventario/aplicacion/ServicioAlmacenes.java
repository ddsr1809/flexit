package mx.constructora.inventario.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static mx.constructora.inventario.aplicacion.Sesion.ADMINISTRAR;
import static mx.constructora.inventario.aplicacion.Sesion.empresa;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Textos;
import mx.constructora.compartido.Versiones;
import mx.constructora.estructura.EstructuraApi;
import mx.constructora.estructura.EstructuraApi.FrenteInfo;
import mx.constructora.inventario.infraestructura.RepositorioAlmacenes;
import mx.constructora.inventario.infraestructura.RepositorioAlmacenes.FilaAlmacen;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Almacenes: uno por frente. Los ve cualquier usuario con sesión; los da de alta administración.
 */
@Service
public class ServicioAlmacenes {

  /** Almacén con su frente. */
  public record Almacen(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) long frenteId,
      @Schema(requiredMode = REQUIRED) String frente,
      @Schema(requiredMode = REQUIRED) String clave,
      @Schema(requiredMode = REQUIRED) String nombre,
      @Schema(requiredMode = REQUIRED) boolean activo,
      @Schema(requiredMode = REQUIRED) int version) {}

  /** Alta o cambio. El frente no cambia después del alta. */
  public record DatosAlmacen(
      @NotNull Long frenteId,
      @NotBlank
          @Pattern(regexp = "^\\s*[A-Za-z0-9]{2,12}\\s*$", message = "De 2 a 12 letras o números")
          String clave,
      @NotBlank @Size(max = 120) String nombre,
      Boolean activo) {}

  private final RepositorioAlmacenes repositorio;
  private final EstructuraApi estructura;

  public ServicioAlmacenes(RepositorioAlmacenes repositorio, EstructuraApi estructura) {
    this.repositorio = repositorio;
    this.estructura = estructura;
  }

  @Transactional(readOnly = true)
  public List<Almacen> almacenes() {
    Map<Long, FrenteInfo> frentes =
        estructura.frentes().stream()
            .collect(Collectors.toMap(FrenteInfo::id, Function.identity()));
    return repositorio.almacenes(empresa()).stream().map(a -> presentar(a, frentes)).toList();
  }

  @Transactional(readOnly = true)
  public Almacen almacen(long id) {
    return presentar(existente(id));
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public Almacen crear(DatosAlmacen datos) {
    FrenteInfo frente =
        estructura
            .frente(datos.frenteId())
            .orElseThrow(
                () -> ErrorNegocio.invalido("FRENTE_NO_ENCONTRADO", "No existe el frente"));
    if (!frente.activo()) {
      throw ErrorNegocio.conflicto("FRENTE_INACTIVO", "El frente está desactivado");
    }
    try {
      long id =
          repositorio.insertar(
              empresa(),
              frente.id(),
              Textos.mayusculas(datos.clave()),
              Textos.limpio(datos.nombre()));
      return almacen(id);
    } catch (DuplicateKeyException e) {
      throw ErrorNegocio.conflicto(
          "ALMACEN_DUPLICADO", "Ese frente ya tiene almacén o la clave ya existe");
    }
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public Almacen cambiar(long id, int version, DatosAlmacen datos) {
    FilaAlmacen actual = existente(id);
    if (datos.frenteId() != actual.frenteId()) {
      throw ErrorNegocio.conflicto("ALMACEN_CAMBIA_FRENTE", "El frente de un almacén no cambia");
    }
    int filas;
    try {
      filas =
          repositorio.actualizar(
              empresa(),
              id,
              version,
              Textos.mayusculas(datos.clave()),
              Textos.limpio(datos.nombre()),
              datos.activo() == null ? actual.activo() : datos.activo());
    } catch (DuplicateKeyException e) {
      throw ErrorNegocio.conflicto("ALMACEN_DUPLICADO", "La clave ya existe");
    }
    if (filas == 0) {
      throw Versiones.desactualizada();
    }
    return almacen(id);
  }

  public FilaAlmacen existente(long id) {
    return repositorio
        .almacen(empresa(), id)
        .orElseThrow(
            () -> ErrorNegocio.noEncontrado("ALMACEN_NO_ENCONTRADO", "No existe el almacén"));
  }

  /** El almacén activo, o un error claro. */
  public FilaAlmacen activo(Long id) {
    if (id == null) {
      throw ErrorNegocio.invalido("FALTA_ALMACEN", "Elige el almacén");
    }
    FilaAlmacen almacen =
        repositorio
            .almacen(empresa(), id)
            .orElseThrow(
                () -> ErrorNegocio.invalido("ALMACEN_NO_ENCONTRADO", "No existe el almacén"));
    if (!almacen.activo()) {
      throw ErrorNegocio.conflicto(
          "ALMACEN_INACTIVO", "El almacén " + almacen.clave() + " está desactivado");
    }
    return almacen;
  }

  private Almacen presentar(FilaAlmacen a) {
    return presentar(
        a,
        estructura.frente(a.frenteId()).stream()
            .collect(Collectors.toMap(FrenteInfo::id, Function.identity())));
  }

  private static Almacen presentar(FilaAlmacen a, Map<Long, FrenteInfo> frentes) {
    FrenteInfo frente = frentes.get(a.frenteId());
    return new Almacen(
        a.id(),
        a.frenteId(),
        frente == null ? "" : frente.nombre(),
        a.clave(),
        a.nombre(),
        a.activo(),
        a.version());
  }
}

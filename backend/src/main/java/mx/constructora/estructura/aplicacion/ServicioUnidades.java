package mx.constructora.estructura.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static mx.constructora.estructura.aplicacion.ServicioClientes.exigirActualizado;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Permisos;
import mx.constructora.estructura.ClaveUnidad;
import mx.constructora.estructura.aplicacion.ServicioClientes.TipoUnidad;
import mx.constructora.estructura.aplicacion.ServicioContratos.Contrato;
import mx.constructora.estructura.infraestructura.RepositorioClientes;
import mx.constructora.estructura.infraestructura.RepositorioUnidades;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unidades de obra de un contrato. Los lotes se dan de alta por manzana y lista de lotes ({@code
 * 29-31, 35}) para no capturar uno por uno; las demás unidades (edificio, tramo, área común) por
 * clave. Un lote no se repite en el frente, aunque sea en otro contrato.
 */
@Service
public class ServicioUnidades {

  private static final String ADMINISTRAR = "hasAuthority('" + Permisos.ADMINISTRAR + "')";

  /** Lote, edificio o tramo de un contrato. */
  public record UnidadObra(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) long contratoId,
      @Schema(requiredMode = REQUIRED) String tipo,
      @Schema(requiredMode = REQUIRED) String clave,
      String manzana,
      String lote,
      Long prototipoId,
      String prototipo,
      BigDecimal presupuestoMaterial,
      @Schema(requiredMode = REQUIRED) boolean activa,
      @Schema(requiredMode = REQUIRED) int version) {}

  /**
   * Alta de unidades. Para lotes: {@code manzana} y {@code lotes} ({@code "29-31, 35"}); para otros
   * tipos: {@code clave}.
   */
  public record NuevasUnidades(
      @NotBlank @Schema(example = "LOTE") String tipo,
      @Size(max = 10) String manzana,
      @Size(max = 500) @Schema(example = "29-31, 35") String lotes,
      @Size(max = 60) String clave,
      Long prototipoId,
      @PositiveOrZero @Digits(integer = 14, fraction = 2) BigDecimal presupuestoMaterial) {}

  /** Cambio de una unidad: prototipo, presupuesto de material y si está activa. */
  public record DatosUnidad(
      Long prototipoId,
      @PositiveOrZero @Digits(integer = 14, fraction = 2) BigDecimal presupuestoMaterial,
      @NotNull Boolean activa) {}

  private final RepositorioUnidades repositorio;
  private final RepositorioClientes clientes;
  private final ServicioContratos contratos;

  public ServicioUnidades(
      RepositorioUnidades repositorio, RepositorioClientes clientes, ServicioContratos contratos) {
    this.repositorio = repositorio;
    this.clientes = clientes;
    this.contratos = contratos;
  }

  @Transactional(readOnly = true)
  public List<UnidadObra> unidades(long contratoId) {
    contratos.visible(contratoId);
    return repositorio.unidades(empresa(), contratoId);
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public List<UnidadObra> crearUnidades(long contratoId, NuevasUnidades datos) {
    Contrato contrato = contratos.visible(contratoId);
    if (!"activo".equals(contrato.estado())) {
      throw ErrorNegocio.conflicto(
          "CONTRATO_NO_ACTIVO", "Solo se agregan unidades a un contrato activo");
    }
    TipoUnidad tipo =
        clientes.tiposUnidad().stream()
            .filter(t -> t.clave().equals(datos.tipo().trim().toUpperCase(Locale.ROOT)))
            .findFirst()
            .orElseThrow(
                () -> ErrorNegocio.invalido("TIPO_UNIDAD_INVALIDO", "No existe el tipo de unidad"));
    validarPrototipo(datos.prototipoId());

    List<ClaveUnidad> lotes = new ArrayList<>();
    List<String> claves = new ArrayList<>();
    try {
      if (tipo.usaManzanaLote()) {
        String manzana = ClaveUnidad.numero(requerido(datos.manzana(), "la manzana"));
        for (String lote : ClaveUnidad.expandirLotes(datos.lotes())) {
          ClaveUnidad clave = new ClaveUnidad(manzana, lote);
          lotes.add(clave);
          claves.add(clave.clave());
        }
      } else {
        claves.add(ClaveUnidad.claveLibre(requerido(datos.clave(), "la clave")));
      }
    } catch (IllegalArgumentException e) {
      throw ErrorNegocio.invalido("UNIDAD_INVALIDA", e.getMessage());
    }

    List<String> repetidas =
        repositorio.clavesExistentes(empresa(), contratoId, contrato.frenteId(), claves);
    if (!repetidas.isEmpty()) {
      throw ErrorNegocio.conflicto(
          "UNIDAD_DUPLICADA",
          "Ya existen en "
              + (contrato.frente() == null ? "el contrato" : contrato.frente())
              + ": "
              + String.join(", ", repetidas.subList(0, Math.min(10, repetidas.size())))
              + (repetidas.size() > 10 ? " y " + (repetidas.size() - 10) + " más" : ""));
    }

    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < claves.size(); i++) {
      ClaveUnidad lote = lotes.isEmpty() ? null : lotes.get(i);
      ids.add(
          repositorio.insertar(
              empresa(),
              contratoId,
              contrato.frenteId(),
              tipo.id(),
              claves.get(i),
              lote == null ? null : lote.manzana(),
              lote == null ? null : lote.lote(),
              datos.prototipoId(),
              datos.presupuestoMaterial()));
    }
    return repositorio.unidades(empresa(), contratoId).stream()
        .filter(unidad -> ids.contains(unidad.id()))
        .toList();
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public UnidadObra cambiarUnidad(long id, int version, DatosUnidad datos) {
    UnidadObra actual =
        repositorio
            .unidad(empresa(), id)
            .orElseThrow(
                () -> ErrorNegocio.noEncontrado("UNIDAD_NO_ENCONTRADA", "No existe la unidad"));
    contratos.visible(actual.contratoId());
    validarPrototipo(datos.prototipoId());
    exigirActualizado(
        repositorio.actualizar(
            empresa(),
            id,
            version,
            datos.prototipoId(),
            datos.presupuestoMaterial(),
            datos.activa()));
    return repositorio.unidad(empresa(), id).orElseThrow();
  }

  private void validarPrototipo(Long prototipoId) {
    if (prototipoId != null && clientes.prototipo(empresa(), prototipoId).isEmpty()) {
      throw ErrorNegocio.invalido("PROTOTIPO_NO_ENCONTRADO", "No existe el prototipo");
    }
  }

  private static String requerido(String texto, String que) {
    if (texto == null || texto.isBlank()) {
      throw new IllegalArgumentException("Falta " + que);
    }
    return texto;
  }

  private static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }
}

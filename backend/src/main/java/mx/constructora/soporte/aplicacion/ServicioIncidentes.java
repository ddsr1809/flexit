package mx.constructora.soporte.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import mx.constructora.compartido.Autorizacion;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Folios;
import mx.constructora.compartido.Permisos;
import mx.constructora.compartido.Textos;
import mx.constructora.compartido.Versiones;
import mx.constructora.inventario.InventarioApi;
import mx.constructora.inventario.InventarioApi.AlmacenInfo;
import mx.constructora.organizacion.DirectorioUsuarios;
import mx.constructora.organizacion.DirectorioUsuarios.UsuarioResumen;
import mx.constructora.soporte.infraestructura.RepositorioIncidentes;
import mx.constructora.soporte.infraestructura.RepositorioIncidentes.FilaIncidente;
import mx.constructora.soporte.infraestructura.RepositorioIncidentes.FilaNota;
import mx.constructora.soporte.infraestructura.RepositorioIncidentes.NuevoIncidente;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Incidentes del piloto (H9, ADR-015). Cualquier usuario reporta lo que no funciona, con su
 * severidad; administración ({@code incidente:atender}) lo clasifica, lo liga a un issue de GitHub
 * y lo resuelve o lo descarta. Quien reportó ve los suyos, agrega notas y lo reabre si sigue
 * pasando. Cada cambio deja una nota que no se borra.
 *
 * <p>Severidad: <b>crítica</b> si el almacén no puede registrar entradas o salidas, o el sistema
 * dejó mal una existencia, un costo o un permiso; <b>mayor</b> si se puede seguir con un rodeo o
 * un dato importante se ve mal; <b>menor</b> para lo demás.
 */
@Service
public class ServicioIncidentes {

  static final String ATENDER = "hasAuthority('" + Permisos.INCIDENTE_ATENDER + "')";
  static final String AUTENTICADO = "isAuthenticated()";
  static final String SEVERIDADES = "critica|mayor|menor";

  /** Lo que se reporta. La pantalla y el identificador de la petición los llena el frontend. */
  public record DatosIncidente(
      @NotNull @Pattern(regexp = SEVERIDADES) String severidad,
      @NotBlank @Size(min = 3, max = 120) String titulo,
      @NotBlank @Size(max = 2000) String descripcion,
      Long almacenId,
      @Size(max = 200) String pantalla,
      @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String peticion,
      @Size(max = 40) String documento) {}

  /** Lo que cambia quien atiende; la nota explica por qué. */
  public record ClasificacionIncidente(
      @NotNull @Pattern(regexp = SEVERIDADES) String severidad,
      @Positive Integer issue,
      @Size(max = 1000) String nota) {}

  /** Una nota del seguimiento. */
  public record NotaIncidente(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {
                "nota",
                "severidad",
                "issue",
                "resuelto",
                "descartado",
                "reabierto"
              })
          String tipo,
      @Schema(requiredMode = REQUIRED) String texto,
      @Schema(requiredMode = REQUIRED) String usuario,
      @Schema(requiredMode = REQUIRED) OffsetDateTime creado) {}

  /** Un incidente en la lista. */
  public record IncidenteResumen(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"critica", "mayor", "menor"})
          String severidad,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"abierto", "resuelto", "descartado"})
          String estado,
      @Schema(requiredMode = REQUIRED) String titulo,
      Long almacenId,
      String almacen,
      Integer issue,
      @Schema(requiredMode = REQUIRED) String reporto,
      @Schema(requiredMode = REQUIRED) OffsetDateTime reportadoEn,
      OffsetDateTime cerradoEn,
      @Schema(requiredMode = REQUIRED) int reaperturas) {}

  /** Un incidente con su seguimiento. */
  public record Incidente(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"critica", "mayor", "menor"})
          String severidad,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"abierto", "resuelto", "descartado"})
          String estado,
      @Schema(requiredMode = REQUIRED) String titulo,
      @Schema(requiredMode = REQUIRED) String descripcion,
      Long almacenId,
      String almacen,
      String pantalla,
      @Schema(description = "X-Request-Id de la petición que falló") String peticion,
      @Schema(description = "Folio del documento relacionado") String documento,
      @Schema(description = "Número del issue en GitHub") Integer issue,
      @Schema(requiredMode = REQUIRED) String reporto,
      @Schema(requiredMode = REQUIRED) OffsetDateTime reportadoEn,
      String cerro,
      OffsetDateTime cerradoEn,
      String resolucion,
      @Schema(requiredMode = REQUIRED) int reaperturas,
      @Schema(requiredMode = REQUIRED, description = "Quien consulta lo puede clasificar y cerrar")
          boolean puedeAtender,
      @Schema(requiredMode = REQUIRED) List<NotaIncidente> notas,
      @Schema(requiredMode = REQUIRED) int version) {}

  private final RepositorioIncidentes repositorio;
  private final InventarioApi inventario;
  private final DirectorioUsuarios usuarios;
  private final Folios folios;

  public ServicioIncidentes(
      RepositorioIncidentes repositorio,
      InventarioApi inventario,
      DirectorioUsuarios usuarios,
      Folios folios) {
    this.repositorio = repositorio;
    this.inventario = inventario;
    this.usuarios = usuarios;
    this.folios = folios;
  }

  @PreAuthorize(AUTENTICADO)
  @Transactional
  public Incidente reportar(DatosIncidente datos) {
    if (datos.almacenId() != null && inventario.almacen(datos.almacenId()).isEmpty()) {
      throw ErrorNegocio.invalido("ALMACEN_NO_ENCONTRADO", "No existe el almacén");
    }
    String titulo = Textos.limpio(datos.titulo());
    String descripcion = datos.descripcion() == null ? null : datos.descripcion().strip();
    if (titulo == null || titulo.length() < 3 || descripcion == null || descripcion.isEmpty()) {
      throw ErrorNegocio.invalido("INCIDENTE_INCOMPLETO", "Escribe qué pasó y un título corto");
    }
    long id =
        repositorio.insertar(
            empresa(),
            folios.siguiente("INC"),
            new NuevoIncidente(
                datos.severidad(),
                titulo,
                descripcion,
                datos.almacenId(),
                Textos.limpio(datos.pantalla()),
                Textos.limpio(datos.peticion()),
                Textos.mayusculas(datos.documento())),
            usuario());
    return incidente(id);
  }

  /** Quien atiende ve todos; los demás, solo los que reportaron. */
  @PreAuthorize(AUTENTICADO)
  @Transactional(readOnly = true)
  public List<IncidenteResumen> incidentes(String estado, String severidad, Long almacenId) {
    Long soloDe = atiende() ? null : usuario();
    List<FilaIncidente> filas =
        repositorio.incidentes(
            empresa(), Textos.limpio(estado), Textos.limpio(severidad), almacenId, soloDe);
    Map<Long, String> nombres =
        nombres(filas.stream().map(FilaIncidente::reportadoPor).collect(Collectors.toSet()));
    Map<Long, String> almacenes = almacenes();
    return filas.stream()
        .map(
            f ->
                new IncidenteResumen(
                    f.id(),
                    f.folio(),
                    f.severidad(),
                    f.estado(),
                    f.titulo(),
                    f.almacenId(),
                    f.almacenId() == null ? null : almacenes.get(f.almacenId()),
                    f.issue(),
                    nombres.getOrDefault(f.reportadoPor(), ""),
                    f.reportadoEn(),
                    f.cerradoEn(),
                    f.reaperturas()))
        .toList();
  }

  @PreAuthorize(AUTENTICADO)
  @Transactional(readOnly = true)
  public Incidente incidente(long id) {
    return presentar(visible(id, false));
  }

  @PreAuthorize(ATENDER)
  @Transactional
  public Incidente clasificar(long id, int version, ClasificacionIncidente datos) {
    FilaIncidente f = abierto(visible(id, true));
    if (f.version() != version) {
      throw Versiones.desactualizada();
    }
    if (!repositorio.clasificar(empresa(), id, version, datos.severidad(), datos.issue())) {
      throw Versiones.desactualizada();
    }
    if (!f.severidad().equals(datos.severidad())) {
      repositorio.nota(
          empresa(),
          id,
          "severidad",
          "De " + nombreSeveridad(f.severidad()) + " a " + nombreSeveridad(datos.severidad()),
          usuario());
    }
    if (!Objects.equals(f.issue(), datos.issue())) {
      repositorio.nota(
          empresa(),
          id,
          "issue",
          datos.issue() == null ? "Sin issue en GitHub" : "Issue #" + datos.issue() + " en GitHub",
          usuario());
    }
    String nota = Textos.limpio(datos.nota());
    if (nota != null) {
      repositorio.nota(empresa(), id, "nota", nota, usuario());
    }
    return incidente(id);
  }

  @PreAuthorize(ATENDER)
  @Transactional
  public Incidente resolver(long id, String resolucion) {
    return cerrar(id, "resuelto", resolucion);
  }

  @PreAuthorize(ATENDER)
  @Transactional
  public Incidente descartar(long id, String motivo) {
    return cerrar(id, "descartado", motivo);
  }

  /** Sigue pasando: quien lo reportó o quien atiende lo vuelve a abrir. */
  @PreAuthorize(AUTENTICADO)
  @Transactional
  public Incidente reabrir(long id, String motivo) {
    FilaIncidente f = visible(id, true);
    if (f.estado().equals("abierto")) {
      throw ErrorNegocio.conflicto(
          "INCIDENTE_ABIERTO", "El incidente " + f.folio() + " ya está abierto");
    }
    String texto = requerido(motivo, "Escribe por qué se reabre");
    repositorio.reabrir(empresa(), id);
    repositorio.nota(empresa(), id, "reabierto", texto, usuario());
    return incidente(id);
  }

  /** Quien lo reportó o quien atiende agrega lo que se sabe. */
  @PreAuthorize(AUTENTICADO)
  @Transactional
  public Incidente anotar(long id, String texto) {
    visible(id, true);
    repositorio.nota(empresa(), id, "nota", requerido(texto, "Escribe la nota"), usuario());
    return incidente(id);
  }

  // ------------------------------------------------------------------------------ Internos

  private Incidente cerrar(long id, String estado, String texto) {
    FilaIncidente f = abierto(visible(id, true));
    String resolucion =
        requerido(
            texto,
            estado.equals("resuelto")
                ? "Escribe qué se hizo para resolverlo"
                : "Escribe por qué se descarta");
    repositorio.cerrar(empresa(), f.id(), estado, resolucion, usuario());
    repositorio.nota(empresa(), f.id(), estado, resolucion, usuario());
    return incidente(id);
  }

  /** El incidente, si quien consulta lo puede ver; si no, 404 (no se distingue a propósito). */
  private FilaIncidente visible(long id, boolean bloquear) {
    return repositorio
        .incidente(empresa(), id, bloquear)
        .filter(f -> atiende() || f.reportadoPor() == usuario())
        .orElseThrow(
            () -> ErrorNegocio.noEncontrado("INCIDENTE_NO_ENCONTRADO", "No existe el incidente"));
  }

  private static FilaIncidente abierto(FilaIncidente f) {
    if (!f.estado().equals("abierto")) {
      throw ErrorNegocio.conflicto(
          "INCIDENTE_CERRADO",
          "El incidente " + f.folio() + " está " + f.estado() + ": para cambiarlo, reábrelo");
    }
    return f;
  }

  private Incidente presentar(FilaIncidente f) {
    List<FilaNota> notas = repositorio.notas(empresa(), f.id());
    Set<Long> ids = new HashSet<>();
    ids.add(f.reportadoPor());
    if (f.cerradoPor() != null) {
      ids.add(f.cerradoPor());
    }
    notas.forEach(n -> ids.add(n.usuarioId()));
    Map<Long, String> nombres = nombres(ids);
    return new Incidente(
        f.id(),
        f.folio(),
        f.severidad(),
        f.estado(),
        f.titulo(),
        f.descripcion(),
        f.almacenId(),
        f.almacenId() == null ? null : almacenes().get(f.almacenId()),
        f.pantalla(),
        f.peticion(),
        f.documento(),
        f.issue(),
        nombres.getOrDefault(f.reportadoPor(), ""),
        f.reportadoEn(),
        f.cerradoPor() == null ? null : nombres.getOrDefault(f.cerradoPor(), ""),
        f.cerradoEn(),
        f.resolucion(),
        f.reaperturas(),
        atiende(),
        notas.stream()
            .map(
                n ->
                    new NotaIncidente(
                        n.id(),
                        n.tipo(),
                        n.texto(),
                        nombres.getOrDefault(n.usuarioId(), ""),
                        n.creado()))
            .toList(),
        f.version());
  }

  private Map<Long, String> nombres(Set<Long> ids) {
    return usuarios.buscar(ids).stream()
        .collect(Collectors.toMap(UsuarioResumen::id, UsuarioResumen::nombre));
  }

  private Map<Long, String> almacenes() {
    return inventario.almacenes().stream()
        .collect(Collectors.toMap(AlmacenInfo::id, AlmacenInfo::clave, (a, b) -> a));
  }

  private static String requerido(String texto, String mensaje) {
    String limpio = texto == null ? null : texto.strip();
    if (limpio == null || limpio.isEmpty()) {
      throw ErrorNegocio.invalido("FALTA_TEXTO", mensaje);
    }
    if (limpio.length() > 1000) {
      throw ErrorNegocio.invalido("TEXTO_LARGO", "El texto pasa de 1,000 caracteres");
    }
    return limpio;
  }

  static String nombreSeveridad(String severidad) {
    return switch (severidad) {
      case "critica" -> "crítica";
      case "mayor" -> "mayor";
      default -> "menor";
    };
  }

  private static boolean atiende() {
    return Autorizacion.tiene(Permisos.INCIDENTE_ATENDER);
  }

  private static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }

  private static long usuario() {
    return ContextoEmpresa.requerida().usuarioId();
  }
}

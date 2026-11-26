package mx.constructora.inventario.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static mx.constructora.inventario.aplicacion.Sesion.ADMINISTRAR;
import static mx.constructora.inventario.aplicacion.Sesion.CONSULTAR;
import static mx.constructora.inventario.aplicacion.Sesion.empresa;
import static mx.constructora.inventario.aplicacion.Sesion.hoy;
import static mx.constructora.inventario.aplicacion.Sesion.usuario;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mx.constructora.catalogo.CatalogoApi.MaterialInfo;
import mx.constructora.compartido.Autorizacion;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Folios;
import mx.constructora.compartido.Permisos;
import mx.constructora.compartido.Textos;
import mx.constructora.inventario.dominio.TipoMovimiento;
import mx.constructora.inventario.dominio.Valuacion;
import mx.constructora.inventario.infraestructura.RepositorioAlmacenes.FilaAlmacen;
import mx.constructora.inventario.infraestructura.RepositorioConteos;
import mx.constructora.inventario.infraestructura.RepositorioConteos.FilaConteo;
import mx.constructora.inventario.infraestructura.RepositorioConteos.FilaLineaConteo;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.Cabecera;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.NuevaLinea;
import mx.constructora.inventario.infraestructura.RepositorioPeriodos;
import mx.constructora.inventario.infraestructura.RepositorioSaldos;
import mx.constructora.inventario.infraestructura.RepositorioSaldos.FilaSaldo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conteo físico. El almacén abre el conteo y captura lo que cuenta; administración lo cierra (quien
 * lo abrió no lo cierra). Al cerrar, lo que debía estar en el anaquel (existencia menos lo
 * prestado) se compara con lo contado: los sobrantes entran como ajuste de entrada y los faltantes
 * salen como ajuste de salida, los dos aplicados en el mismo paso. Mientras el conteo está abierto,
 * el almacén no aplica movimientos.
 *
 * <p>Conteo a ciegas: mientras está abierto, quien captura no ve la existencia del sistema; solo
 * administración.
 */
@Service
public class ServicioConteos {

  private static final String CAPTURAR_O_ADMINISTRAR =
      "hasAnyAuthority('" + Permisos.MOVIMIENTO_CAPTURAR + "', '" + Permisos.ADMINISTRAR + "')";

  /** Conteo. */
  public record Conteo(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(requiredMode = REQUIRED) long almacenId,
      @Schema(requiredMode = REQUIRED) String almacen,
      @Schema(requiredMode = REQUIRED) LocalDate fecha,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"abierto", "cerrado", "cancelado"})
          String estado,
      @Schema(requiredMode = REQUIRED) String abrio,
      String cerro,
      OffsetDateTime cerradoEn,
      String observaciones,
      @Schema(requiredMode = REQUIRED) int lineas,
      @Schema(requiredMode = REQUIRED) int contadas,
      @Schema(requiredMode = REQUIRED) int version) {}

  /** Línea de conteo. La existencia del sistema solo se ve cerrado o con permiso de administrar. */
  public record LineaConteo(
      @Schema(requiredMode = REQUIRED) long materialId,
      @Schema(requiredMode = REQUIRED) String clave,
      @Schema(requiredMode = REQUIRED) String material,
      @Schema(requiredMode = REQUIRED) String unidad,
      BigDecimal contado,
      BigDecimal costoUnitario,
      BigDecimal existenciaSistema,
      BigDecimal diferencia,
      OffsetDateTime contadoEn) {}

  /** Conteo con sus líneas y, si ya se cerró, los ajustes que generó. */
  public record DetalleConteo(
      @Schema(requiredMode = REQUIRED) Conteo conteo,
      @Schema(requiredMode = REQUIRED) List<LineaConteo> lineas) {}

  /** Apertura de un conteo. Sin materiales: todos los que tienen existencia en el almacén. */
  public record DatosConteo(
      @NotNull Long almacenId,
      @NotNull LocalDate fecha,
      @Size(max = 2000) List<Long> materialIds,
      @Size(max = 500) String observaciones) {}

  /** Lo contado de un material. El costo solo hace falta para un sobrante sin costo conocido. */
  public record Contado(
      @NotNull Long materialId,
      @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 4) BigDecimal contado,
      @PositiveOrZero @Digits(integer = 10, fraction = 6) BigDecimal costoUnitario) {}

  /** Captura de varias líneas a la vez. */
  public record CapturaConteo(@NotEmpty @Size(max = 2000) List<@Valid Contado> lineas) {}

  private final RepositorioConteos repositorio;
  private final RepositorioSaldos saldos;
  private final RepositorioMovimientos movimientos;
  private final RepositorioPeriodos periodos;
  private final ServicioAlmacenes almacenes;
  private final ServicioAplicacion aplicacion;
  private final Nombres nombres;
  private final Folios folios;

  public ServicioConteos(
      RepositorioConteos repositorio,
      RepositorioSaldos saldos,
      RepositorioMovimientos movimientos,
      RepositorioPeriodos periodos,
      ServicioAlmacenes almacenes,
      ServicioAplicacion aplicacion,
      Nombres nombres,
      Folios folios) {
    this.repositorio = repositorio;
    this.saldos = saldos;
    this.movimientos = movimientos;
    this.periodos = periodos;
    this.almacenes = almacenes;
    this.aplicacion = aplicacion;
    this.nombres = nombres;
    this.folios = folios;
  }

  @PreAuthorize(CONSULTAR)
  @Transactional(readOnly = true)
  public List<Conteo> conteos(Long almacenId) {
    List<FilaConteo> filas = repositorio.conteos(empresa(), almacenId);
    return presentar(filas);
  }

  @PreAuthorize(CONSULTAR)
  @Transactional(readOnly = true)
  public DetalleConteo conteo(long id) {
    FilaConteo c = existente(id, false);
    boolean veSistema = !c.estado().equals("abierto") || Autorizacion.tiene(Permisos.ADMINISTRAR);
    List<FilaLineaConteo> lineas = repositorio.lineas(empresa(), id);
    Map<Long, MaterialInfo> materiales =
        nombres.materiales(lineas.stream().map(FilaLineaConteo::materialId).toList());
    Map<Long, FilaSaldo> actuales = new HashMap<>();
    if (veSistema && c.estado().equals("abierto")) {
      saldos
          .saldos(empresa(), c.almacenId(), materiales.keySet(), false)
          .forEach(s -> actuales.put(s.materialId(), s));
    }
    List<LineaConteo> salida = new ArrayList<>();
    for (FilaLineaConteo l : lineas) {
      MaterialInfo m = materiales.get(l.materialId());
      BigDecimal sistema = null;
      if (veSistema) {
        if (l.existenciaSistema() != null) {
          sistema = l.existenciaSistema();
        } else {
          FilaSaldo s = actuales.get(l.materialId());
          sistema = s == null ? BigDecimal.ZERO : s.existencia().subtract(s.prestado());
        }
      }
      salida.add(
          new LineaConteo(
              l.materialId(),
              m == null ? "" : m.clave(),
              m == null ? "" : m.nombre(),
              m == null ? "" : m.unidadControl(),
              l.contado(),
              l.costoUnitario(),
              sistema,
              sistema != null && l.contado() != null ? l.contado().subtract(sistema) : null,
              l.contadoEn()));
    }
    salida.sort(
        java.util.Comparator.comparing(LineaConteo::material, String.CASE_INSENSITIVE_ORDER));
    return new DetalleConteo(presentar(List.of(c)).getFirst(), salida);
  }

  @PreAuthorize(CAPTURAR_O_ADMINISTRAR)
  @Transactional
  public DetalleConteo abrir(DatosConteo datos) {
    FilaAlmacen almacen = almacenes.activo(datos.almacenId());
    if (datos.fecha().isAfter(hoy())) {
      throw ErrorNegocio.invalido("FECHA_FUTURA", "La fecha no puede ser posterior a hoy");
    }
    if (periodos.cerrado(empresa(), datos.fecha())) {
      throw ErrorNegocio.conflicto("PERIODO_CERRADO", "Ese mes ya está cerrado");
    }
    long id;
    try {
      id =
          repositorio.insertar(
              empresa(),
              folios.siguiente("CON"),
              almacen.id(),
              datos.fecha(),
              usuario(),
              Textos.limpio(datos.observaciones()));
    } catch (DuplicateKeyException e) {
      throw ErrorNegocio.conflicto("CONTEO_ABIERTO", "El almacén ya tiene un conteo abierto");
    }
    List<Long> materiales =
        datos.materialIds() != null && !datos.materialIds().isEmpty()
            ? validarMateriales(datos.materialIds())
            : saldos.saldos(empresa(), almacen.id(), null, true).stream()
                .map(FilaSaldo::materialId)
                .toList();
    repositorio.agregarMateriales(empresa(), id, materiales);
    return conteo(id);
  }

  @PreAuthorize(CAPTURAR_O_ADMINISTRAR)
  @Transactional
  public DetalleConteo capturar(long id, CapturaConteo captura) {
    FilaConteo c = abierto(id);
    validarMateriales(captura.lineas().stream().map(Contado::materialId).toList());
    for (Contado l : captura.lineas()) {
      repositorio.capturar(
          empresa(),
          c.id(),
          l.materialId(),
          l.contado().setScale(Valuacion.DECIMALES_CANTIDAD, RoundingMode.HALF_UP),
          l.costoUnitario(),
          usuario());
    }
    return conteo(id);
  }

  @PreAuthorize(CAPTURAR_O_ADMINISTRAR)
  @Transactional
  public DetalleConteo cancelar(long id) {
    abierto(id);
    repositorio.cancelar(empresa(), id);
    return conteo(id);
  }

  /** Cierra el conteo y aplica los ajustes. Solo administración y no quien lo abrió. */
  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public DetalleConteo cerrar(long id) {
    FilaConteo c = abierto(id);
    if (c.usuarioCrea() == usuario()) {
      throw new ErrorNegocio(
          HttpStatus.FORBIDDEN, "CIERRA_QUIEN_ABRIO", "Quien abrió el conteo no lo cierra");
    }
    if (periodos.cerrado(empresa(), c.fecha())) {
      throw ErrorNegocio.conflicto("PERIODO_CERRADO", "El mes del conteo ya está cerrado");
    }
    List<FilaLineaConteo> lineas = repositorio.lineas(empresa(), id);
    long sinContar = lineas.stream().filter(l -> l.contado() == null).count();
    if (sinContar > 0) {
      throw ErrorNegocio.conflicto(
          "CONTEO_INCOMPLETO", "Faltan " + sinContar + " materiales por contar");
    }
    Map<Long, MaterialInfo> materiales =
        nombres.materiales(lineas.stream().map(FilaLineaConteo::materialId).toList());

    // Saldos bloqueados en orden de material: nadie mueve el almacén mientras se compara.
    List<NuevaLinea> sobrantes = new ArrayList<>();
    List<NuevaLinea> faltantes = new ArrayList<>();
    Map<Long, BigDecimal> costos = new HashMap<>();
    List<String> sinCosto = new ArrayList<>();
    for (FilaLineaConteo l : lineas) {
      FilaSaldo s = saldos.bloquear(empresa(), c.almacenId(), l.materialId());
      BigDecimal enAnaquel = s.existencia().subtract(s.prestado());
      repositorio.fijarExistenciaSistema(
          empresa(), id, l.materialId(), enAnaquel.max(BigDecimal.ZERO));
      BigDecimal diferencia = l.contado().subtract(enAnaquel);
      MaterialInfo m = materiales.get(l.materialId());
      if (diferencia.signum() > 0) {
        BigDecimal costo = costoSobrante(l, s, m);
        if (costo == null) {
          sinCosto.add(m == null ? String.valueOf(l.materialId()) : m.nombre());
          continue;
        }
        costos.put(l.materialId(), costo);
        sobrantes.add(linea(sobrantes.size() + 1, l.materialId(), diferencia, m));
      } else if (diferencia.signum() < 0) {
        faltantes.add(linea(faltantes.size() + 1, l.materialId(), diferencia.negate(), m));
      }
    }
    if (!sinCosto.isEmpty()) {
      throw ErrorNegocio.invalido(
          "COSTO_REQUERIDO",
          "Escribe el costo de lo que sobró de: "
              + String.join(", ", sinCosto.subList(0, Math.min(10, sinCosto.size()))));
    }
    repositorio.cerrar(empresa(), id, usuario());
    ajuste(c, TipoMovimiento.AJUSTE_SALIDA, faltantes, Map.of());
    ajuste(c, TipoMovimiento.AJUSTE_ENTRADA, sobrantes, costos);
    return conteo(id);
  }

  /**
   * Costo del sobrante: el capturado, el promedio (o último costo) del almacén, o el de referencia.
   */
  private static BigDecimal costoSobrante(FilaLineaConteo l, FilaSaldo s, MaterialInfo m) {
    if (l.costoUnitario() != null) {
      return l.costoUnitario();
    }
    if (s.costoPromedio().signum() > 0) {
      return s.costoPromedio();
    }
    return m == null ? null : m.precioReferencia();
  }

  private static NuevaLinea linea(int renglon, long material, BigDecimal cantidad, MaterialInfo m) {
    return new NuevaLinea(
        renglon,
        material,
        cantidad,
        cantidad,
        m.unidadControlId(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private void ajuste(
      FilaConteo c, TipoMovimiento tipo, List<NuevaLinea> lineas, Map<Long, BigDecimal> costos) {
    if (lineas.isEmpty()) {
      return;
    }
    long id =
        movimientos.insertar(
            empresa(),
            folios.siguiente(tipo.prefijoFolio()),
            new Cabecera(
                tipo.clave(),
                c.fecha(),
                c.almacenId(),
                null,
                null,
                null,
                null,
                null,
                null,
                c.id(),
                null,
                null,
                null,
                "Conteo físico " + c.folio()),
            usuario(),
            null);
    for (NuevaLinea l : lineas) {
      movimientos.insertarLinea(empresa(), id, tipo.clave(), l);
    }
    aplicacion.aplicarBloqueado(movimientos.bloquear(empresa(), id).orElseThrow(), costos);
  }

  private List<Long> validarMateriales(List<Long> ids) {
    Set<Long> unicos = new HashSet<>(ids);
    Map<Long, MaterialInfo> encontrados = nombres.materiales(unicos);
    if (encontrados.size() != unicos.size()) {
      throw ErrorNegocio.invalido("MATERIAL_NO_ENCONTRADO", "Algún material no existe");
    }
    return List.copyOf(unicos);
  }

  private FilaConteo abierto(long id) {
    FilaConteo c = existente(id, true);
    if (!c.estado().equals("abierto")) {
      throw ErrorNegocio.conflicto(
          "CONTEO_CERRADO", "El conteo " + c.folio() + " ya está " + c.estado());
    }
    return c;
  }

  private FilaConteo existente(long id, boolean bloquear) {
    return repositorio
        .conteo(empresa(), id, bloquear)
        .orElseThrow(
            () -> ErrorNegocio.noEncontrado("CONTEO_NO_ENCONTRADO", "No existe el conteo"));
  }

  private List<Conteo> presentar(List<FilaConteo> filas) {
    Set<Long> usuarioIds = new HashSet<>();
    filas.forEach(
        c -> {
          usuarioIds.add(c.usuarioCrea());
          if (c.usuarioCierra() != null) {
            usuarioIds.add(c.usuarioCierra());
          }
        });
    Map<Long, String> usuarios = nombres.usuarios(usuarioIds);
    Map<Long, String> claves = nombres.almacenes();
    return filas.stream()
        .map(
            c ->
                new Conteo(
                    c.id(),
                    c.folio(),
                    c.almacenId(),
                    claves.getOrDefault(c.almacenId(), ""),
                    c.fecha(),
                    c.estado(),
                    usuarios.getOrDefault(c.usuarioCrea(), ""),
                    c.usuarioCierra() == null ? null : usuarios.get(c.usuarioCierra()),
                    c.cerradoEn(),
                    c.observaciones(),
                    c.lineas(),
                    c.contadas(),
                    c.version()))
        .toList();
  }
}

package mx.constructora.inventario.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static mx.constructora.inventario.aplicacion.Sesion.AUTORIZAR;
import static mx.constructora.inventario.aplicacion.Sesion.CAPTURAR;
import static mx.constructora.inventario.aplicacion.Sesion.CONSULTAR;
import static mx.constructora.inventario.aplicacion.Sesion.empresa;
import static mx.constructora.inventario.aplicacion.Sesion.usuario;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import mx.constructora.compartido.Autorizacion;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Folios;
import mx.constructora.compartido.Permisos;
import mx.constructora.compartido.Textos;
import mx.constructora.compartido.Versiones;
import mx.constructora.estructura.EstructuraApi;
import mx.constructora.inventario.aplicacion.Captura.Preparado;
import mx.constructora.inventario.dominio.TipoMovimiento;
import mx.constructora.inventario.dominio.TipoMovimiento.QuienAutoriza;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.FilaLinea;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.FilaMovimiento;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.FilaResumen;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.NuevaLinea;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.RestanteDeLinea;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Captura y ciclo de los documentos del almacén: crear en borrador, corregir, enviar a autorizar,
 * autorizar o rechazar, y cancelar. Aplicar (lo único que mueve existencia) vive en {@link
 * ServicioAplicacion}.
 *
 * <p>Quién autoriza: el vale, el residente del contrato o coordinación ({@code vale:autorizar});
 * traspaso, reasignación, merma y ajuste de costo, coordinación o dirección ({@code
 * documento:autorizar}). Nadie autoriza lo que él mismo capturó (regla 5).
 */
@Service
public class ServicioMovimientos {

  /** Tamaño máximo de página. */
  public static final int PAGINA_MAXIMA = 200;

  /** Una línea como se captura. */
  public record LineaCaptura(
      @Schema(description = "En devolución, reasignación y ajuste se toma de la línea de origen")
          Long materialId,
      @Schema(description = "En la unidad capturada; el ajuste de costo no la usa")
          @Positive
          @Digits(integer = 10, fraction = 4)
          BigDecimal cantidad,
      @Schema(description = "Unidad capturada; vacía: la unidad de control del material")
          Long unidadId,
      @Schema(description = "Recepción: precio sin IVA por unidad capturada")
          @PositiveOrZero
          @Digits(integer = 10, fraction = 6)
          BigDecimal precio,
      @Schema(description = "Recepción: descuento del renglón, en pesos")
          @PositiveOrZero
          @Digits(integer = 12, fraction = 4)
          BigDecimal descuento,
      @Schema(description = "Recepción: tasa de IVA; vacía, la del proveedor")
          @DecimalMin("0")
          @DecimalMax("0.16")
          BigDecimal tasaIva,
      @Schema(description = "Ajuste de costo: costo correcto por unidad de control, sin IVA")
          @PositiveOrZero
          @Digits(integer = 10, fraction = 6)
          BigDecimal costoNuevo,
      Long contratoId,
      @Schema(description = "Vale: uno o varios lotes; con varios, la cantidad se reparte exacta")
          @Size(max = 200)
          List<Long> unidadObraIds,
      Long paqueteId,
      Integer clasificacionId,
      @Schema(description = "Devolución, reasignación, devolución a proveedor y ajuste")
          Long lineaOrigenId) {}

  /** Alta o corrección de un documento. */
  public record DatosMovimiento(
      @NotBlank
          @Schema(
              allowableValues = {
                "entrada",
                "salida",
                "devolucion",
                "devolucion_proveedor",
                "traspaso",
                "reasignacion",
                "merma",
                "ajuste_costo"
              })
          String tipo,
      @NotNull LocalDate fecha,
      @NotNull Long almacenId,
      Long almacenDestinoId,
      Long proveedorId,
      @Size(max = 40) String folioFactura,
      @PositiveOrZero @Digits(integer = 12, fraction = 4) BigDecimal flete,
      Long solicitanteId,
      Long movimientoOrigenId,
      Long archivoId,
      @Size(max = 500) String motivo,
      @Size(max = 500) String observaciones,
      @NotEmpty @Size(max = 500) List<@Valid LineaCaptura> lineas,
      @Schema(description = "Crear y enviar a autorizar en un paso") Boolean enviar) {}

  /** Línea de un documento, con nombres. Costo e importe solo para quien ve costos. */
  public record LineaMovimiento(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) int renglon,
      @Schema(requiredMode = REQUIRED) long materialId,
      @Schema(requiredMode = REQUIRED) String materialClave,
      @Schema(requiredMode = REQUIRED) String material,
      @Schema(requiredMode = REQUIRED) String unidadControl,
      @Schema(requiredMode = REQUIRED) BigDecimal cantidad,
      @Schema(requiredMode = REQUIRED) BigDecimal cantidadCaptura,
      @Schema(requiredMode = REQUIRED) long unidadCapturaId,
      @Schema(requiredMode = REQUIRED) String unidadCaptura,
      BigDecimal precio,
      BigDecimal descuento,
      BigDecimal tasaIva,
      BigDecimal costoNuevo,
      BigDecimal costoUnitario,
      BigDecimal importe,
      Long contratoId,
      String contrato,
      Long unidadObraId,
      String unidadObra,
      Long paqueteId,
      String paquete,
      Integer clasificacionId,
      String clasificacion,
      Long lineaOrigenId) {}

  /** Qué puede hacer el usuario en curso con el documento. */
  public record Acciones(
      @Schema(requiredMode = REQUIRED) boolean editar,
      @Schema(requiredMode = REQUIRED) boolean enviar,
      @Schema(requiredMode = REQUIRED) boolean autorizar,
      @Schema(requiredMode = REQUIRED) boolean aplicar,
      @Schema(requiredMode = REQUIRED) boolean cancelar) {}

  /** Documento completo. */
  public record Movimiento(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(requiredMode = REQUIRED) String tipo,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"borrador", "pendiente", "autorizado", "aplicado", "cancelado"})
          String estado,
      @Schema(requiredMode = REQUIRED) LocalDate fecha,
      @Schema(requiredMode = REQUIRED) long almacenId,
      @Schema(requiredMode = REQUIRED) String almacen,
      Long almacenDestinoId,
      String almacenDestino,
      Long proveedorId,
      String proveedor,
      String folioFactura,
      BigDecimal flete,
      Long solicitanteId,
      String solicitante,
      Long movimientoOrigenId,
      Long conteoId,
      Long prestamoId,
      Long archivoId,
      String motivo,
      String notaRechazo,
      String motivoCancelacion,
      String observaciones,
      @Schema(requiredMode = REQUIRED) String capturo,
      String autorizo,
      String aplico,
      OffsetDateTime autorizadoEn,
      OffsetDateTime aplicadoEn,
      OffsetDateTime canceladoEn,
      @Schema(requiredMode = REQUIRED) OffsetDateTime creado,
      @Schema(description = "Suma de importes; solo para quien ve costos") BigDecimal importe,
      @Schema(requiredMode = REQUIRED) int version,
      @Schema(requiredMode = REQUIRED) List<LineaMovimiento> lineas,
      @Schema(requiredMode = REQUIRED) Acciones acciones) {}

  /** Renglón de las listas. */
  public record ResumenMovimiento(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(requiredMode = REQUIRED) String tipo,
      @Schema(requiredMode = REQUIRED) String estado,
      @Schema(requiredMode = REQUIRED) LocalDate fecha,
      @Schema(requiredMode = REQUIRED) String almacen,
      String almacenDestino,
      @Schema(requiredMode = REQUIRED) String capturo,
      String autorizo,
      String solicitante,
      @Schema(requiredMode = REQUIRED) int lineas,
      @Schema(requiredMode = REQUIRED) List<String> contratos,
      BigDecimal importe,
      @Schema(requiredMode = REQUIRED) OffsetDateTime creado) {}

  /** Lo que queda en obra de una línea de vale (para devolver o reasignar). */
  public record LineaRestante(
      @Schema(requiredMode = REQUIRED) long lineaId,
      @Schema(requiredMode = REQUIRED) BigDecimal cantidad) {}

  private final RepositorioMovimientos repositorio;
  private final Captura captura;
  private final Presentador presentador;
  private final Folios folios;
  private final EstructuraApi estructura;

  public ServicioMovimientos(
      RepositorioMovimientos repositorio,
      Captura captura,
      Presentador presentador,
      Folios folios,
      EstructuraApi estructura) {
    this.repositorio = repositorio;
    this.captura = captura;
    this.presentador = presentador;
    this.folios = folios;
    this.estructura = estructura;
  }

  // ------------------------------------------------------------------------------ Captura

  /**
   * Crea el documento en borrador (o ya enviado a autorizar). Con {@code idempotencia}, repetir la
   * petición devuelve el mismo documento en lugar de crear otro.
   */
  @PreAuthorize(CAPTURAR)
  @Transactional
  public Movimiento crear(DatosMovimiento datos, UUID idempotencia) {
    if (idempotencia != null) {
      var existente = repositorio.porIdempotencia(empresa(), idempotencia);
      if (existente.isPresent()) {
        return movimiento(existente.get());
      }
    }
    TipoMovimiento tipo = tipoCapturable(datos.tipo());
    Preparado preparado = captura.preparar(tipo, datos);
    String folio = folios.siguiente(tipo.prefijoFolio());
    long id = repositorio.insertar(empresa(), folio, preparado.cabecera(), usuario(), idempotencia);
    for (NuevaLinea linea : preparado.lineas()) {
      repositorio.insertarLinea(empresa(), id, tipo.clave(), linea);
    }
    if (Boolean.TRUE.equals(datos.enviar()) && tipo.requiereAutorizacion()) {
      repositorio.enviar(empresa(), id);
    }
    return movimiento(id);
  }

  /** Reemplaza un borrador completo (cabecera y líneas). El tipo no cambia. */
  @PreAuthorize(CAPTURAR)
  @Transactional
  public Movimiento reemplazar(long id, int version, DatosMovimiento datos) {
    FilaMovimiento actual = bloquear(id);
    if (!actual.estado().equals("borrador")) {
      throw ErrorNegocio.conflicto("NO_ES_BORRADOR", "Solo se corrige un documento en borrador");
    }
    TipoMovimiento tipo = tipoCapturable(datos.tipo());
    if (!tipo.clave().equals(actual.tipo())) {
      throw ErrorNegocio.invalido("TIPO_NO_CAMBIA", "El tipo de documento no cambia");
    }
    Preparado preparado = captura.preparar(tipo, datos);
    if (repositorio.actualizarCabecera(empresa(), id, version, preparado.cabecera()) == 0) {
      throw Versiones.desactualizada();
    }
    repositorio.borrarLineas(empresa(), id);
    for (NuevaLinea linea : preparado.lineas()) {
      repositorio.insertarLinea(empresa(), id, tipo.clave(), linea);
    }
    if (Boolean.TRUE.equals(datos.enviar()) && tipo.requiereAutorizacion()) {
      repositorio.enviar(empresa(), id);
    }
    return movimiento(id);
  }

  @PreAuthorize(CAPTURAR)
  @Transactional
  public Movimiento enviar(long id) {
    FilaMovimiento m = bloquear(id);
    TipoMovimiento tipo = tipo(m);
    if (!tipo.requiereAutorizacion()) {
      throw ErrorNegocio.conflicto(
          "NO_REQUIERE_AUTORIZACION", "Este documento no se autoriza: se aplica directo");
    }
    if (!m.estado().equals("borrador")) {
      throw ErrorNegocio.conflicto("NO_ES_BORRADOR", "El documento ya se envió");
    }
    repositorio.enviar(empresa(), id);
    return movimiento(id);
  }

  // ------------------------------------------------------------------------------ Autorización

  @PreAuthorize(AUTORIZAR)
  @Transactional
  public Movimiento autorizar(long id) {
    FilaMovimiento m = bloquear(id);
    exigirPuedeAutorizar(m);
    repositorio.autorizar(empresa(), id, usuario());
    return movimiento(id);
  }

  /** Regresa el documento a borrador con la nota de por qué, para que quien capturó lo corrija. */
  @PreAuthorize(AUTORIZAR)
  @Transactional
  public Movimiento rechazar(long id, String nota) {
    String texto = Textos.limpio(nota);
    if (texto == null) {
      throw ErrorNegocio.invalido("FALTA_NOTA", "Escribe qué hay que corregir");
    }
    FilaMovimiento m = bloquear(id);
    exigirPuedeAutorizar(m);
    repositorio.rechazar(empresa(), id, texto);
    return movimiento(id);
  }

  private void exigirPuedeAutorizar(FilaMovimiento m) {
    if (!m.estado().equals("pendiente")) {
      throw ErrorNegocio.conflicto(
          "MOVIMIENTO_NO_PENDIENTE",
          "El documento " + m.folio() + " no está pendiente de autorizar");
    }
    String falta = motivoParaNoAutorizar(m);
    if (falta != null) {
      throw new ErrorNegocio(HttpStatus.FORBIDDEN, falta, mensajeAutorizar(falta, m));
    }
  }

  /** {@code null} si el usuario en curso puede autorizar {@code m}; si no, el código de por qué. */
  private String motivoParaNoAutorizar(FilaMovimiento m) {
    TipoMovimiento tipo = tipo(m);
    if (tipo.autoriza() == QuienAutoriza.NADIE) {
      return "NO_REQUIERE_AUTORIZACION";
    }
    if (tipo.autoriza() == QuienAutoriza.VALE && !Autorizacion.tiene(Permisos.VALE_AUTORIZAR)) {
      return "SIN_PERMISO";
    }
    if (tipo.autoriza() == QuienAutoriza.COORDINACION
        && !Autorizacion.tiene(Permisos.DOCUMENTO_AUTORIZAR)) {
      return "SIN_PERMISO";
    }
    if (m.usuarioCaptura() == usuario()) {
      return "AUTORIZA_QUIEN_CAPTURO";
    }
    if (!estructura.veTodosLosContratos()) {
      Set<Long> visibles = estructura.contratosVisibles();
      boolean fuera =
          repositorio.lineas(empresa(), m.id()).stream()
              .map(FilaLinea::contratoId)
              .anyMatch(c -> c == null || !visibles.contains(c));
      if (fuera) {
        return "FUERA_DE_ALCANCE";
      }
    }
    return null;
  }

  private static String mensajeAutorizar(String codigo, FilaMovimiento m) {
    return switch (codigo) {
      case "AUTORIZA_QUIEN_CAPTURO" -> "Quien captura un documento no lo autoriza";
      case "FUERA_DE_ALCANCE" ->
          "El vale " + m.folio() + " tiene lotes de contratos que no tienes asignados";
      case "NO_REQUIERE_AUTORIZACION" -> "Este documento no se autoriza";
      default -> "No tienes permiso para autorizar este documento";
    };
  }

  // ------------------------------------------------------------------------------ Consulta

  @PreAuthorize(CONSULTAR)
  @Transactional(readOnly = true)
  public Movimiento movimiento(long id) {
    FilaMovimiento m = repositorio.movimiento(empresa(), id).orElseThrow(() -> noEncontrado());
    List<FilaLinea> lineas = repositorio.lineas(empresa(), id);
    exigirVisible(lineas);
    return presentador.movimiento(m, lineas, acciones(m));
  }

  /** Documentos filtrados. {@code estados}: separados por coma. */
  @PreAuthorize(CONSULTAR)
  @Transactional(readOnly = true)
  public List<ResumenMovimiento> buscar(
      String tipo,
      String estados,
      Long almacenId,
      LocalDate desde,
      LocalDate hasta,
      String folio,
      int pagina,
      int tamano) {
    int limite = Math.clamp(tamano, 1, PAGINA_MAXIMA);
    List<String> listaEstados =
        estados == null || estados.isBlank()
            ? List.of()
            : Arrays.stream(estados.split(","))
                .map(String::trim)
                .filter(e -> !e.isEmpty())
                .toList();
    List<FilaResumen> filas =
        repositorio.buscar(
            empresa(),
            Textos.limpio(tipo),
            listaEstados,
            almacenId,
            desde,
            hasta,
            Textos.limpio(folio),
            alcance(),
            null,
            limite,
            (long) Math.max(pagina, 0) * limite);
    return presentador.resumenes(filas);
  }

  /** Bandeja: lo pendiente que el usuario en curso puede autorizar (sin lo que él capturó). */
  @PreAuthorize(AUTORIZAR)
  @Transactional(readOnly = true)
  public List<ResumenMovimiento> porAutorizar() {
    boolean vales = Autorizacion.tiene(Permisos.VALE_AUTORIZAR);
    boolean documentos = Autorizacion.tiene(Permisos.DOCUMENTO_AUTORIZAR);
    List<FilaResumen> filas =
        repositorio
            .buscar(
                empresa(),
                null,
                List.of("pendiente"),
                null,
                null,
                null,
                null,
                alcance(),
                usuario(),
                PAGINA_MAXIMA,
                0)
            .stream()
            .filter(
                f -> {
                  QuienAutoriza quien = TipoMovimiento.deClave(f.tipo()).orElseThrow().autoriza();
                  return (quien == QuienAutoriza.VALE && vales)
                      || (quien == QuienAutoriza.COORDINACION && documentos);
                })
            .toList();
    if (!estructura.veTodosLosContratos()) {
      // El residente autoriza un vale solo si todos sus lotes son de sus contratos.
      Set<Long> visibles = estructura.contratosVisibles();
      filas =
          filas.stream()
              .filter(
                  f ->
                      f.contratos() != null
                          && Arrays.stream(f.contratos().split(","))
                              .map(Long::valueOf)
                              .allMatch(visibles::contains))
              .toList();
    }
    return presentador.resumenes(filas);
  }

  /** Lo que queda en obra de cada línea de un vale o reasignación aplicado. */
  @PreAuthorize(CONSULTAR)
  @Transactional(readOnly = true)
  public List<LineaRestante> restantes(long id) {
    movimiento(id);
    List<Long> lineas = repositorio.lineas(empresa(), id).stream().map(FilaLinea::id).toList();
    List<RestanteDeLinea> filas = repositorio.restantes(empresa(), lineas);
    List<LineaRestante> resultado = new ArrayList<>();
    for (Long linea : lineas) {
      BigDecimal cantidad =
          filas.stream()
              .filter(f -> f.lineaId() == linea)
              .map(RestanteDeLinea::cantidad)
              .findFirst()
              .orElse(BigDecimal.ZERO);
      resultado.add(new LineaRestante(linea, cantidad));
    }
    return resultado;
  }

  // ------------------------------------------------------------------------------ Apoyo

  /** El documento bloqueado ({@code FOR UPDATE}), si el usuario en curso lo puede ver. */
  public FilaMovimiento bloquear(long id) {
    FilaMovimiento m =
        repositorio.bloquear(empresa(), id).orElseThrow(ServicioMovimientos::noEncontrado);
    exigirVisible(repositorio.lineas(empresa(), id));
    return m;
  }

  private Acciones acciones(FilaMovimiento m) {
    TipoMovimiento tipo = tipo(m);
    boolean captura = Autorizacion.tiene(Permisos.MOVIMIENTO_CAPTURAR);
    boolean esperaAplicar =
        tipo.requiereAutorizacion()
            ? m.estado().equals("autorizado")
            : m.estado().equals("borrador");
    boolean cancelable =
        Set.of("borrador", "pendiente", "autorizado").contains(m.estado())
            || (m.estado().equals("aplicado")
                && (tipo == TipoMovimiento.SALIDA || tipo == TipoMovimiento.ENTRADA));
    return new Acciones(
        captura && m.estado().equals("borrador") && tipo.seCaptura(),
        captura && m.estado().equals("borrador") && tipo.requiereAutorizacion(),
        m.estado().equals("pendiente") && motivoParaNoAutorizar(m) == null,
        captura && esperaAplicar,
        captura && cancelable);
  }

  /** {@code null} si ve todos los contratos; si no, los suyos (residente). */
  private java.util.Collection<Long> alcance() {
    return estructura.veTodosLosContratos() ? null : estructura.contratosVisibles();
  }

  /** El residente solo ve documentos con lotes de sus contratos. */
  private void exigirVisible(List<FilaLinea> lineas) {
    if (estructura.veTodosLosContratos()) {
      return;
    }
    Set<Long> visibles = estructura.contratosVisibles();
    boolean alguno =
        lineas.stream()
            .map(FilaLinea::contratoId)
            .filter(Objects::nonNull)
            .anyMatch(visibles::contains);
    if (!alguno) {
      throw noEncontrado();
    }
  }

  static TipoMovimiento tipo(FilaMovimiento m) {
    return TipoMovimiento.deClave(m.tipo()).orElseThrow();
  }

  private static TipoMovimiento tipoCapturable(String clave) {
    TipoMovimiento tipo =
        TipoMovimiento.deClave(clave == null ? "" : clave.trim())
            .orElseThrow(
                () -> ErrorNegocio.invalido("TIPO_INVALIDO", "No existe ese tipo de documento"));
    if (!tipo.seCaptura()) {
      throw ErrorNegocio.invalido(
          "TIPO_NO_CAPTURABLE", "Los ajustes de existencia salen del conteo físico");
    }
    return tipo;
  }

  static ErrorNegocio noEncontrado() {
    return ErrorNegocio.noEncontrado("MOVIMIENTO_NO_ENCONTRADO", "No existe el documento");
  }
}

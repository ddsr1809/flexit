package mx.constructora.inventario.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static mx.constructora.inventario.aplicacion.Sesion.ADMINISTRAR;
import static mx.constructora.inventario.aplicacion.Sesion.CAPTURAR;
import static mx.constructora.inventario.aplicacion.Sesion.CONSULTAR;
import static mx.constructora.inventario.aplicacion.Sesion.empresa;
import static mx.constructora.inventario.aplicacion.Sesion.hoy;
import static mx.constructora.inventario.aplicacion.Sesion.usuario;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import mx.constructora.catalogo.CatalogoApi;
import mx.constructora.catalogo.CatalogoApi.MaterialInfo;
import mx.constructora.compartido.Autorizacion;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Folios;
import mx.constructora.compartido.LibroExcel;
import mx.constructora.compartido.LibroExcel.Columna;
import mx.constructora.compartido.Permisos;
import mx.constructora.compartido.Textos;
import mx.constructora.compartido.Versiones;
import mx.constructora.historico.ConsultaHistorico;
import mx.constructora.historico.ConsultaHistorico.CostoAlCorte;
import mx.constructora.historico.ConsultaHistorico.ExistenciaExcel;
import mx.constructora.historico.ConsultaHistorico.PrestamoAbierto;
import mx.constructora.inventario.dominio.SaldoApertura;
import mx.constructora.inventario.dominio.SaldoApertura.Costo;
import mx.constructora.inventario.dominio.SaldoApertura.Linea;
import mx.constructora.inventario.dominio.SaldoApertura.Origen;
import mx.constructora.inventario.dominio.SaldoApertura.PrestamoListo;
import mx.constructora.inventario.dominio.TipoMovimiento;
import mx.constructora.inventario.dominio.Valuacion;
import mx.constructora.inventario.infraestructura.RepositorioAlmacenes;
import mx.constructora.inventario.infraestructura.RepositorioAlmacenes.FilaAlmacen;
import mx.constructora.inventario.infraestructura.RepositorioAperturas;
import mx.constructora.inventario.infraestructura.RepositorioAperturas.FilaApertura;
import mx.constructora.inventario.infraestructura.RepositorioAperturas.FilaCosto;
import mx.constructora.inventario.infraestructura.RepositorioAperturas.FilaLinea;
import mx.constructora.inventario.infraestructura.RepositorioAperturas.FilaPrestamoApertura;
import mx.constructora.inventario.infraestructura.RepositorioConteos;
import mx.constructora.inventario.infraestructura.RepositorioConteos.FilaConteo;
import mx.constructora.inventario.infraestructura.RepositorioConteos.FilaLineaConteo;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.Cabecera;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.NuevaLinea;
import mx.constructora.inventario.infraestructura.RepositorioPeriodos;
import mx.constructora.inventario.infraestructura.RepositorioPrestamos;
import mx.constructora.inventario.infraestructura.RepositorioSaldos;
import mx.constructora.organizacion.Personas;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Apertura de un almacén en el corte (H8, issues 41 y 54): el saldo con que arranca el sistema sale
 * del conteo físico, valuado sin IVA y firmado por coordinación y administración.
 *
 * <ol>
 *   <li><b>Abrir</b> (almacén): crea el conteo a ciegas con todos los materiales activos y trae los
 *       préstamos del Excel del frente que siguen abiertos.
 *   <li><b>Contar</b> (almacén, en el conteo): lo que no se encuentre se marca en 0 de forma
 *       explícita.
 *   <li><b>Préstamos</b> (administración): cada préstamo abierto queda listo (material y
 *       responsable; la herramienta que no es material se da de alta) o descartado con motivo. Lo
 *       prestado no está en el anaquel pero sí es del almacén: entra con lo contado y queda
 *       apartado como préstamo vivo.
 *   <li><b>Costos</b> (administración): el sistema propone el ponderado del Excel o el precio de
 *       referencia; administración captura los que falten o corrige los que quiera.
 *   <li><b>Firmar</b>: dos personas distintas que no contaron. La primera firma congela lo que se
 *       firma; la segunda aplica el movimiento de apertura, cierra el conteo y crea los préstamos
 *       vivos, todo en una transacción.
 * </ol>
 *
 * <p>Lo que decía el Excel (inventario inicial + entradas − salidas) se muestra junto a lo contado
 * como referencia: el conteo es el que vale y la diferencia no se busca a quién cargarla.
 */
@Service
public class ServicioAperturas {

  private static final String CAPTURAR_O_ADMINISTRAR =
      "hasAnyAuthority('" + Permisos.MOVIMIENTO_CAPTURAR + "', '" + Permisos.ADMINISTRAR + "')";
  private static final String FIRMAR =
      "hasAnyAuthority('" + Permisos.DOCUMENTO_AUTORIZAR + "', '" + Permisos.ADMINISTRAR + "')";

  /** Apertura con nombres. */
  public record Apertura(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(requiredMode = REQUIRED) long almacenId,
      @Schema(requiredMode = REQUIRED) String almacen,
      @Schema(requiredMode = REQUIRED) LocalDate fecha,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"abierta", "en_firma", "aplicada", "cancelada"})
          String estado,
      @Schema(requiredMode = REQUIRED) long conteoId,
      @Schema(requiredMode = REQUIRED) String conteoFolio,
      @Schema(requiredMode = REQUIRED) String abrio,
      String firmaCoordinacion,
      OffsetDateTime firmaCoordinacionEn,
      String firmaAdministracion,
      OffsetDateTime firmaAdministracionEn,
      Long movimientoId,
      @Schema(description = "Valor firmado, sin IVA (con permiso de ver costos)")
          BigDecimal importe,
      String motivoCancelacion,
      String observaciones,
      @Schema(requiredMode = REQUIRED) int version) {}

  /**
   * Un material. {@code contado} nulo: no se ha contado. Costo e importe solo con permiso; lo que
   * decía el Excel, a quien cuenta solo después de la primera firma (conteo a ciegas).
   */
  public record LineaApertura(
      @Schema(requiredMode = REQUIRED) long materialId,
      @Schema(requiredMode = REQUIRED) String clave,
      @Schema(requiredMode = REQUIRED) String material,
      @Schema(requiredMode = REQUIRED) String unidad,
      BigDecimal contado,
      @Schema(requiredMode = REQUIRED) BigDecimal prestado,
      @Schema(requiredMode = REQUIRED) BigDecimal cantidad,
      BigDecimal costoUnitario,
      @Schema(allowableValues = {"capturado", "historico", "referencia"}) String origenCosto,
      String detalleCosto,
      BigDecimal importe,
      @Schema(requiredMode = REQUIRED) boolean faltaCosto,
      BigDecimal existenciaExcel,
      BigDecimal diferenciaExcel) {}

  /** Un préstamo abierto al corte. */
  public record PrestamoApertura(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(description = "Renglón del histórico de préstamos; nulo si se agregó a mano")
          Long origenId,
      Integer filaExcel,
      @Schema(requiredMode = REQUIRED) String articulo,
      LocalDate fecha,
      @Schema(requiredMode = REQUIRED) BigDecimal cantidad,
      String responsableTexto,
      Long materialId,
      String materialClave,
      String material,
      String unidad,
      Long responsableId,
      String responsable,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"pendiente", "listo", "descartado"})
          String estado,
      String motivo,
      Long prestamoId,
      @Schema(requiredMode = REQUIRED) int version) {}

  /** Cuánto falta y cuánto vale. */
  public record Resumen(
      @Schema(requiredMode = REQUIRED) int materiales,
      @Schema(requiredMode = REQUIRED) int contados,
      @Schema(requiredMode = REQUIRED) int sinContar,
      @Schema(requiredMode = REQUIRED) int conExistencia,
      @Schema(requiredMode = REQUIRED) int sinCosto,
      @Schema(requiredMode = REQUIRED) int prestamosPendientes,
      @Schema(requiredMode = REQUIRED) int prestamosListos,
      @Schema(requiredMode = REQUIRED) int prestamosDescartados,
      BigDecimal importe,
      @Schema(requiredMode = REQUIRED) boolean conFotoExcel,
      Integer conDiferenciaExcel) {}

  /**
   * La apertura completa. {@code huella}: lo que se firma (se manda al firmar). {@code firmaComo}:
   * qué firma le toca a quien consulta (nulo si ninguna). {@code faltaParaFirmar}: lo que impide
   * firmar todavía.
   */
  public record DetalleApertura(
      @Schema(requiredMode = REQUIRED) Apertura apertura,
      @Schema(requiredMode = REQUIRED) Resumen resumen,
      @Schema(requiredMode = REQUIRED) List<LineaApertura> lineas,
      @Schema(requiredMode = REQUIRED) List<PrestamoApertura> prestamos,
      @Schema(requiredMode = REQUIRED) String huella,
      @Schema(allowableValues = {"coordinacion", "administracion"}) String firmaComo,
      @Schema(requiredMode = REQUIRED) List<String> faltaParaFirmar) {}

  /** Abrir la apertura de un almacén. */
  public record DatosApertura(
      @NotNull Long almacenId, @NotNull LocalDate fecha, @Size(max = 500) String observaciones) {}

  /** Lo que decide administración de un préstamo abierto. */
  public record DatosPrestamoApertura(
      Long materialId,
      Long responsableId,
      @NotNull @Positive @Digits(integer = 10, fraction = 4) BigDecimal cantidad,
      @NotNull @Pattern(regexp = "pendiente|listo|descartado") String estado,
      @Size(max = 500) String motivo) {}

  /** Un préstamo que no estaba en el Excel (se encontró al contar). */
  public record NuevoPrestamoApertura(
      @NotBlank @Size(max = 200) String articulo,
      @NotNull @Positive @Digits(integer = 10, fraction = 4) BigDecimal cantidad,
      LocalDate fecha,
      @Size(max = 160) String responsableTexto) {}

  /** Alta de la herramienta de un préstamo como material. Sin nombre, el del Excel. */
  public record Herramienta(@NotNull Long unidadId, @Size(max = 200) String nombre) {}

  /** Costo de un material; sin costo, se quita el capturado y vuelve el propuesto. */
  public record CostoApertura(
      @NotNull Long materialId,
      @PositiveOrZero @Digits(integer = 10, fraction = 6) BigDecimal costoUnitario,
      @Size(max = 200) String nota) {}

  /** Varios costos a la vez. */
  public record CapturaCostos(@NotEmpty @Size(max = 2000) List<@Valid CostoApertura> costos) {}

  /** La firma, con la huella de lo que se revisó. */
  public record Firma(@NotBlank @Size(max = 64) String huella) {}

  /** Motivo de la cancelación. */
  public record Cancelacion(@NotBlank @Size(max = 500) String motivo) {}

  private final RepositorioAperturas repositorio;
  private final RepositorioConteos conteos;
  private final RepositorioMovimientos movimientos;
  private final RepositorioSaldos saldos;
  private final RepositorioPrestamos prestamos;
  private final RepositorioPeriodos periodos;
  private final ServicioAlmacenes almacenes;
  private final RepositorioAlmacenes almacenesBase;
  private final ServicioAplicacion aplicacion;
  private final ConsultaHistorico historico;
  private final CatalogoApi catalogo;
  private final Personas personas;
  private final Nombres nombres;
  private final Folios folios;

  public ServicioAperturas(
      RepositorioAperturas repositorio,
      RepositorioConteos conteos,
      RepositorioMovimientos movimientos,
      RepositorioSaldos saldos,
      RepositorioPrestamos prestamos,
      RepositorioPeriodos periodos,
      ServicioAlmacenes almacenes,
      RepositorioAlmacenes almacenesBase,
      ServicioAplicacion aplicacion,
      ConsultaHistorico historico,
      CatalogoApi catalogo,
      Personas personas,
      Nombres nombres,
      Folios folios) {
    this.repositorio = repositorio;
    this.conteos = conteos;
    this.movimientos = movimientos;
    this.saldos = saldos;
    this.prestamos = prestamos;
    this.periodos = periodos;
    this.almacenes = almacenes;
    this.almacenesBase = almacenesBase;
    this.aplicacion = aplicacion;
    this.historico = historico;
    this.catalogo = catalogo;
    this.personas = personas;
    this.nombres = nombres;
    this.folios = folios;
  }

  // ------------------------------------------------------------------------------ consultas

  @PreAuthorize(CONSULTAR)
  @Transactional(readOnly = true)
  public List<Apertura> aperturas() {
    return presentar(repositorio.aperturas(empresa()));
  }

  @PreAuthorize(CONSULTAR)
  @Transactional(readOnly = true)
  public DetalleApertura apertura(long id) {
    return detalle(existente(id, false));
  }

  // ---------------------------------------------------------------------------------- abrir

  /**
   * Abre la apertura del almacén: su conteo a ciegas con todos los materiales activos y los
   * préstamos del Excel de su frente que siguen abiertos.
   */
  @PreAuthorize(CAPTURAR_O_ADMINISTRAR)
  @Transactional
  public DetalleApertura abrir(DatosApertura datos) {
    FilaAlmacen almacen = almacenes.activo(datos.almacenId());
    if (datos.fecha().isAfter(hoy())) {
      throw ErrorNegocio.invalido("FECHA_FUTURA", "La fecha no puede ser posterior a hoy");
    }
    if (periodos.cerrado(empresa(), datos.fecha())) {
      throw ErrorNegocio.conflicto("PERIODO_CERRADO", "Ese mes ya está cerrado");
    }
    repositorio
        .vigente(empresa(), almacen.id())
        .ifPresent(
            a -> {
              throw ErrorNegocio.conflicto(
                  "APERTURA_EXISTENTE",
                  "El almacén " + almacen.clave() + " ya tiene la apertura " + a.folio());
            });
    if (repositorio.tieneKardex(empresa(), almacen.id())) {
      throw ErrorNegocio.conflicto(
          "YA_TIENE_MOVIMIENTOS",
          "El almacén " + almacen.clave() + " ya tiene movimientos: no se vuelve a abrir");
    }
    if (almacenesBase.enConteo(empresa(), almacen.id())) {
      throw ErrorNegocio.conflicto(
          "CONTEO_ABIERTO", "El almacén ya tiene un conteo abierto: ciérralo o cancélalo");
    }
    String observaciones = Textos.limpio(datos.observaciones());
    long conteo =
        conteos.insertar(
            empresa(),
            folios.siguiente("CON"),
            almacen.id(),
            datos.fecha(),
            usuario(),
            "Conteo de apertura del almacén " + almacen.clave());
    conteos.agregarMateriales(
        empresa(), conteo, nombres.activos().stream().map(MaterialInfo::id).toList());
    long id =
        repositorio.insertar(
            empresa(),
            folios.siguiente("APE"),
            almacen.id(),
            conteo,
            datos.fecha(),
            usuario(),
            observaciones);
    traerDelExcel(id, almacen);
    return apertura(id);
  }

  /** Vuelve a traer los préstamos abiertos del Excel (después de la carga final): no repite. */
  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public DetalleApertura traerPrestamos(long id) {
    FilaApertura a = abierta(id);
    traerDelExcel(a.id(), almacenes.existente(a.almacenId()));
    return apertura(id);
  }

  private void traerDelExcel(long apertura, FilaAlmacen almacen) {
    for (PrestamoAbierto p : historico.prestamosAbiertos(almacen.frenteId())) {
      String responsable =
          p.entregarATexto() != null && p.entregarAId() != null
              ? p.entregarATexto()
              : p.solicitanteTexto();
      repositorio.insertarPrestamo(
          empresa(),
          apertura,
          p.id(),
          p.filaExcel(),
          p.articulo(),
          p.fecha(),
          p.cantidad(),
          responsable,
          p.materialId(),
          p.entregarAId() != null ? p.entregarAId() : p.solicitanteId());
    }
  }

  // ------------------------------------------------------------------------------- contar

  /** Marca en 0 lo que no se contó: es del almacén y queda como contado por quien lo marca. */
  @PreAuthorize(CAPTURAR)
  @Transactional
  public DetalleApertura sinContarEnCero(long id) {
    FilaApertura a = abierta(id);
    conteoAbierto(a);
    for (FilaLineaConteo l : conteos.lineas(empresa(), a.conteoId())) {
      if (l.contado() == null) {
        conteos.capturar(
            empresa(),
            a.conteoId(),
            l.materialId(),
            BigDecimal.ZERO.setScale(Valuacion.DECIMALES_CANTIDAD),
            null,
            usuario());
      }
    }
    return apertura(id);
  }

  // ---------------------------------------------------------------------------- préstamos

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public DetalleApertura agregarPrestamo(long id, NuevoPrestamoApertura datos) {
    FilaApertura a = abierta(id);
    if (datos.fecha() != null && datos.fecha().isAfter(a.fecha())) {
      throw ErrorNegocio.invalido(
          "FECHA_POSTERIOR", "Un préstamo abierto al corte es de antes de la fecha del corte");
    }
    repositorio.insertarPrestamo(
        empresa(),
        a.id(),
        null,
        null,
        Textos.limpio(datos.articulo()),
        datos.fecha(),
        cantidad(datos.cantidad()),
        Textos.limpio(datos.responsableTexto()),
        null,
        null);
    return apertura(id);
  }

  /** Lo que decide administración de un préstamo: listo, pendiente o descartado. */
  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public DetalleApertura cambiarPrestamo(
      long id, long prestamoId, int version, DatosPrestamoApertura datos) {
    FilaApertura a = abierta(id);
    prestamoDe(a, prestamoId);
    String motivo = Textos.limpio(datos.motivo());
    switch (datos.estado()) {
      case "listo" -> {
        if (datos.materialId() == null || datos.responsableId() == null) {
          throw ErrorNegocio.invalido(
              "PRESTAMO_INCOMPLETO", "Elige el material y quién tiene la herramienta");
        }
        motivo = null;
      }
      case "descartado" -> {
        if (motivo == null) {
          throw ErrorNegocio.invalido(
              "FALTA_MOTIVO",
              "Escribe por qué no es un préstamo abierto (ya regresó, otro almacén…)");
        }
      }
      default -> motivo = null;
    }
    if (datos.materialId() != null) {
      MaterialInfo m = nombres.materiales(List.of(datos.materialId())).get(datos.materialId());
      if (m == null || !m.activo()) {
        throw ErrorNegocio.invalido(
            "MATERIAL_NO_ENCONTRADO", "No existe el material o está desactivado");
      }
    }
    if (datos.responsableId() != null) {
      var persona = personas.nombres(List.of(datos.responsableId()));
      if (persona.isEmpty() || !persona.getFirst().activa()) {
        throw ErrorNegocio.invalido(
            "PERSONA_NO_ENCONTRADA", "No existe la persona o está inactiva");
      }
    }
    if (repositorio.cambiarPrestamo(
            empresa(),
            prestamoId,
            version,
            datos.materialId(),
            datos.responsableId(),
            cantidad(datos.cantidad()),
            datos.estado(),
            motivo)
        == 0) {
      throw Versiones.desactualizada();
    }
    if (datos.materialId() != null) {
      // Lo prestado se cuenta con su material: si no estaba en el conteo, se agrega.
      conteoAbierto(a);
      conteos.agregarMateriales(empresa(), a.conteoId(), List.of(datos.materialId()));
    }
    return apertura(id);
  }

  /** Da de alta la herramienta del préstamo como material (categoría Herramienta) y la liga. */
  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public DetalleApertura darDeAltaHerramienta(long id, long prestamoId, Herramienta datos) {
    FilaApertura a = abierta(id);
    FilaPrestamoApertura p = prestamoDe(a, prestamoId);
    if (p.materialId() != null) {
      throw ErrorNegocio.conflicto("YA_TIENE_MATERIAL", "El préstamo ya tiene material");
    }
    String nombre = Textos.limpio(datos.nombre());
    MaterialInfo material =
        catalogo.altaHerramienta(nombre == null ? p.articulo() : nombre, datos.unidadId());
    repositorio.cambiarPrestamo(
        empresa(),
        p.id(),
        p.version(),
        material.id(),
        p.responsableId(),
        p.cantidad(),
        p.estado(),
        p.motivo());
    conteoAbierto(a);
    conteos.agregarMateriales(empresa(), a.conteoId(), List.of(material.id()));
    return apertura(id);
  }

  // ------------------------------------------------------------------------------- costos

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public DetalleApertura capturarCostos(long id, CapturaCostos captura) {
    FilaApertura a = abierta(id);
    Set<Long> enApertura = materialesDe(a);
    for (CostoApertura c : captura.costos()) {
      if (!enApertura.contains(c.materialId())) {
        throw ErrorNegocio.invalido(
            "MATERIAL_NO_ENCONTRADO", "Un material no está en el conteo de la apertura");
      }
      if (c.costoUnitario() == null) {
        repositorio.quitarCosto(empresa(), a.id(), c.materialId());
      } else {
        repositorio.fijarCosto(
            empresa(),
            a.id(),
            c.materialId(),
            c.costoUnitario().setScale(Valuacion.DECIMALES_COSTO, RoundingMode.HALF_EVEN),
            Textos.limpio(c.nota()),
            usuario());
      }
    }
    return apertura(id);
  }

  // ------------------------------------------------------------------------------- firmas

  /**
   * Firma la apertura. La primera firma congela lo que se firma; la segunda aplica el saldo de
   * apertura, cierra el conteo y crea los préstamos vivos.
   */
  @PreAuthorize(FIRMAR)
  @Transactional
  public DetalleApertura firmar(long id, Firma firma) {
    FilaApertura a = existente(id, true);
    if (!a.estado().equals("abierta") && !a.estado().equals("en_firma")) {
      throw ErrorNegocio.conflicto(
          "APERTURA_CERRADA", "La apertura " + a.folio() + " ya está " + a.estado());
    }
    revisarQuienFirma(a);
    boolean coordinacion = firmaQueToca(a);
    Calculo calculo = calcular(a);
    if (!calculo.huella().equals(firma.huella())) {
      throw ErrorNegocio.conflicto(
          "APERTURA_CAMBIO",
          "La apertura cambió desde que la revisaste: vuelve a cargarla antes de firmar");
    }
    if (a.estado().equals("abierta")) {
      List<String> falta = faltaParaFirmar(a, calculo);
      if (!falta.isEmpty()) {
        throw ErrorNegocio.conflicto("APERTURA_INCOMPLETA", String.join(". ", falta));
      }
      repositorio.insertarLineas(empresa(), a.id(), congelar(calculo));
      repositorio.firmar(
          empresa(), a.id(), coordinacion, usuario(), SaldoApertura.importe(calculo.lineas()));
      return apertura(id);
    }
    repositorio.firmar(empresa(), a.id(), coordinacion, usuario(), null);
    aplicar(existente(id, true), calculo);
    return apertura(id);
  }

  /** Quita las firmas para corregir algo: lo congelado se borra y la apertura vuelve a abierta. */
  @PreAuthorize(FIRMAR)
  @Transactional
  public DetalleApertura retirarFirmas(long id) {
    FilaApertura a = existente(id, true);
    if (!a.estado().equals("en_firma")) {
      throw ErrorNegocio.conflicto(
          "SIN_FIRMAS", "La apertura " + a.folio() + " no está en firma (" + a.estado() + ")");
    }
    if (!Autorizacion.tiene(Permisos.ADMINISTRAR)
        && !Objects.equals(a.firmaCoordinacion(), usuario())
        && !Objects.equals(a.firmaAdministracion(), usuario())) {
      throw new ErrorNegocio(
          HttpStatus.FORBIDDEN, "NO_ES_TU_FIRMA", "Retira las firmas quien firmó o administración");
    }
    repositorio.retirarFirmas(empresa(), a.id());
    return apertura(id);
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public DetalleApertura cancelar(long id, Cancelacion cancelacion) {
    FilaApertura a = existente(id, true);
    if (!a.estado().equals("abierta") && !a.estado().equals("en_firma")) {
      throw ErrorNegocio.conflicto(
          "APERTURA_CERRADA", "La apertura " + a.folio() + " ya está " + a.estado());
    }
    repositorio.cancelar(empresa(), a.id(), Textos.limpio(cancelacion.motivo()));
    conteos.cancelar(empresa(), a.conteoId());
    return apertura(id);
  }

  /** La segunda firma: aplica todo en la misma transacción. */
  private void aplicar(FilaApertura a, Calculo calculo) {
    if (a.fecha().isAfter(hoy())) {
      throw ErrorNegocio.invalido("FECHA_FUTURA", "La fecha del corte es posterior a hoy");
    }
    if (periodos.cerrado(empresa(), a.fecha())) {
      throw ErrorNegocio.conflicto("PERIODO_CERRADO", "El mes del corte ya está cerrado");
    }
    Map<Long, MaterialInfo> materiales =
        nombres.materiales(calculo.lineas().stream().map(Linea::materialId).toList());
    List<NuevaLinea> lineas = new ArrayList<>();
    Map<Long, BigDecimal> costos = new HashMap<>();
    for (Linea l : calculo.lineas()) {
      if (l.cantidad().signum() <= 0) {
        continue;
      }
      MaterialInfo m = materiales.get(l.materialId());
      lineas.add(
          new NuevaLinea(
              lineas.size() + 1,
              l.materialId(),
              l.cantidad(),
              l.cantidad(),
              m.unidadControlId(),
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null));
      costos.put(l.materialId(), l.costo().unitario());
    }
    TipoMovimiento tipo = TipoMovimiento.APERTURA;
    long movimiento =
        movimientos.insertar(
            empresa(),
            folios.siguiente(tipo.prefijoFolio()),
            new Cabecera(
                tipo.clave(),
                a.fecha(),
                a.almacenId(),
                null,
                null,
                null,
                null,
                null,
                null,
                a.conteoId(),
                null,
                null,
                null,
                "Saldo de apertura " + a.folio()),
            usuario(),
            null);
    for (NuevaLinea l : lineas) {
      movimientos.insertarLinea(empresa(), movimiento, tipo.clave(), l);
    }
    conteos.cerrar(empresa(), a.conteoId(), usuario());
    if (!lineas.isEmpty()) {
      aplicacion.aplicarBloqueado(
          movimientos.bloquear(empresa(), movimiento).orElseThrow(), costos);
    } else {
      movimientos.aplicar(empresa(), movimiento, usuario());
    }
    // Los préstamos vivos, en orden de material: lo prestado ya está en la existencia.
    List<FilaPrestamoApertura> listos =
        calculo.prestamos().stream()
            .filter(p -> p.estado().equals("listo"))
            .sorted(
                Comparator.comparingLong((FilaPrestamoApertura p) -> p.materialId())
                    .thenComparingLong(FilaPrestamoApertura::id))
            .toList();
    for (FilaPrestamoApertura p : listos) {
      saldos.bloquear(empresa(), a.almacenId(), p.materialId());
      saldos.cambiarPrestado(empresa(), a.almacenId(), p.materialId(), p.cantidad());
      LocalDate fecha = p.fecha() == null || p.fecha().isAfter(a.fecha()) ? a.fecha() : p.fecha();
      long vivo =
          prestamos.insertar(
              empresa(),
              folios.siguiente("PRE"),
              a.almacenId(),
              p.materialId(),
              p.cantidad(),
              p.responsableId(),
              null,
              fecha,
              null,
              usuario(),
              recortar(
                  "Abierto al corte "
                      + a.folio()
                      + (p.filaExcel() == null ? "" : ", fila " + p.filaExcel() + " del Excel")
                      + ": "
                      + p.articulo()));
      repositorio.ligarPrestamo(empresa(), p.id(), vivo);
    }
    repositorio.aplicar(empresa(), a.id(), movimiento);
  }

  /** Qué firma le toca a quien firma: la de administración o la de coordinación. */
  private static boolean firmaQueToca(FilaApertura a) {
    boolean admin = Autorizacion.tiene(Permisos.ADMINISTRAR);
    boolean coord = Autorizacion.tiene(Permisos.DOCUMENTO_AUTORIZAR);
    if (admin && a.firmaAdministracion() == null) {
      return false;
    }
    if (coord && a.firmaCoordinacion() == null) {
      return true;
    }
    throw ErrorNegocio.conflicto(
        "FIRMA_YA_PUESTA",
        admin || coord
            ? "La firma que te toca ya está puesta: falta la de "
                + (a.firmaCoordinacion() == null ? "coordinación" : "administración")
            : "Firman coordinación y administración");
  }

  /** Dos personas distintas, y ninguna abrió el conteo ni contó. */
  private void revisarQuienFirma(FilaApertura a) {
    long yo = usuario();
    if (Objects.equals(a.firmaCoordinacion(), yo) || Objects.equals(a.firmaAdministracion(), yo)) {
      throw new ErrorNegocio(
          HttpStatus.FORBIDDEN, "YA_FIRMASTE", "Ya firmaste: la otra firma es de otra persona");
    }
    FilaConteo c = conteos.conteo(empresa(), a.conteoId(), false).orElseThrow();
    boolean conto =
        c.usuarioCrea() == yo
            || conteos.lineas(empresa(), a.conteoId()).stream()
                .anyMatch(l -> Objects.equals(l.contadoPor(), yo));
    if (conto) {
      throw new ErrorNegocio(
          HttpStatus.FORBIDDEN,
          "FIRMA_DE_QUIEN_CONTO",
          "Quien abrió el conteo o contó no firma la apertura");
    }
  }

  // ---------------------------------------------------------------------------- cálculo

  /** Las líneas y los préstamos de la apertura, con su huella. */
  private record Calculo(
      List<Linea> lineas,
      Map<Long, String> detalles,
      Map<Long, BigDecimal> excel,
      boolean conFotoExcel,
      List<FilaPrestamoApertura> prestamos,
      String huella) {}

  private Calculo calcular(FilaApertura a) {
    List<FilaPrestamoApertura> prestamosApertura = repositorio.prestamos(empresa(), a.id());
    List<PrestamoListo> listos =
        prestamosApertura.stream()
            .filter(p -> p.estado().equals("listo"))
            .map(p -> new PrestamoListo(p.id(), p.materialId(), p.cantidad(), p.responsableId()))
            .toList();
    List<Linea> lineas = new ArrayList<>();
    Map<Long, String> detalles = new HashMap<>();
    Map<Long, BigDecimal> excel = new HashMap<>();
    boolean conFoto;
    if (a.estado().equals("abierta")) {
      Map<Long, BigDecimal> contado = new HashMap<>();
      Set<Long> materiales = new LinkedHashSet<>();
      for (FilaLineaConteo l : conteos.lineas(empresa(), a.conteoId())) {
        materiales.add(l.materialId());
        if (l.contado() != null) {
          contado.put(l.materialId(), l.contado());
        }
      }
      Map<Long, BigDecimal> prestado = new HashMap<>();
      for (PrestamoListo p : listos) {
        materiales.add(p.materialId());
        prestado.merge(p.materialId(), p.cantidad(), BigDecimal::add);
      }
      Map<Long, FilaCosto> capturados = new HashMap<>();
      repositorio.costos(empresa(), a.id()).forEach(c -> capturados.put(c.materialId(), c));
      Map<Long, CostoAlCorte> ponderados = historico.costosAlCorte(materiales, a.fecha());
      Map<Long, MaterialInfo> info = nombres.materiales(materiales);
      for (Long m : materiales) {
        FilaCosto capturado = capturados.get(m);
        CostoAlCorte ponderado = ponderados.get(m);
        MaterialInfo material = info.get(m);
        Costo costo =
            SaldoApertura.elegir(
                capturado == null ? null : capturado.costoUnitario(),
                ponderado == null ? null : ponderado.unitario(),
                material == null ? null : material.precioReferencia());
        lineas.add(new Linea(m, contado.get(m), prestado.get(m), costo));
        if (costo != null) {
          detalles.put(m, detalle(costo.origen(), capturado, ponderado));
        }
      }
      Map<Long, ExistenciaExcel> existencias = historico.existenciasExcel(materiales);
      conFoto = historico.hayFotoDeExistencias();
      if (conFoto) {
        for (Long m : materiales) {
          ExistenciaExcel e = existencias.get(m);
          excel.put(m, e == null ? BigDecimal.ZERO : e.existencia());
        }
      }
    } else {
      conFoto = false;
      for (FilaLinea l : repositorio.lineas(empresa(), a.id())) {
        Costo costo =
            l.costoUnitario() == null
                ? null
                : new Costo(l.costoUnitario(), Origen.deClave(l.origenCosto()));
        lineas.add(new Linea(l.materialId(), l.contado(), l.prestado(), costo));
        if (costo != null) {
          detalles.put(l.materialId(), detalle(costo.origen(), null, null));
        }
        if (l.existenciaExcel() != null) {
          conFoto = true;
          excel.put(l.materialId(), l.existenciaExcel());
        }
      }
    }
    return new Calculo(
        lineas,
        detalles,
        excel,
        conFoto,
        prestamosApertura,
        SaldoApertura.huella(a.fecha(), lineas, listos));
  }

  private static String detalle(Origen origen, FilaCosto capturado, CostoAlCorte ponderado) {
    return switch (origen) {
      case CAPTURADO ->
          capturado != null && capturado.nota() != null
              ? "Capturado: " + capturado.nota()
              : "Capturado por administración";
      case HISTORICO ->
          ponderado == null
              ? "Ponderado sin IVA de las entradas del Excel"
              : "Ponderado sin IVA de %d entrada%s del Excel hasta el %s%s"
                  .formatted(
                      ponderado.entradas(),
                      ponderado.entradas() == 1 ? "" : "s",
                      ponderado.ultima(),
                      ponderado.estimadas() > 0
                          ? " (" + ponderado.estimadas() + " con precio estimado)"
                          : "");
      case REFERENCIA -> "Precio de referencia del catálogo";
    };
  }

  private static List<FilaLinea> congelar(Calculo c) {
    return c.lineas().stream()
        .map(
            l ->
                new FilaLinea(
                    l.materialId(),
                    l.contado(),
                    l.prestado(),
                    l.costo() == null ? null : l.costo().unitario(),
                    l.costo() == null ? null : l.costo().origen().clave(),
                    l.importe(),
                    c.conFotoExcel() ? c.excel().get(l.materialId()) : null))
        .toList();
  }

  private List<String> faltaParaFirmar(FilaApertura a, Calculo c) {
    List<String> falta = new ArrayList<>();
    if (!a.estado().equals("abierta") && !a.estado().equals("en_firma")) {
      return falta;
    }
    long sinContar = c.lineas().stream().filter(l -> l.contado() == null).count();
    if (sinContar > 0) {
      falta.add(
          cuantos(sinContar, "material", "materiales")
              + " por contar (lo que no se encuentre se marca en 0)");
    }
    long pendientes = c.prestamos().stream().filter(p -> p.estado().equals("pendiente")).count();
    if (pendientes > 0) {
      falta.add(
          cuantos(pendientes, "préstamo", "préstamos") + " por resolver (listo o descartado)");
    }
    long sinCosto = c.lineas().stream().filter(Linea::faltaCosto).count();
    if (sinCosto > 0) {
      falta.add(cuantos(sinCosto, "costo", "costos") + ": captúralos en Costos");
    }
    if (a.fecha().isAfter(hoy())) {
      falta.add("La fecha del corte es posterior a hoy");
    }
    return falta;
  }

  // ---------------------------------------------------------------------------- presentar

  private DetalleApertura detalle(FilaApertura a) {
    Calculo c = calcular(a);
    boolean veCostos =
        Autorizacion.tieneAlguno(
            Permisos.ADMINISTRAR,
            Permisos.COSTO_VER_TODO,
            Permisos.DOCUMENTO_AUTORIZAR,
            Permisos.PRECIO_VER);
    boolean veExcel =
        !a.estado().equals("abierta")
            || Autorizacion.tieneAlguno(Permisos.ADMINISTRAR, Permisos.DOCUMENTO_AUTORIZAR);
    Map<Long, MaterialInfo> info =
        nombres.materiales(c.lineas().stream().map(Linea::materialId).toList());
    List<LineaApertura> lineas = new ArrayList<>();
    int contados = 0;
    int conExistencia = 0;
    int sinCosto = 0;
    int conDiferencia = 0;
    for (Linea l : c.lineas()) {
      MaterialInfo m = info.get(l.materialId());
      BigDecimal excel = c.conFotoExcel() && veExcel ? c.excel().get(l.materialId()) : null;
      BigDecimal diferencia =
          excel == null || l.contado() == null
              ? null
              : l.cantidad().subtract(excel).setScale(4, RoundingMode.HALF_UP);
      if (diferencia != null && diferencia.signum() != 0) {
        conDiferencia++;
      }
      contados += l.contado() == null ? 0 : 1;
      conExistencia += l.cantidad().signum() > 0 ? 1 : 0;
      sinCosto += l.faltaCosto() ? 1 : 0;
      lineas.add(
          new LineaApertura(
              l.materialId(),
              m == null ? "" : m.clave(),
              m == null ? "" : m.nombre(),
              m == null ? "" : m.unidadControl(),
              l.contado(),
              l.prestado(),
              l.cantidad(),
              veCostos && l.costo() != null ? l.costo().unitario() : null,
              veCostos && l.costo() != null ? l.costo().origen().clave() : null,
              veCostos ? c.detalles().get(l.materialId()) : null,
              veCostos ? l.importe() : null,
              l.faltaCosto(),
              excel,
              diferencia));
    }
    lineas.sort(Comparator.comparing(LineaApertura::material, String.CASE_INSENSITIVE_ORDER));
    List<PrestamoApertura> prestamosApertura = presentarPrestamos(c.prestamos());
    Resumen resumen =
        new Resumen(
            lineas.size(),
            contados,
            lineas.size() - contados,
            conExistencia,
            sinCosto,
            (int) c.prestamos().stream().filter(p -> p.estado().equals("pendiente")).count(),
            (int) c.prestamos().stream().filter(p -> p.estado().equals("listo")).count(),
            (int) c.prestamos().stream().filter(p -> p.estado().equals("descartado")).count(),
            veCostos ? SaldoApertura.importe(c.lineas()) : null,
            c.conFotoExcel(),
            c.conFotoExcel() && veExcel ? conDiferencia : null);
    return new DetalleApertura(
        presentar(List.of(a)).getFirst(),
        resumen,
        lineas,
        prestamosApertura,
        c.huella(),
        firmaComo(a),
        faltaParaFirmar(a, c));
  }

  /** La firma que puede poner quien consulta, o nulo. */
  private String firmaComo(FilaApertura a) {
    if (!a.estado().equals("abierta") && !a.estado().equals("en_firma")) {
      return null;
    }
    long yo = usuario();
    if (Objects.equals(a.firmaCoordinacion(), yo) || Objects.equals(a.firmaAdministracion(), yo)) {
      return null;
    }
    if (Autorizacion.tiene(Permisos.ADMINISTRAR) && a.firmaAdministracion() == null) {
      return "administracion";
    }
    if (Autorizacion.tiene(Permisos.DOCUMENTO_AUTORIZAR) && a.firmaCoordinacion() == null) {
      return "coordinacion";
    }
    return null;
  }

  private List<PrestamoApertura> presentarPrestamos(List<FilaPrestamoApertura> filas) {
    Set<Long> materialIds = new HashSet<>();
    Set<Long> personaIds = new HashSet<>();
    filas.forEach(
        p -> {
          if (p.materialId() != null) {
            materialIds.add(p.materialId());
          }
          if (p.responsableId() != null) {
            personaIds.add(p.responsableId());
          }
        });
    Map<Long, MaterialInfo> materiales = nombres.materiales(materialIds);
    Map<Long, String> responsables = nombres.personas(personaIds);
    return filas.stream()
        .map(
            p -> {
              MaterialInfo m = p.materialId() == null ? null : materiales.get(p.materialId());
              return new PrestamoApertura(
                  p.id(),
                  p.origenId(),
                  p.filaExcel(),
                  p.articulo(),
                  p.fecha(),
                  p.cantidad(),
                  p.responsableTexto(),
                  p.materialId(),
                  m == null ? null : m.clave(),
                  m == null ? null : m.nombre(),
                  m == null ? null : m.unidadControl(),
                  p.responsableId(),
                  p.responsableId() == null ? null : responsables.get(p.responsableId()),
                  p.estado(),
                  p.motivo(),
                  p.prestamoId(),
                  p.version());
            })
        .toList();
  }

  private List<Apertura> presentar(List<FilaApertura> filas) {
    Set<Long> usuarioIds = new HashSet<>();
    filas.forEach(
        a -> {
          usuarioIds.add(a.usuarioCrea());
          if (a.firmaCoordinacion() != null) {
            usuarioIds.add(a.firmaCoordinacion());
          }
          if (a.firmaAdministracion() != null) {
            usuarioIds.add(a.firmaAdministracion());
          }
        });
    Map<Long, String> usuarios = nombres.usuarios(usuarioIds);
    Map<Long, String> claves = nombres.almacenes();
    boolean veCostos =
        Autorizacion.tieneAlguno(
            Permisos.ADMINISTRAR,
            Permisos.COSTO_VER_TODO,
            Permisos.DOCUMENTO_AUTORIZAR,
            Permisos.PRECIO_VER);
    Map<Long, String> folioConteo = new HashMap<>();
    for (FilaApertura a : filas) {
      conteos
          .conteo(empresa(), a.conteoId(), false)
          .ifPresent(c -> folioConteo.put(a.conteoId(), c.folio()));
    }
    return filas.stream()
        .map(
            a ->
                new Apertura(
                    a.id(),
                    a.folio(),
                    a.almacenId(),
                    claves.getOrDefault(a.almacenId(), ""),
                    a.fecha(),
                    a.estado(),
                    a.conteoId(),
                    folioConteo.getOrDefault(a.conteoId(), ""),
                    usuarios.getOrDefault(a.usuarioCrea(), ""),
                    a.firmaCoordinacion() == null ? null : usuarios.get(a.firmaCoordinacion()),
                    a.firmaCoordinacionEn(),
                    a.firmaAdministracion() == null ? null : usuarios.get(a.firmaAdministracion()),
                    a.firmaAdministracionEn(),
                    a.movimientoId(),
                    veCostos ? a.importe() : null,
                    a.motivoCancelacion(),
                    a.observaciones(),
                    a.version()))
        .toList();
  }

  // --------------------------------------------------------------------------------- acta

  /** El acta de apertura en Excel: datos y firmas, materiales valuados y préstamos vivos. */
  @PreAuthorize(FIRMAR)
  @Transactional(readOnly = true)
  public byte[] acta(long id) {
    DetalleApertura d = apertura(id);
    Apertura a = d.apertura();
    LibroExcel libro = new LibroExcel();
    var hoja =
        libro.hoja("Apertura", List.of(Columna.texto("Dato", 28), Columna.texto("Valor", 60)));
    hoja.fila("Folio", a.folio());
    hoja.fila("Almacén", a.almacen());
    hoja.fila("Fecha del corte", a.fecha().toString());
    hoja.fila("Estado", a.estado());
    hoja.fila("Conteo físico", a.conteoFolio());
    hoja.fila("Abrió", a.abrio());
    hoja.fila(
        "Firma de coordinación",
        a.firmaCoordinacion() == null
            ? ""
            : a.firmaCoordinacion() + " · " + a.firmaCoordinacionEn());
    hoja.fila(
        "Firma de administración",
        a.firmaAdministracion() == null
            ? ""
            : a.firmaAdministracion() + " · " + a.firmaAdministracionEn());
    hoja.fila("Materiales con existencia", String.valueOf(d.resumen().conExistencia()));
    hoja.fila("Préstamos vivos", String.valueOf(d.resumen().prestamosListos()));
    hoja.fila(
        "Valor sin IVA",
        d.resumen().importe() == null ? "" : d.resumen().importe().toPlainString());
    hoja.fila("Huella de lo firmado", d.huella());
    var materiales =
        libro.hoja(
            "Materiales",
            List.of(
                Columna.texto("Clave", 10),
                Columna.texto("Material", 44),
                Columna.texto("Unidad", 10),
                Columna.cantidad("Contado"),
                Columna.cantidad("Prestado"),
                Columna.cantidad("Entra"),
                Columna.moneda("Costo sin IVA"),
                Columna.texto("De dónde", 40),
                Columna.moneda("Importe"),
                Columna.cantidad("Excel"),
                Columna.cantidad("Diferencia")));
    for (LineaApertura l : d.lineas()) {
      materiales.fila(
          l.clave(),
          l.material(),
          l.unidad(),
          l.contado(),
          l.prestado(),
          l.cantidad(),
          l.costoUnitario(),
          l.detalleCosto(),
          l.importe(),
          l.existenciaExcel(),
          l.diferenciaExcel());
    }
    materiales.totales(
        "Total", null, null, null, null, null, null, null, d.resumen().importe(), null, null);
    var prestamosHoja =
        libro.hoja(
            "Préstamos",
            List.of(
                Columna.texto("Fila del Excel", 10),
                Columna.texto("Artículo", 40),
                Columna.texto("Material", 40),
                Columna.cantidad("Cantidad"),
                Columna.texto("Responsable", 36),
                Columna.texto("Estado", 12),
                Columna.texto("Motivo", 40)));
    for (PrestamoApertura p : d.prestamos()) {
      prestamosHoja.fila(
          p.filaExcel() == null ? "" : String.valueOf(p.filaExcel()),
          p.articulo(),
          p.material() == null ? "" : p.materialClave() + " " + p.material(),
          p.cantidad(),
          p.responsable() == null ? Objects.toString(p.responsableTexto(), "") : p.responsable(),
          p.estado(),
          Objects.toString(p.motivo(), ""));
    }
    return libro.bytes();
  }

  // ---------------------------------------------------------------------------- auxiliares

  private Set<Long> materialesDe(FilaApertura a) {
    Set<Long> materiales = new HashSet<>();
    conteos.lineas(empresa(), a.conteoId()).forEach(l -> materiales.add(l.materialId()));
    repositorio.prestamos(empresa(), a.id()).stream()
        .filter(p -> p.materialId() != null)
        .forEach(p -> materiales.add(p.materialId()));
    return materiales;
  }

  private void conteoAbierto(FilaApertura a) {
    FilaConteo c = conteos.conteo(empresa(), a.conteoId(), true).orElseThrow();
    if (!c.estado().equals("abierto")) {
      throw ErrorNegocio.conflicto("CONTEO_CERRADO", "El conteo " + c.folio() + " ya está cerrado");
    }
  }

  private FilaPrestamoApertura prestamoDe(FilaApertura a, long prestamoId) {
    return repositorio
        .prestamo(empresa(), a.id(), prestamoId)
        .orElseThrow(
            () ->
                ErrorNegocio.noEncontrado(
                    "PRESTAMO_NO_ENCONTRADO", "No existe ese préstamo en la apertura"));
  }

  /** "Falta 1 costo", "Faltan 3 costos". */
  private static String cuantos(long n, String uno, String varios) {
    return (n == 1 ? "Falta 1 " + uno : "Faltan " + n + " " + varios);
  }

  private static String recortar(String texto) {
    return texto.length() <= 500 ? texto : texto.substring(0, 500);
  }

  private static BigDecimal cantidad(BigDecimal valor) {
    return valor.setScale(Valuacion.DECIMALES_CANTIDAD, RoundingMode.HALF_UP);
  }

  /** La apertura abierta (bloqueada): lo único que se edita antes de firmar. */
  private FilaApertura abierta(long id) {
    FilaApertura a = existente(id, true);
    if (!a.estado().equals("abierta")) {
      throw ErrorNegocio.conflicto(
          a.estado().equals("en_firma") ? "APERTURA_EN_FIRMA" : "APERTURA_CERRADA",
          a.estado().equals("en_firma")
              ? "La apertura " + a.folio() + " ya tiene una firma: retira las firmas para cambiarla"
              : "La apertura " + a.folio() + " ya está " + a.estado());
    }
    return a;
  }

  private FilaApertura existente(long id, boolean bloquear) {
    return repositorio
        .apertura(empresa(), id, bloquear)
        .orElseThrow(
            () -> ErrorNegocio.noEncontrado("APERTURA_NO_ENCONTRADA", "No existe la apertura"));
  }
}

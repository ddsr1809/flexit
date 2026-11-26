package mx.constructora.inventario.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static mx.constructora.inventario.aplicacion.Sesion.CAPTURAR;
import static mx.constructora.inventario.aplicacion.Sesion.CONSULTAR;
import static mx.constructora.inventario.aplicacion.Sesion.empresa;
import static mx.constructora.inventario.aplicacion.Sesion.hoy;
import static mx.constructora.inventario.aplicacion.Sesion.usuario;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mx.constructora.catalogo.CatalogoApi.MaterialInfo;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Folios;
import mx.constructora.compartido.Textos;
import mx.constructora.estructura.EstructuraApi.ContratoInfo;
import mx.constructora.inventario.dominio.TipoMovimiento;
import mx.constructora.inventario.dominio.Valuacion;
import mx.constructora.inventario.infraestructura.RepositorioAlmacenes.FilaAlmacen;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.Cabecera;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.NuevaLinea;
import mx.constructora.inventario.infraestructura.RepositorioPrestamos;
import mx.constructora.inventario.infraestructura.RepositorioPrestamos.FilaPrestamo;
import mx.constructora.inventario.infraestructura.RepositorioSaldos;
import mx.constructora.inventario.infraestructura.RepositorioSaldos.FilaSaldo;
import mx.constructora.organizacion.Personas;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Préstamos de herramienta (o material) a una persona. No mueven la existencia: la apartan ({@code
 * saldo.prestado}) y lo prestado ya no está disponible para vales. Si se pierde, se libera lo
 * apartado y se crea una merma ligada al préstamo, que coordinación autoriza.
 */
@Service
public class ServicioPrestamos {

  /** Préstamo con nombres. */
  public record Prestamo(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String folio,
      @Schema(requiredMode = REQUIRED) long almacenId,
      @Schema(requiredMode = REQUIRED) String almacen,
      @Schema(requiredMode = REQUIRED) long materialId,
      @Schema(requiredMode = REQUIRED) String materialClave,
      @Schema(requiredMode = REQUIRED) String material,
      @Schema(requiredMode = REQUIRED) String unidad,
      @Schema(requiredMode = REQUIRED) BigDecimal cantidad,
      @Schema(requiredMode = REQUIRED) long responsableId,
      @Schema(requiredMode = REQUIRED) String responsable,
      Long contratoId,
      String contrato,
      @Schema(requiredMode = REQUIRED) LocalDate fecha,
      LocalDate compromiso,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"abierto", "devuelto", "perdido"})
          String estado,
      @Schema(requiredMode = REQUIRED) boolean vencido,
      LocalDate devueltoEn,
      @Schema(requiredMode = REQUIRED) String presto,
      String recibio,
      String observaciones,
      @Schema(requiredMode = REQUIRED) int version) {}

  /** Alta de un préstamo. */
  public record DatosPrestamo(
      @NotNull Long almacenId,
      @NotNull Long materialId,
      @NotNull @Positive @Digits(integer = 10, fraction = 4) BigDecimal cantidad,
      @NotNull Long responsableId,
      Long contratoId,
      @NotNull LocalDate fecha,
      LocalDate compromiso,
      @Size(max = 500) String observaciones) {}

  /** Resultado de marcar un préstamo como perdido: el préstamo y la merma creada. */
  public record PrestamoPerdido(
      @Schema(requiredMode = REQUIRED) Prestamo prestamo,
      @Schema(requiredMode = REQUIRED) long mermaId) {}

  private final RepositorioPrestamos repositorio;
  private final RepositorioSaldos saldos;
  private final RepositorioMovimientos movimientos;
  private final ServicioAlmacenes almacenes;
  private final Nombres nombres;
  private final Personas personas;
  private final Folios folios;

  public ServicioPrestamos(
      RepositorioPrestamos repositorio,
      RepositorioSaldos saldos,
      RepositorioMovimientos movimientos,
      ServicioAlmacenes almacenes,
      Nombres nombres,
      Personas personas,
      Folios folios) {
    this.repositorio = repositorio;
    this.saldos = saldos;
    this.movimientos = movimientos;
    this.almacenes = almacenes;
    this.nombres = nombres;
    this.personas = personas;
    this.folios = folios;
  }

  @PreAuthorize(CONSULTAR)
  @Transactional(readOnly = true)
  public List<Prestamo> prestamos(Long almacenId, String estado, boolean soloVencidos) {
    return presentar(
        repositorio.prestamos(
            empresa(), almacenId, Textos.limpio(estado), soloVencidos ? hoy() : null));
  }

  @PreAuthorize(CONSULTAR)
  @Transactional(readOnly = true)
  public Prestamo prestamo(long id) {
    return presentar(List.of(existente(id, false))).getFirst();
  }

  @PreAuthorize(CAPTURAR)
  @Transactional
  public Prestamo prestar(DatosPrestamo datos) {
    FilaAlmacen almacen = almacenes.activo(datos.almacenId());
    MaterialInfo material = nombres.materiales(List.of(datos.materialId())).get(datos.materialId());
    if (material == null || !material.activo()) {
      throw ErrorNegocio.invalido(
          "MATERIAL_NO_ENCONTRADO", "No existe el material o está desactivado");
    }
    var persona = personas.nombres(List.of(datos.responsableId()));
    if (persona.isEmpty() || !persona.getFirst().activa()) {
      throw ErrorNegocio.invalido("PERSONA_NO_ENCONTRADA", "No existe la persona o está inactiva");
    }
    if (datos.contratoId() != null && nombres.contratos(List.of(datos.contratoId())).isEmpty()) {
      throw ErrorNegocio.invalido("CONTRATO_NO_ENCONTRADO", "No existe el contrato");
    }
    if (datos.fecha().isAfter(hoy())) {
      throw ErrorNegocio.invalido("FECHA_FUTURA", "La fecha no puede ser posterior a hoy");
    }
    if (datos.compromiso() != null && datos.compromiso().isBefore(datos.fecha())) {
      throw ErrorNegocio.invalido(
          "COMPROMISO_ANTES", "La fecha de regreso es anterior al préstamo");
    }
    BigDecimal cantidad =
        datos.cantidad().setScale(Valuacion.DECIMALES_CANTIDAD, RoundingMode.HALF_UP);
    FilaSaldo saldo = saldos.bloquear(empresa(), almacen.id(), material.id());
    if (cantidad.compareTo(saldo.disponible()) > 0) {
      throw ErrorNegocio.conflicto(
          "EXISTENCIA_INSUFICIENTE",
          "Se prestarían "
              + cantidad.stripTrailingZeros().toPlainString()
              + " "
              + material.unidadControl()
              + " de "
              + material.nombre()
              + " y hay disponibles "
              + saldo.disponible().stripTrailingZeros().toPlainString());
    }
    saldos.cambiarPrestado(empresa(), almacen.id(), material.id(), cantidad);
    long id =
        repositorio.insertar(
            empresa(),
            folios.siguiente("PRE"),
            almacen.id(),
            material.id(),
            cantidad,
            datos.responsableId(),
            datos.contratoId(),
            datos.fecha(),
            datos.compromiso(),
            usuario(),
            Textos.limpio(datos.observaciones()));
    return prestamo(id);
  }

  @PreAuthorize(CAPTURAR)
  @Transactional
  public Prestamo devolver(long id, LocalDate fecha) {
    FilaPrestamo p = abierto(id);
    LocalDate devuelto = fecha == null ? hoy() : fecha;
    if (devuelto.isAfter(hoy()) || devuelto.isBefore(p.fecha())) {
      throw ErrorNegocio.invalido("FECHA_INVALIDA", "La fecha de regreso no es válida");
    }
    saldos.bloquear(empresa(), p.almacenId(), p.materialId());
    saldos.cambiarPrestado(empresa(), p.almacenId(), p.materialId(), p.cantidad().negate());
    repositorio.cerrar(empresa(), id, "devuelto", devuelto, usuario());
    return prestamo(id);
  }

  /** Marca el préstamo como perdido y crea la merma (pendiente de autorizar) que lo da de baja. */
  @PreAuthorize(CAPTURAR)
  @Transactional
  public PrestamoPerdido perdido(long id, String motivo) {
    String texto = Textos.limpio(motivo);
    if (texto == null) {
      throw ErrorNegocio.invalido("FALTA_MOTIVO", "Escribe qué pasó con la herramienta");
    }
    FilaPrestamo p = abierto(id);
    saldos.bloquear(empresa(), p.almacenId(), p.materialId());
    saldos.cambiarPrestado(empresa(), p.almacenId(), p.materialId(), p.cantidad().negate());
    repositorio.cerrar(empresa(), id, "perdido", null, usuario());
    MaterialInfo material = nombres.materiales(List.of(p.materialId())).get(p.materialId());
    TipoMovimiento merma = TipoMovimiento.MERMA;
    long mermaId =
        movimientos.insertar(
            empresa(),
            folios.siguiente(merma.prefijoFolio()),
            new Cabecera(
                merma.clave(),
                hoy(),
                p.almacenId(),
                null,
                null,
                null,
                null,
                p.responsableId(),
                null,
                null,
                p.id(),
                null,
                "Pérdida en préstamo " + p.folio() + ": " + texto,
                null),
            usuario(),
            null);
    movimientos.insertarLinea(
        empresa(),
        mermaId,
        merma.clave(),
        new NuevaLinea(
            1,
            p.materialId(),
            p.cantidad(),
            p.cantidad(),
            material.unidadControlId(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));
    movimientos.enviar(empresa(), mermaId);
    return new PrestamoPerdido(prestamo(id), mermaId);
  }

  private FilaPrestamo abierto(long id) {
    FilaPrestamo p = existente(id, true);
    if (!p.estado().equals("abierto")) {
      throw ErrorNegocio.conflicto(
          "PRESTAMO_CERRADO", "El préstamo " + p.folio() + " ya está " + p.estado());
    }
    return p;
  }

  private FilaPrestamo existente(long id, boolean bloquear) {
    return repositorio
        .prestamo(empresa(), id, bloquear)
        .orElseThrow(
            () -> ErrorNegocio.noEncontrado("PRESTAMO_NO_ENCONTRADO", "No existe el préstamo"));
  }

  private List<Prestamo> presentar(List<FilaPrestamo> filas) {
    Set<Long> materialIds = new HashSet<>();
    Set<Long> personaIds = new HashSet<>();
    Set<Long> contratoIds = new HashSet<>();
    Set<Long> usuarioIds = new HashSet<>();
    for (FilaPrestamo p : filas) {
      materialIds.add(p.materialId());
      personaIds.add(p.responsableId());
      if (p.contratoId() != null) {
        contratoIds.add(p.contratoId());
      }
      usuarioIds.add(p.usuarioPresta());
      if (p.usuarioRecibe() != null) {
        usuarioIds.add(p.usuarioRecibe());
      }
    }
    Map<Long, MaterialInfo> materiales = nombres.materiales(materialIds);
    Map<Long, String> responsables = nombres.personas(personaIds);
    Map<Long, ContratoInfo> contratos = nombres.contratos(contratoIds);
    Map<Long, String> usuarios = nombres.usuarios(usuarioIds);
    Map<Long, String> claves = nombres.almacenes();
    LocalDate hoy = hoy();
    return filas.stream()
        .map(
            p -> {
              MaterialInfo m = materiales.get(p.materialId());
              ContratoInfo c = p.contratoId() == null ? null : contratos.get(p.contratoId());
              return new Prestamo(
                  p.id(),
                  p.folio(),
                  p.almacenId(),
                  claves.getOrDefault(p.almacenId(), ""),
                  p.materialId(),
                  m == null ? "" : m.clave(),
                  m == null ? "" : m.nombre(),
                  m == null ? "" : m.unidadControl(),
                  p.cantidad(),
                  p.responsableId(),
                  responsables.getOrDefault(p.responsableId(), ""),
                  p.contratoId(),
                  c == null ? null : c.codigo(),
                  p.fecha(),
                  p.compromiso(),
                  p.estado(),
                  p.estado().equals("abierto")
                      && p.compromiso() != null
                      && p.compromiso().isBefore(hoy),
                  p.devueltoEn(),
                  usuarios.getOrDefault(p.usuarioPresta(), ""),
                  p.usuarioRecibe() == null ? null : usuarios.get(p.usuarioRecibe()),
                  p.observaciones(),
                  p.version());
            })
        .toList();
  }
}

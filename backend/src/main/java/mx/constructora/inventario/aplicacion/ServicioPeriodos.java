package mx.constructora.inventario.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static mx.constructora.inventario.aplicacion.Sesion.ADMINISTRAR;
import static mx.constructora.inventario.aplicacion.Sesion.CONSULTAR;
import static mx.constructora.inventario.aplicacion.Sesion.empresa;
import static mx.constructora.inventario.aplicacion.Sesion.hoy;
import static mx.constructora.inventario.aplicacion.Sesion.usuario;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mx.constructora.compartido.Autorizacion;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Permisos;
import mx.constructora.inventario.infraestructura.RepositorioPeriodos;
import mx.constructora.inventario.infraestructura.RepositorioPeriodos.FilaPeriodo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cierre de mes. Un mes se cierra cuando ya terminó, los anteriores están cerrados y no le quedan
 * documentos sin aplicar o cancelar. Cerrado, la base no acepta movimientos con fecha en él y se
 * guarda la foto del inventario (existencia y valor por almacén y material). No se reabre desde la
 * aplicación.
 */
@Service
public class ServicioPeriodos {

  /** Valor del inventario de un almacén al cierre. */
  public record ValorAlmacen(
      @Schema(requiredMode = REQUIRED) long almacenId,
      @Schema(requiredMode = REQUIRED) String almacen,
      @Schema(description = "Solo para quien ve costos") BigDecimal valor,
      @Schema(requiredMode = REQUIRED) int materiales) {}

  /** Mes cerrado. */
  public record Periodo(
      @Schema(requiredMode = REQUIRED) int anio,
      @Schema(requiredMode = REQUIRED) int mes,
      @Schema(requiredMode = REQUIRED) String cerro,
      @Schema(requiredMode = REQUIRED) OffsetDateTime cerradoEn,
      @Schema(requiredMode = REQUIRED) List<ValorAlmacen> valor) {}

  /** Meses cerrados y el siguiente por cerrar. */
  public record Periodos(
      @Schema(requiredMode = REQUIRED) List<Periodo> cerrados,
      @Schema(description = "Siguiente mes por cerrar (AAAA-MM); vacío si no hay movimientos")
          String siguiente,
      @Schema(requiredMode = REQUIRED, description = "Documentos sin aplicar en el siguiente mes")
          int pendientesSiguiente,
      @Schema(requiredMode = REQUIRED, description = "El siguiente mes ya terminó")
          boolean siguienteTerminado) {}

  private final RepositorioPeriodos repositorio;
  private final Nombres nombres;

  public ServicioPeriodos(RepositorioPeriodos repositorio, Nombres nombres) {
    this.repositorio = repositorio;
    this.nombres = nombres;
  }

  @PreAuthorize(CONSULTAR)
  @Transactional(readOnly = true)
  public Periodos periodos() {
    List<FilaPeriodo> cerrados = repositorio.cerrados(empresa());
    Set<Long> usuarioIds = new HashSet<>();
    cerrados.forEach(p -> usuarioIds.add(p.cerradoPor()));
    Map<Long, String> usuarios = nombres.usuarios(usuarioIds);
    Map<Long, String> claves = nombres.almacenes();
    boolean costos = Autorizacion.tieneAlguno(Permisos.PRECIO_VER, Permisos.COSTO_VER_TODO);
    List<Periodo> lista =
        cerrados.stream()
            .map(
                p ->
                    new Periodo(
                        p.anio(),
                        p.mes(),
                        usuarios.getOrDefault(p.cerradoPor(), ""),
                        p.cerradoEn(),
                        repositorio.valorAlCierre(empresa(), p.anio(), p.mes()).stream()
                            .map(
                                v ->
                                    new ValorAlmacen(
                                        v.almacenId(),
                                        claves.getOrDefault(v.almacenId(), ""),
                                        costos ? v.valor() : null,
                                        v.materiales()))
                            .toList()))
            .toList();
    YearMonth siguiente = siguiente(cerrados);
    if (siguiente == null) {
      return new Periodos(lista, null, 0, false);
    }
    return new Periodos(
        lista,
        siguiente.toString(),
        repositorio.pendientes(empresa(), siguiente.atDay(1), siguiente.atEndOfMonth()),
        siguiente.atEndOfMonth().isBefore(hoy()));
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public Periodos cerrar(int anio, int mes) {
    if (mes < 1 || mes > 12 || anio < 2020 || anio > 2100) {
      throw ErrorNegocio.invalido("MES_INVALIDO", "Mes inválido");
    }
    YearMonth periodo = YearMonth.of(anio, mes);
    LocalDate fin = periodo.atEndOfMonth();
    if (!fin.isBefore(hoy())) {
      throw ErrorNegocio.conflicto("MES_EN_CURSO", "El mes todavía no termina");
    }
    YearMonth siguiente = siguiente(repositorio.cerrados(empresa()));
    if (siguiente != null && periodo.isAfter(siguiente)) {
      throw ErrorNegocio.conflicto(
          "MES_ANTERIOR_ABIERTO",
          "Primero cierra " + siguiente + ": los meses se cierran en orden");
    }
    int pendientes = repositorio.pendientes(empresa(), periodo.atDay(1), fin);
    if (pendientes > 0) {
      throw ErrorNegocio.conflicto(
          "MES_CON_PENDIENTES",
          "Hay " + pendientes + " documentos de " + periodo + " sin aplicar ni cancelar");
    }
    try {
      repositorio.cerrar(empresa(), anio, mes, fin, usuario());
    } catch (DuplicateKeyException e) {
      throw ErrorNegocio.conflicto("MES_YA_CERRADO", "El mes " + periodo + " ya está cerrado");
    }
    return periodos();
  }

  /** El mes más antiguo sin cerrar desde el primer movimiento; {@code null} sin movimientos. */
  private YearMonth siguiente(List<FilaPeriodo> cerrados) {
    if (!cerrados.isEmpty()) {
      FilaPeriodo ultimo = cerrados.getFirst();
      return YearMonth.of(ultimo.anio(), ultimo.mes()).plusMonths(1);
    }
    LocalDate primero = repositorio.primerMovimiento(empresa());
    return primero == null ? null : YearMonth.from(primero);
  }
}

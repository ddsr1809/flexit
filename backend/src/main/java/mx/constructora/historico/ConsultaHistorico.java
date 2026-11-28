package mx.constructora.historico;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.historico.infraestructura.RepositorioHistorico;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que otros módulos consultan del histórico del Excel sin tocar sus tablas: el último proveedor
 * de cada material (para sugerir a quién pedir los faltantes, H6), las salidas de una carga con su
 * renglón del Excel (para conciliar un contrato contra la hoja de su frente) y, para el corte (H8),
 * los préstamos que siguen abiertos, el costo ponderado de cada material a la fecha del corte y la
 * existencia que decía el Excel. No revisa permisos: quien llama ya los exigió.
 */
@Service
public class ConsultaHistorico {

  /** Proveedor y fecha de la última entrada del material en el Excel. */
  public record ProveedorDeMaterial(long proveedorId, LocalDate fecha) {}

  /** Una salida del histórico con el renglón de la hoja Salidas de donde salió. */
  public record SalidaDeCarga(
      long id,
      int filaExcel,
      String tipo,
      long materialId,
      BigDecimal cantidad,
      Long frenteId,
      Long contratoId,
      Long unidadObraId,
      String lotesTexto,
      Integer clasificacionId,
      boolean entregado,
      BigDecimal importe,
      boolean costoEstimado) {}

  /** Un préstamo del Excel que no se devolvió. */
  public record PrestamoAbierto(
      long id,
      Integer filaExcel,
      LocalDate fecha,
      Long materialId,
      String articulo,
      BigDecimal cantidad,
      Long solicitanteId,
      String solicitanteTexto,
      Long entregarAId,
      String entregarATexto) {}

  /**
   * Ponderado sin IVA de las entradas del material hasta la fecha del corte. {@code estimadas}:
   * cuántas de esas entradas se valuaron con el precio de entradas cercanas (no traían precio).
   */
  public record CostoAlCorte(
      long materialId, BigDecimal unitario, int entradas, int estimadas, LocalDate ultima) {}

  /**
   * Existencia que decía el Excel (Existencias!J = inventario inicial + entradas − salidas
   * entregadas), recalculada desde lo capturado. Es de todo el almacén del Excel, sin frente.
   */
  public record ExistenciaExcel(
      long materialId,
      BigDecimal inicial,
      BigDecimal entradas,
      BigDecimal salidas,
      BigDecimal existencia) {}

  private final RepositorioHistorico repositorio;

  ConsultaHistorico(RepositorioHistorico repositorio) {
    this.repositorio = repositorio;
  }

  @Transactional(readOnly = true)
  public Map<Long, ProveedorDeMaterial> ultimosProveedores(Collection<Long> materiales) {
    Map<Long, ProveedorDeMaterial> resultado = new HashMap<>();
    repositorio
        .ultimosProveedores(empresa(), materiales)
        .forEach(
            f ->
                resultado.put(f.materialId(), new ProveedorDeMaterial(f.proveedorId(), f.fecha())));
    return resultado;
  }

  /** Las salidas de la carga para un frente, en el orden de la hoja. */
  @Transactional(readOnly = true)
  public List<SalidaDeCarga> salidasDeCarga(long carga, long frente) {
    return repositorio.salidasDeCarga(empresa(), carga, frente).stream()
        .map(
            f ->
                new SalidaDeCarga(
                    f.id(),
                    f.filaExcel(),
                    f.tipo(),
                    f.materialId(),
                    f.cantidad(),
                    f.frenteId(),
                    f.contratoId(),
                    f.unidadObraId(),
                    f.lotesTexto(),
                    f.clasificacionId(),
                    f.entregado(),
                    f.importe(),
                    f.costoEstimado()))
        .toList();
  }

  /** Préstamos del Excel del frente que siguen abiertos, en el orden de la hoja. */
  @Transactional(readOnly = true)
  public List<PrestamoAbierto> prestamosAbiertos(long frente) {
    return repositorio.prestamosAbiertos(empresa(), frente).stream()
        .map(
            f ->
                new PrestamoAbierto(
                    f.id(),
                    f.filaExcel(),
                    f.fecha(),
                    f.materialId(),
                    f.articulo(),
                    f.cantidad(),
                    f.solicitanteId(),
                    f.solicitanteTexto(),
                    f.entregarAId(),
                    f.entregarATexto()))
        .toList();
  }

  /** Costo ponderado sin IVA a la fecha; los materiales sin entradas con importe no vienen. */
  @Transactional(readOnly = true)
  public Map<Long, CostoAlCorte> costosAlCorte(Collection<Long> materiales, LocalDate fecha) {
    Map<Long, CostoAlCorte> resultado = new HashMap<>();
    for (var f : repositorio.costosAlCorte(empresa(), materiales, fecha)) {
      if (f.cantidad().signum() > 0) {
        resultado.put(
            f.materialId(),
            new CostoAlCorte(
                f.materialId(),
                f.importe().divide(f.cantidad(), 6, RoundingMode.HALF_UP),
                f.entradas(),
                f.estimadas(),
                f.ultima()));
      }
    }
    return resultado;
  }

  /** Existencia del Excel por material; vacío si no se cargó la foto de Existencias. */
  @Transactional(readOnly = true)
  public Map<Long, ExistenciaExcel> existenciasExcel(Collection<Long> materiales) {
    Map<Long, ExistenciaExcel> resultado = new HashMap<>();
    if (!repositorio.hayFotoDeExistencias(empresa())) {
      return resultado;
    }
    for (var f : repositorio.existenciasExcel(empresa(), materiales)) {
      resultado.put(
          f.materialId(),
          new ExistenciaExcel(
              f.materialId(), f.inicial(), f.entradas(), f.salidas(), f.existencia()));
    }
    return resultado;
  }

  /** {@code true} si ya se cargó la foto de la hoja Existencias. */
  @Transactional(readOnly = true)
  public boolean hayFotoDeExistencias() {
    return repositorio.hayFotoDeExistencias(empresa());
  }

  private static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }
}

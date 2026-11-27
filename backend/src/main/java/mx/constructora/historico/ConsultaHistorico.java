package mx.constructora.historico;

import java.math.BigDecimal;
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
 * de cada material (para sugerir a quién pedir los faltantes, H6) y las salidas de una carga con su
 * renglón del Excel (para conciliar un contrato contra la hoja de su frente). No revisa permisos:
 * quien llama ya los exigió.
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

  private static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }
}

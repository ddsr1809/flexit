package mx.constructora.abastecimiento;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import mx.constructora.abastecimiento.aplicacion.ServicioRequisiciones;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que otros módulos (reportes) leen del abastecimiento. Aplica el alcance del residente; los
 * permisos los revisa quien llama.
 */
@Service
public class AbastecimientoApi {

  /** Un renglón de requisición contra lo que se ha surtido, en unidad de control. */
  public record SurtidoRequisicion(
      long requisicionId,
      String folio,
      String estado,
      LocalDate fecha,
      LocalDate fechaRequerida,
      long contratoId,
      String contrato,
      long unidadObraId,
      String unidadObra,
      String materialClave,
      String material,
      String unidad,
      BigDecimal pedido,
      BigDecimal surtido,
      BigDecimal enVale,
      BigDecimal reservado,
      BigDecimal porRecibir,
      BigDecimal faltante,
      BigDecimal porcentaje) {}

  private final ServicioRequisiciones requisiciones;

  AbastecimientoApi(ServicioRequisiciones requisiciones) {
    this.requisiciones = requisiciones;
  }

  /**
   * Requisiciones autorizadas o cerradas del periodo (por fecha de captura), renglón por renglón,
   * contra lo surtido.
   */
  @Transactional(readOnly = true)
  public List<SurtidoRequisicion> requisicionVsSurtido(
      Long contratoId, LocalDate desde, LocalDate hasta) {
    return requisiciones.surtidoDelPeriodo(contratoId, desde, hasta);
  }
}

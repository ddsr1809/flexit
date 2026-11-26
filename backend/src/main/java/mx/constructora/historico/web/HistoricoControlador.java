package mx.constructora.historico.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import mx.constructora.historico.aplicacion.ServicioHistorico;
import mx.constructora.historico.aplicacion.ServicioHistorico.HistoriaUnidad;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Consulta del histórico del Excel. */
@RestController
@RequestMapping("/api/v1/historico")
@Tag(name = "Histórico")
class HistoricoControlador {

  private final ServicioHistorico servicio;

  HistoricoControlador(ServicioHistorico servicio) {
    this.servicio = servicio;
  }

  @GetMapping("/unidades/{id}")
  @Operation(
      operationId = "historiaDeUnidad",
      summary =
          "Lo que dice el Excel de un lote: sus vales, sus pagos de destajo y las altas del IMSS"
              + " de su contrato")
  HistoriaUnidad unidad(@PathVariable long id) {
    return servicio.deUnidad(id);
  }
}

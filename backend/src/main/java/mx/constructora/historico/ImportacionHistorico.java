package mx.constructora.historico;

import java.util.List;
import java.util.Map;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.historico.Registros.EntradaValorada;
import mx.constructora.historico.Registros.Lote;
import mx.constructora.historico.infraestructura.RepositorioHistorico;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que la migración desde Excel (módulo importacion) escribe y lee del histórico. Todo queda
 * marcado con la carga que lo trajo para poder revertirla completa.
 *
 * <p>No revisa permisos: lo llama solo el servicio de importación, que exige {@code
 * sistema:administrar}. Las defensas de la base (RLS, FK compuestas, histórico inmutable) aplican
 * igual.
 */
@Service
public class ImportacionHistorico {

  private final RepositorioHistorico repositorio;

  ImportacionHistorico(RepositorioHistorico repositorio) {
    this.repositorio = repositorio;
  }

  /**
   * Escribe todo el lote de una carga.
   *
   * @return renglones escritos por tabla ({@code entrada}, {@code salida}, {@code pago_destajo}…)
   */
  @Transactional
  public Map<String, Long> guardar(long carga, Lote lote) {
    return repositorio.guardar(empresa(), carga, lote);
  }

  /** Entradas del histórico con importe: la base para valuar las salidas. */
  @Transactional(readOnly = true)
  public List<EntradaValorada> entradasValoradas() {
    return repositorio.entradasValoradas(empresa());
  }

  /** Borra lo que escribió la carga. */
  @Transactional
  public int revertirCarga(long carga) {
    return repositorio.revertir(carga);
  }

  private static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }
}

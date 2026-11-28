package mx.constructora.inventario;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.Folios;
import mx.constructora.inventario.infraestructura.RepositorioActividad;
import mx.constructora.inventario.infraestructura.RepositorioActividad.FilaOtros;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que el reporte de estabilización (H9) lee del inventario: cómo se usa un almacén día por día,
 * qué se capturó tarde, qué sigue sin aplicar y el último mes cerrado. Solo lectura; los permisos
 * los revisa quien llama.
 */
@Service
public class ActividadAlmacen {

  /** Documentos de un día, por fecha del documento. */
  public record Dia(
      LocalDate fecha, int documentos, int salidas, int entradas, int tardios, int cancelados) {}

  /** Un documento capturado más días después de su fecha de lo que se tolera. */
  public record Tardio(
      String folio, String tipo, String estado, LocalDate fecha, LocalDate capturado, int dias) {}

  /** Un documento que sigue sin aplicar ni cancelar. */
  public record Pendiente(String folio, String tipo, String estado, LocalDate fecha, int dias) {}

  /** Todo lo del almacén en el periodo. */
  public record Actividad(
      LocalDate apertura,
      List<Dia> dias,
      List<Tardio> tardios,
      List<Pendiente> pendientes,
      int totalPendientes,
      int prestamosAbiertos,
      int prestamosVencidos,
      int conteosCerrados,
      int ajustesDeConteo,
      BigDecimal medianaHorasVale) {}

  private final RepositorioActividad repositorio;

  ActividadAlmacen(RepositorioActividad repositorio) {
    this.repositorio = repositorio;
  }

  /** La fecha del saldo de apertura del almacén, si ya abrió. */
  @Transactional(readOnly = true)
  public Optional<LocalDate> apertura(long almacenId) {
    return repositorio.apertura(empresa(), almacenId);
  }

  /**
   * Actividad del almacén de {@code desde} a {@code hasta}. Un documento es tardío si se capturó
   * más de {@code tolerancia} días después de su fecha.
   */
  @Transactional(readOnly = true)
  public Actividad actividad(long almacenId, LocalDate desde, LocalDate hasta, int tolerancia) {
    long empresa = empresa();
    LocalDate hoy = LocalDate.now(Folios.ZONA);
    FilaOtros otros = repositorio.otros(empresa, almacenId, desde, hasta, hoy);
    return new Actividad(
        repositorio.apertura(empresa, almacenId).orElse(null),
        repositorio.dias(empresa, almacenId, desde, hasta, tolerancia).stream()
            .map(
                d ->
                    new Dia(
                        d.fecha(),
                        d.documentos(),
                        d.salidas(),
                        d.entradas(),
                        d.tardios(),
                        d.cancelados()))
            .toList(),
        repositorio.tardios(empresa, almacenId, desde, hasta, tolerancia).stream()
            .map(
                t ->
                    new Tardio(t.folio(), t.tipo(), t.estado(), t.fecha(), t.capturado(), t.dias()))
            .toList(),
        repositorio.pendientes(empresa, almacenId, hoy).stream()
            .map(p -> new Pendiente(p.folio(), p.tipo(), p.estado(), p.fecha(), p.dias()))
            .toList(),
        repositorio.totalPendientes(empresa, almacenId),
        otros.prestamosAbiertos(),
        otros.prestamosVencidos(),
        otros.conteosCerrados(),
        otros.ajustesDeConteo(),
        otros.medianaHorasVale());
  }

  /** El último mes cerrado de la empresa (AAAA-MM), si hay. */
  @Transactional(readOnly = true)
  public Optional<String> ultimoMesCerrado() {
    return repositorio.ultimoMesCerrado(empresa());
  }

  private static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }
}

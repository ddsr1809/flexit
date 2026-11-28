package mx.constructora.soporte;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.soporte.infraestructura.RepositorioIncidentes;
import mx.constructora.soporte.infraestructura.RepositorioIncidentes.Conteo;
import mx.constructora.soporte.infraestructura.RepositorioIncidentes.Periodo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que otros módulos (reportes) leen de los incidentes: cuántos hay abiertos por severidad y
 * qué pasó en un periodo. Son de toda la empresa: un incidente crítico cuenta aunque no sea de un
 * almacén (por ejemplo, si nadie puede iniciar sesión). Los permisos los revisa quien llama.
 */
@Service
public class SoporteApi {

  /** Un incidente crítico que sigue abierto. */
  public record CriticoAbierto(long id, String folio, String titulo, OffsetDateTime reportadoEn) {}

  /** Incidentes de hoy y del periodo. */
  public record ResumenIncidentes(
      int criticosAbiertos,
      int mayoresAbiertos,
      int menoresAbiertos,
      int reportados,
      int resueltos,
      BigDecimal medianaHorasResolucion,
      List<CriticoAbierto> criticos) {}

  private final RepositorioIncidentes repositorio;

  SoporteApi(RepositorioIncidentes repositorio) {
    this.repositorio = repositorio;
  }

  /** Abiertos hoy por severidad; reportados y resueltos entre {@code desde} y {@code hasta}. */
  @Transactional(readOnly = true)
  public ResumenIncidentes resumen(LocalDate desde, LocalDate hasta) {
    long empresa = ContextoEmpresa.requerida().empresaId();
    List<Conteo> conteo = repositorio.conteo(empresa, null);
    Periodo periodo = repositorio.periodo(empresa, null, desde, hasta);
    List<CriticoAbierto> criticos =
        repositorio.incidentes(empresa, "abierto", "critica", null, null).stream()
            .map(f -> new CriticoAbierto(f.id(), f.folio(), f.titulo(), f.reportadoEn()))
            .toList();
    return new ResumenIncidentes(
        abiertos(conteo, "critica"),
        abiertos(conteo, "mayor"),
        abiertos(conteo, "menor"),
        periodo.reportados(),
        periodo.resueltos(),
        periodo.medianaHorasResolucion(),
        criticos);
  }

  private static int abiertos(List<Conteo> conteo, String severidad) {
    return conteo.stream()
        .filter(c -> c.estado().equals("abierto") && c.severidad().equals(severidad))
        .mapToInt(Conteo::cuantos)
        .sum();
  }
}

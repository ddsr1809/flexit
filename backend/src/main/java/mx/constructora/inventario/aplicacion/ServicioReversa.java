package mx.constructora.inventario.aplicacion;

import static mx.constructora.inventario.aplicacion.Sesion.empresa;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import mx.constructora.catalogo.CatalogoApi.MaterialInfo;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.LibroExcel;
import mx.constructora.compartido.LibroExcel.Columna;
import mx.constructora.compartido.LibroExcel.Formato;
import mx.constructora.compartido.LibroExcel.Logico;
import mx.constructora.compartido.Permisos;
import mx.constructora.estructura.EstructuraApi;
import mx.constructora.estructura.EstructuraApi.PaqueteInfo;
import mx.constructora.estructura.EstructuraApi.UnidadDetalle;
import mx.constructora.inventario.infraestructura.RepositorioAlmacenes.FilaAlmacen;
import mx.constructora.inventario.infraestructura.RepositorioSaldos;
import mx.constructora.inventario.infraestructura.RepositorioSaldos.CargoDeDocumento;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plan de reversa del corte (H8): lo que el almacén capturó en el sistema, escrito con las columnas
 * de la hoja Salidas del Excel (A a O), para pegarlo en el Excel si hubiera que regresar a él. Cada
 * cargo a obra es un renglón: el vale en positivo, la devolución en negativo y la reasignación como
 * dos renglones ("Ajuste de materiales en lotes"), igual que se capturaban en el Excel.
 */
@Service
public class ServicioReversa {

  private static final String CAPTURAR_O_ADMINISTRAR =
      "hasAnyAuthority('" + Permisos.MOVIMIENTO_CAPTURAR + "', '" + Permisos.ADMINISTRAR + "')";

  /** Máximo de días por archivo: la reversa es de la primera semana del piloto. */
  static final int DIAS_MAXIMOS = 92;

  private final RepositorioSaldos saldos;
  private final ServicioAlmacenes almacenes;
  private final EstructuraApi estructura;
  private final Nombres nombres;

  public ServicioReversa(
      RepositorioSaldos saldos,
      ServicioAlmacenes almacenes,
      EstructuraApi estructura,
      Nombres nombres) {
    this.saldos = saldos;
    this.almacenes = almacenes;
    this.estructura = estructura;
    this.nombres = nombres;
  }

  /** El archivo con el formato de Salidas, de {@code desde} a {@code hasta} (inclusive). */
  @PreAuthorize(CAPTURAR_O_ADMINISTRAR)
  @Transactional(readOnly = true)
  public byte[] salidasComoExcel(long almacenId, LocalDate desde, LocalDate hasta) {
    if (hasta.isBefore(desde)) {
      throw ErrorNegocio.invalido("FECHAS_INVALIDAS", "La fecha final es anterior a la inicial");
    }
    if (desde.plusDays(DIAS_MAXIMOS).isBefore(hasta)) {
      throw ErrorNegocio.invalido(
          "RANGO_GRANDE", "Exporta a lo más " + DIAS_MAXIMOS + " días por archivo");
    }
    FilaAlmacen almacen = almacenes.existente(almacenId);
    String frente =
        estructura.frente(almacen.frenteId()).map(EstructuraApi.FrenteInfo::nombre).orElse("");
    List<CargoDeDocumento> cargos = saldos.cargosDeAlmacen(empresa(), almacenId, desde, hasta);

    Set<Long> materialIds = new HashSet<>();
    Set<Long> personaIds = new HashSet<>();
    Set<Long> paqueteIds = new HashSet<>();
    Set<Long> contratoIds = new HashSet<>();
    for (CargoDeDocumento c : cargos) {
      materialIds.add(c.materialId());
      paqueteIds.add(c.paqueteId());
      contratoIds.add(c.contratoId());
      if (c.solicitanteId() != null) {
        personaIds.add(c.solicitanteId());
      }
    }
    Map<Long, MaterialInfo> materiales = nombres.materiales(materialIds);
    Map<Long, String> personas = nombres.personas(personaIds);
    Map<Long, PaqueteInfo> paquetes = nombres.paquetes(paqueteIds);
    Map<Integer, String> razones = nombres.clasificaciones();
    Map<Long, UnidadDetalle> unidades = new HashMap<>();
    for (Long contrato : contratoIds) {
      estructura.unidadesDeContrato(contrato).forEach(u -> unidades.put(u.id(), u));
    }

    LibroExcel libro = new LibroExcel();
    var hoja =
        libro.hoja(
            "Salidas",
            List.of(
                new Columna("Fecha", Formato.FECHA, 12),
                Columna.texto("Documento", 12),
                Columna.texto("Razón", 18),
                Columna.texto("Folio", 18),
                Columna.texto("Paqueteria", 30),
                Columna.texto("Mza", 6),
                Columna.texto("Lote", 6),
                Columna.cantidad("Cant."),
                Columna.texto("Unidad", 10),
                Columna.texto("Artículo", 44),
                Columna.texto("Solicitante", 24),
                Columna.texto("Frente", 16),
                Columna.texto("Entregar a:", 24),
                Columna.texto("Entregado", 10),
                Columna.texto("Observaciones", 40)));
    for (CargoDeDocumento c : cargos) {
      MaterialInfo m = materiales.get(c.materialId());
      UnidadDetalle u = unidades.get(c.unidadObraId());
      PaqueteInfo p = paquetes.get(c.paqueteId());
      String solicitante =
          c.solicitanteId() == null ? null : personas.getOrDefault(c.solicitanteId(), null);
      hoja.fila(
          c.fecha(),
          "Vale",
          razones.getOrDefault(c.clasificacionId(), ""),
          c.folio(),
          p == null ? null : p.nombre(),
          u == null ? null : u.manzana(),
          u == null ? null : u.lote(),
          c.cantidad(),
          m == null ? null : m.unidadControl(),
          m == null ? null : m.nombre(),
          solicitante,
          frente,
          solicitante,
          new Logico(true),
          observacion(c));
    }
    return libro.bytes();
  }

  private static String observacion(CargoDeDocumento c) {
    return switch (c.tipo()) {
      case "reasignacion" -> "Ajuste de materiales en lotes (" + c.folio() + ")";
      case "devolucion" -> "Devolución " + c.folio();
      default -> Objects.equals(c.tipo(), "salida") ? "Del sistema" : c.tipo();
    };
  }
}

package mx.constructora.inventario.aplicacion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import mx.constructora.catalogo.CatalogoApi.MaterialInfo;
import mx.constructora.compartido.Autorizacion;
import mx.constructora.compartido.Permisos;
import mx.constructora.estructura.EstructuraApi;
import mx.constructora.estructura.EstructuraApi.ContratoInfo;
import mx.constructora.estructura.EstructuraApi.PaqueteInfo;
import mx.constructora.estructura.EstructuraApi.UnidadInfo;
import mx.constructora.inventario.aplicacion.ServicioMovimientos.Acciones;
import mx.constructora.inventario.aplicacion.ServicioMovimientos.LineaMovimiento;
import mx.constructora.inventario.aplicacion.ServicioMovimientos.Movimiento;
import mx.constructora.inventario.aplicacion.ServicioMovimientos.ResumenMovimiento;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.FilaLinea;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.FilaMovimiento;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.FilaResumen;
import org.springframework.stereotype.Component;

/**
 * Arma lo que ve el usuario: nombres en lugar de ids, y costos solo para quien los ve. El
 * almacenista ve los precios que él capturó de la factura, pero no el costo promedio ni los
 * importes (matriz de permisos: "Ver costos — almacenista: solo existencias").
 */
@Component
class Presentador {

  private final Nombres nombres;
  private final EstructuraApi estructura;

  Presentador(Nombres nombres, EstructuraApi estructura) {
    this.nombres = nombres;
    this.estructura = estructura;
  }

  Movimiento movimiento(FilaMovimiento m, List<FilaLinea> lineas, Acciones acciones) {
    Set<Long> materialIds = new HashSet<>();
    Set<Long> contratoIds = new HashSet<>();
    Set<Long> unidadIds = new HashSet<>();
    Set<Long> paqueteIds = new HashSet<>();
    for (FilaLinea l : lineas) {
      materialIds.add(l.materialId());
      contratoIds.add(l.contratoId());
      unidadIds.add(l.unidadObraId());
      paqueteIds.add(l.paqueteId());
    }
    contratoIds.remove(null);
    unidadIds.remove(null);
    paqueteIds.remove(null);
    Map<Long, MaterialInfo> materiales = nombres.materiales(materialIds);
    Map<Long, String> unidadesMedida = nombres.unidadesMedida();
    Map<Long, ContratoInfo> contratos = nombres.contratos(contratoIds);
    Map<Long, UnidadInfo> unidades = nombres.unidades(unidadIds);
    Map<Long, PaqueteInfo> paquetes = nombres.paquetes(paqueteIds);
    Map<Integer, String> clasificaciones = nombres.clasificaciones();
    Map<Long, String> usuarios =
        nombres.usuarios(listaSinNulos(m.usuarioCaptura(), m.usuarioAutoriza(), m.usuarioAplica()));
    Map<Long, String> almacenes = nombres.almacenes();

    boolean costos = veCostos(contratoIds);
    boolean precios = costos || Autorizacion.tiene(Permisos.MOVIMIENTO_CAPTURAR);

    List<LineaMovimiento> salida = new ArrayList<>();
    BigDecimal total = null;
    for (FilaLinea l : lineas) {
      MaterialInfo material = materiales.get(l.materialId());
      ContratoInfo contrato = l.contratoId() == null ? null : contratos.get(l.contratoId());
      UnidadInfo unidad = l.unidadObraId() == null ? null : unidades.get(l.unidadObraId());
      PaqueteInfo paquete = l.paqueteId() == null ? null : paquetes.get(l.paqueteId());
      salida.add(
          new LineaMovimiento(
              l.id(),
              l.renglon(),
              l.materialId(),
              material == null ? "" : material.clave(),
              material == null ? "" : material.nombre(),
              material == null ? "" : material.unidadControl(),
              l.cantidad(),
              l.cantidadCaptura(),
              l.unidadCapturaId(),
              unidadesMedida.getOrDefault(l.unidadCapturaId(), ""),
              precios ? l.precioCaptura() : null,
              precios ? l.descuento() : null,
              l.tasaIva(),
              costos ? l.costoNuevo() : null,
              costos ? l.costoUnitario() : null,
              costos ? l.importe() : null,
              l.contratoId(),
              contrato == null ? null : contrato.codigo(),
              l.unidadObraId(),
              unidad == null ? null : unidad.clave(),
              l.paqueteId(),
              paquete == null ? null : paquete.clave() + " " + paquete.nombre(),
              l.clasificacionId(),
              l.clasificacionId() == null ? null : clasificaciones.get(l.clasificacionId()),
              l.lineaOrigenId()));
      if (costos && l.importe() != null) {
        total = (total == null ? BigDecimal.ZERO : total).add(l.importe());
      }
    }
    String proveedor =
        m.proveedorId() == null
            ? null
            : nombres.proveedores(List.of(m.proveedorId())).get(m.proveedorId());
    String solicitante =
        m.solicitanteId() == null
            ? null
            : nombres.personas(List.of(m.solicitanteId())).get(m.solicitanteId());
    return new Movimiento(
        m.id(),
        m.folio(),
        m.tipo(),
        m.estado(),
        m.fecha(),
        m.almacenId(),
        almacenes.getOrDefault(m.almacenId(), ""),
        m.almacenDestinoId(),
        m.almacenDestinoId() == null ? null : almacenes.get(m.almacenDestinoId()),
        m.proveedorId(),
        proveedor,
        m.folioFactura(),
        precios ? m.flete() : null,
        m.solicitanteId(),
        solicitante,
        m.movimientoOrigenId(),
        m.conteoId(),
        m.prestamoId(),
        m.archivoId(),
        m.motivo(),
        m.notaRechazo(),
        m.motivoCancelacion(),
        m.observaciones(),
        usuarios.getOrDefault(m.usuarioCaptura(), ""),
        m.usuarioAutoriza() == null ? null : usuarios.get(m.usuarioAutoriza()),
        m.usuarioAplica() == null ? null : usuarios.get(m.usuarioAplica()),
        m.autorizadoEn(),
        m.aplicadoEn(),
        m.canceladoEn(),
        m.creado(),
        total,
        m.version(),
        salida,
        acciones);
  }

  List<ResumenMovimiento> resumenes(List<FilaResumen> filas) {
    Set<Long> usuarioIds = new HashSet<>();
    Set<Long> personaIds = new HashSet<>();
    Set<Long> contratoIds = new HashSet<>();
    for (FilaResumen f : filas) {
      usuarioIds.add(f.usuarioCaptura());
      usuarioIds.add(f.usuarioAutoriza());
      personaIds.add(f.solicitanteId());
      contratoIds.addAll(contratosDe(f));
    }
    usuarioIds.remove(null);
    personaIds.remove(null);
    Map<Long, String> usuarios = nombres.usuarios(usuarioIds);
    Map<Long, String> personas = nombres.personas(personaIds);
    Map<Long, ContratoInfo> contratos = nombres.contratos(contratoIds);
    Map<Long, String> almacenes = nombres.almacenes();
    boolean todos = Autorizacion.tieneAlguno(Permisos.PRECIO_VER, Permisos.COSTO_VER_TODO);
    Set<Long> propios =
        Autorizacion.tiene(Permisos.COSTO_VER_PROPIO) ? estructura.contratosVisibles() : Set.of();
    return filas.stream()
        .map(
            f -> {
              List<Long> deFila = contratosDe(f);
              boolean costos = todos || (!deFila.isEmpty() && propios.containsAll(deFila));
              return new ResumenMovimiento(
                  f.id(),
                  f.folio(),
                  f.tipo(),
                  f.estado(),
                  f.fecha(),
                  almacenes.getOrDefault(f.almacenId(), ""),
                  f.almacenDestinoId() == null ? null : almacenes.get(f.almacenDestinoId()),
                  usuarios.getOrDefault(f.usuarioCaptura(), ""),
                  f.usuarioAutoriza() == null ? null : usuarios.get(f.usuarioAutoriza()),
                  f.solicitanteId() == null ? null : personas.get(f.solicitanteId()),
                  f.lineas(),
                  deFila.stream()
                      .map(contratos::get)
                      .filter(Objects::nonNull)
                      .map(ContratoInfo::codigo)
                      .sorted()
                      .toList(),
                  costos ? f.importe() : null,
                  f.creado());
            })
        .toList();
  }

  /**
   * Costos: con {@code precio:ver} o {@code costo:ver-todo}, siempre; con {@code costo:ver-propio}
   * (residente), si todos los contratos del documento son suyos.
   */
  boolean veCostos(Set<Long> contratoIds) {
    if (Autorizacion.tieneAlguno(Permisos.PRECIO_VER, Permisos.COSTO_VER_TODO)) {
      return true;
    }
    return Autorizacion.tiene(Permisos.COSTO_VER_PROPIO)
        && !contratoIds.isEmpty()
        && estructura.contratosVisibles().containsAll(contratoIds);
  }

  private static List<Long> contratosDe(FilaResumen f) {
    if (f.contratos() == null || f.contratos().isBlank()) {
      return List.of();
    }
    return Arrays.stream(f.contratos().split(",")).map(Long::valueOf).toList();
  }

  private static List<Long> listaSinNulos(Long... ids) {
    return Arrays.stream(ids).filter(Objects::nonNull).toList();
  }
}

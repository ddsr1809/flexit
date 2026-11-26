package mx.constructora.inventario.aplicacion;

import static mx.constructora.inventario.aplicacion.Sesion.empresa;
import static mx.constructora.inventario.aplicacion.Sesion.hoy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import mx.constructora.catalogo.CatalogoApi;
import mx.constructora.catalogo.CatalogoApi.MaterialInfo;
import mx.constructora.catalogo.CatalogoApi.ProveedorInfo;
import mx.constructora.compartido.Almacenamiento;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Textos;
import mx.constructora.estructura.EstructuraApi;
import mx.constructora.estructura.EstructuraApi.ContratoInfo;
import mx.constructora.estructura.EstructuraApi.PaqueteInfo;
import mx.constructora.estructura.EstructuraApi.UnidadInfo;
import mx.constructora.inventario.aplicacion.ServicioMovimientos.DatosMovimiento;
import mx.constructora.inventario.aplicacion.ServicioMovimientos.LineaCaptura;
import mx.constructora.inventario.dominio.Reparto;
import mx.constructora.inventario.dominio.TipoMovimiento;
import mx.constructora.inventario.dominio.Valuacion;
import mx.constructora.inventario.infraestructura.RepositorioAlmacenes.FilaAlmacen;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.Cabecera;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.FilaMovimiento;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.LineaConDocumento;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.NuevaLinea;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.Restante;
import mx.constructora.inventario.infraestructura.RepositorioPeriodos;
import mx.constructora.organizacion.Personas;
import org.springframework.stereotype.Component;

/**
 * Valida lo capturado y lo deja listo para guardar: cabecera y líneas en unidad de control, con el
 * reparto entre lotes ya hecho. Todo lo que se pueda revisar antes de aplicar se revisa aquí, con
 * un código de error claro; al aplicar se vuelve a revisar lo que pudo cambiar (existencia,
 * restante de un vale, estado del contrato).
 */
@Component
class Captura {

  /** Líneas como máximo por documento, después del reparto. */
  static final int LINEAS_MAXIMAS = 1000;

  /** Cabecera y líneas listas para la base. */
  record Preparado(Cabecera cabecera, List<NuevaLinea> lineas) {}

  private final ServicioAlmacenes almacenes;
  private final RepositorioMovimientos movimientos;
  private final RepositorioPeriodos periodos;
  private final CatalogoApi catalogo;
  private final EstructuraApi estructura;
  private final Personas personas;
  private final Almacenamiento almacenamiento;

  Captura(
      ServicioAlmacenes almacenes,
      RepositorioMovimientos movimientos,
      RepositorioPeriodos periodos,
      CatalogoApi catalogo,
      EstructuraApi estructura,
      Personas personas,
      Almacenamiento almacenamiento) {
    this.almacenes = almacenes;
    this.movimientos = movimientos;
    this.periodos = periodos;
    this.catalogo = catalogo;
    this.estructura = estructura;
    this.personas = personas;
    this.almacenamiento = almacenamiento;
  }

  Preparado preparar(TipoMovimiento tipo, DatosMovimiento d) {
    FilaAlmacen almacen = almacenes.activo(d.almacenId());
    validarFecha(d);

    Long destino = null;
    if (tipo == TipoMovimiento.TRASPASO) {
      if (d.almacenDestinoId() == null) {
        throw ErrorNegocio.invalido("FALTA_ALMACEN_DESTINO", "Elige el almacén destino");
      }
      destino = almacenes.activo(d.almacenDestinoId()).id();
      if (destino == almacen.id()) {
        throw ErrorNegocio.invalido("TRASPASO_MISMO_ALMACEN", "El destino es el mismo almacén");
      }
    }

    ProveedorInfo proveedor = null;
    if (tipo == TipoMovimiento.ENTRADA || tipo == TipoMovimiento.DEVOLUCION_PROVEEDOR) {
      if (d.proveedorId() == null) {
        throw ErrorNegocio.invalido("FALTA_PROVEEDOR", "Elige el proveedor");
      }
      proveedor =
          catalogo
              .proveedor(d.proveedorId())
              .orElseThrow(
                  () -> ErrorNegocio.invalido("PROVEEDOR_NO_ENCONTRADO", "No existe el proveedor"));
      if (tipo == TipoMovimiento.ENTRADA && !proveedor.activo()) {
        throw ErrorNegocio.conflicto("PROVEEDOR_INACTIVO", "El proveedor está desactivado");
      }
    }

    if (d.solicitanteId() != null) {
      var persona = personas.nombres(List.of(d.solicitanteId()));
      if (persona.isEmpty() || !persona.getFirst().activa()) {
        throw ErrorNegocio.invalido(
            "PERSONA_NO_ENCONTRADA", "No existe la persona o está inactiva");
      }
    }

    String motivo = Textos.limpio(d.motivo());
    Long archivo = null;
    if (tipo == TipoMovimiento.MERMA) {
      if (motivo == null) {
        throw ErrorNegocio.invalido("FALTA_MOTIVO", "Escribe qué le pasó al material");
      }
      if (d.archivoId() == null || !almacenamiento.existe(d.archivoId())) {
        throw ErrorNegocio.invalido("FALTA_FOTO", "Sube la foto del material dañado");
      }
      archivo = d.archivoId();
    }

    FilaMovimiento origen = null;
    if (tipo == TipoMovimiento.AJUSTE_COSTO) {
      if (d.movimientoOrigenId() == null) {
        throw ErrorNegocio.invalido("FALTA_RECEPCION", "Elige la recepción a corregir");
      }
      origen =
          movimientos
              .movimiento(empresa(), d.movimientoOrigenId())
              .filter(m -> m.tipo().equals("entrada") && m.estado().equals("aplicado"))
              .orElseThrow(
                  () ->
                      ErrorNegocio.invalido(
                          "RECEPCION_NO_APLICADA",
                          "Solo se corrige el costo de una recepción aplicada"));
      if (origen.almacenId() != almacen.id()) {
        throw ErrorNegocio.invalido("RECEPCION_OTRO_ALMACEN", "La recepción es de otro almacén");
      }
    }

    List<NuevaLinea> lineas = lineas(tipo, d, almacen, proveedor, origen);

    Cabecera cabecera =
        new Cabecera(
            tipo.clave(),
            d.fecha(),
            almacen.id(),
            destino,
            proveedor == null ? null : proveedor.id(),
            tipo == TipoMovimiento.ENTRADA || tipo == TipoMovimiento.AJUSTE_COSTO
                ? Textos.limpio(d.folioFactura())
                : null,
            tipo == TipoMovimiento.ENTRADA && d.flete() != null && d.flete().signum() > 0
                ? d.flete()
                : null,
            d.solicitanteId(),
            origen == null ? null : origen.id(),
            null,
            null,
            archivo,
            motivo,
            Textos.limpio(d.observaciones()));
    return new Preparado(cabecera, lineas);
  }

  private void validarFecha(DatosMovimiento d) {
    if (d.fecha() == null) {
      throw ErrorNegocio.invalido("FALTA_FECHA", "Escribe la fecha del documento");
    }
    if (d.fecha().isAfter(hoy())) {
      throw ErrorNegocio.invalido("FECHA_FUTURA", "La fecha no puede ser posterior a hoy");
    }
    if (periodos.cerrado(empresa(), d.fecha())) {
      throw ErrorNegocio.conflicto("PERIODO_CERRADO", "Ese mes ya está cerrado");
    }
  }

  // ------------------------------------------------------------------------------ Líneas

  private List<NuevaLinea> lineas(
      TipoMovimiento tipo,
      DatosMovimiento d,
      FilaAlmacen almacen,
      ProveedorInfo proveedor,
      FilaMovimiento origen) {
    if (d.lineas() == null || d.lineas().isEmpty()) {
      throw ErrorNegocio.invalido("SIN_LINEAS", "Agrega al menos un material");
    }
    Map<Long, MaterialInfo> materiales =
        catalogo
            .materiales(
                d.lineas().stream().map(LineaCaptura::materialId).filter(Objects::nonNull).toList())
            .stream()
            .collect(Collectors.toMap(MaterialInfo::id, m -> m));
    Map<Long, Boolean> fraccionable =
        catalogo.unidades().stream()
            .collect(
                Collectors.toMap(CatalogoApi.UnidadInfo::id, CatalogoApi.UnidadInfo::fraccionable));
    Destinos destinos = destinos(tipo, d);

    List<NuevaLinea> resultado = new ArrayList<>();
    Set<Long> origenesUsados = new HashSet<>();
    int renglon = 0;
    for (LineaCaptura l : d.lineas()) {
      renglon++;
      String donde = "Renglón " + renglon + ": ";
      LineaConDocumento base = null;
      if (tipo.requiereLineaOrigen()) {
        base = lineaOrigen(tipo, l, almacen, proveedor, origen, donde);
        if (tipo == TipoMovimiento.AJUSTE_COSTO && !origenesUsados.add(base.id())) {
          throw ErrorNegocio.invalido("ORIGEN_REPETIDO", donde + "esa línea ya está en el ajuste");
        }
      }
      long materialId = base != null ? base.materialId() : exigirMaterial(l, donde);
      if (l.materialId() != null && l.materialId() != materialId) {
        throw ErrorNegocio.invalido(
            "LINEA_ORIGEN_OTRO_MATERIAL", donde + "el material no es el de la línea de origen");
      }
      MaterialInfo material = materiales.get(materialId);
      if (material == null) {
        material =
            catalogo
                .material(materialId)
                .orElseThrow(
                    () ->
                        ErrorNegocio.invalido(
                            "MATERIAL_NO_ENCONTRADO", donde + "no existe el material"));
      }
      boolean entraNuevo =
          tipo == TipoMovimiento.ENTRADA
              || tipo == TipoMovimiento.SALIDA
              || tipo == TipoMovimiento.TRASPASO
              || tipo == TipoMovimiento.MERMA;
      if (entraNuevo && !material.activo()) {
        throw ErrorNegocio.conflicto(
            "MATERIAL_INACTIVO", donde + material.nombre() + " está desactivado");
      }

      // Cantidad en unidad de control.
      long unidadCaptura;
      BigDecimal cantidadCaptura;
      BigDecimal cantidad;
      if (tipo == TipoMovimiento.AJUSTE_COSTO) {
        unidadCaptura = material.unidadControlId();
        cantidad = base.cantidad();
        cantidadCaptura = cantidad;
      } else {
        if (l.cantidad() == null || l.cantidad().signum() <= 0) {
          throw ErrorNegocio.invalido(
              "CANTIDAD_INVALIDA", donde + "la cantidad debe ser mayor que cero");
        }
        unidadCaptura = l.unidadId() == null ? material.unidadControlId() : l.unidadId();
        cantidadCaptura = l.cantidad();
        cantidad =
            unidadCaptura == material.unidadControlId()
                ? l.cantidad().setScale(Valuacion.DECIMALES_CANTIDAD, RoundingMode.HALF_UP)
                : catalogo.aUnidadControl(materialId, unidadCaptura, l.cantidad());
        if (cantidad.signum() <= 0) {
          throw ErrorNegocio.invalido(
              "CANTIDAD_INVALIDA", donde + "la cantidad convertida es cero");
        }
        boolean puedeFraccion = fraccionable.getOrDefault(material.unidadControlId(), true);
        if (!puedeFraccion && cantidad.stripTrailingZeros().scale() > 0 && !tipo.cargaAObra()) {
          throw ErrorNegocio.invalido(
              "CANTIDAD_FRACCIONARIA",
              donde + material.nombre() + " se lleva en " + material.unidadControl() + " enteras");
        }
      }

      // Precio (recepción) y costo nuevo (ajuste).
      BigDecimal precio = null;
      BigDecimal descuento = null;
      BigDecimal tasaIva = null;
      BigDecimal costoNuevo = null;
      if (tipo == TipoMovimiento.ENTRADA) {
        if (l.precio() == null) {
          throw ErrorNegocio.invalido("FALTA_PRECIO", donde + "escribe el precio sin IVA");
        }
        precio = l.precio();
        descuento = l.descuento() != null && l.descuento().signum() > 0 ? l.descuento() : null;
        if (descuento != null && descuento.compareTo(precio.multiply(cantidadCaptura)) > 0) {
          throw ErrorNegocio.invalido(
              "DESCUENTO_MAYOR", donde + "el descuento es mayor que el importe del renglón");
        }
        tasaIva = l.tasaIva() != null ? l.tasaIva() : proveedor.tasaIva();
      } else if (tipo == TipoMovimiento.AJUSTE_COSTO) {
        if (l.costoNuevo() == null) {
          throw ErrorNegocio.invalido("FALTA_COSTO", donde + "escribe el costo correcto");
        }
        costoNuevo = l.costoNuevo();
      }

      // Destino en obra.
      if (tipo == TipoMovimiento.SALIDA || tipo == TipoMovimiento.REASIGNACION) {
        List<Long> unidades =
            l.unidadObraIds() == null
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(l.unidadObraIds()));
        if (unidades.isEmpty()) {
          throw ErrorNegocio.invalido("FALTA_LOTE", donde + "elige el lote");
        }
        if (tipo == TipoMovimiento.REASIGNACION && unidades.size() != 1) {
          throw ErrorNegocio.invalido(
              "REASIGNACION_UN_LOTE", donde + "una reasignación va a un solo lote");
        }
        destinos.validar(l, unidades, almacen, donde);
        if (tipo == TipoMovimiento.REASIGNACION
            && Objects.equals(l.contratoId(), base.contratoId())
            && Objects.equals(unidades.getFirst(), base.unidadObraId())
            && Objects.equals(l.paqueteId(), base.paqueteId())
            && Objects.equals(l.clasificacionId(), base.clasificacionId())) {
          throw ErrorNegocio.invalido(
              "REASIGNACION_MISMO_DESTINO", donde + "el destino es el mismo que el de origen");
        }
        boolean puedeFraccion = fraccionable.getOrDefault(material.unidadControlId(), true);
        List<BigDecimal> partes =
            Reparto.repartir(
                cantidad, unidades.size(), puedeFraccion ? Valuacion.DECIMALES_CANTIDAD : 0);
        if (partes.stream().anyMatch(p -> p.signum() <= 0)) {
          throw ErrorNegocio.invalido(
              "REPARTO_INSUFICIENTE",
              donde
                  + "no alcanza para "
                  + unidades.size()
                  + " lotes en "
                  + material.unidadControl()
                  + " enteras");
        }
        if (!puedeFraccion && cantidad.stripTrailingZeros().scale() > 0) {
          throw ErrorNegocio.invalido(
              "CANTIDAD_FRACCIONARIA",
              donde + material.nombre() + " se lleva en " + material.unidadControl() + " enteras");
        }
        List<BigDecimal> capturas =
            unidades.size() == 1
                ? List.of(cantidadCaptura)
                : Reparto.prorratear(cantidadCaptura, partes, Valuacion.DECIMALES_CANTIDAD);
        boolean capturaEnControl = capturas.stream().anyMatch(c -> c.signum() <= 0);
        for (int i = 0; i < unidades.size(); i++) {
          resultado.add(
              new NuevaLinea(
                  renglon,
                  materialId,
                  partes.get(i),
                  capturaEnControl ? partes.get(i) : capturas.get(i),
                  capturaEnControl ? material.unidadControlId() : unidadCaptura,
                  null,
                  null,
                  null,
                  null,
                  l.contratoId(),
                  unidades.get(i),
                  l.paqueteId(),
                  l.clasificacionId(),
                  base == null ? null : base.id()));
        }
      } else if (tipo == TipoMovimiento.DEVOLUCION) {
        resultado.add(
            new NuevaLinea(
                renglon,
                materialId,
                cantidad,
                cantidadCaptura,
                unidadCaptura,
                null,
                null,
                null,
                null,
                base.contratoId(),
                base.unidadObraId(),
                base.paqueteId(),
                base.clasificacionId(),
                base.id()));
      } else {
        resultado.add(
            new NuevaLinea(
                renglon,
                materialId,
                cantidad,
                cantidadCaptura,
                unidadCaptura,
                precio,
                descuento,
                tasaIva,
                costoNuevo,
                null,
                null,
                null,
                null,
                base == null ? null : base.id()));
      }
      if (resultado.size() > LINEAS_MAXIMAS) {
        throw ErrorNegocio.invalido(
            "DEMASIADAS_LINEAS", "El documento pasa de " + LINEAS_MAXIMAS + " líneas");
      }
    }
    return resultado;
  }

  private static long exigirMaterial(LineaCaptura l, String donde) {
    if (l.materialId() == null) {
      throw ErrorNegocio.invalido("FALTA_MATERIAL", donde + "elige el material");
    }
    return l.materialId();
  }

  /** La línea de origen de una devolución, devolución a proveedor, reasignación o ajuste. */
  private LineaConDocumento lineaOrigen(
      TipoMovimiento tipo,
      LineaCaptura l,
      FilaAlmacen almacen,
      ProveedorInfo proveedor,
      FilaMovimiento origen,
      String donde) {
    if (l.lineaOrigenId() == null) {
      throw ErrorNegocio.invalido("FALTA_LINEA_ORIGEN", donde + "elige la línea de origen");
    }
    LineaConDocumento base =
        movimientos
            .lineaConDocumento(empresa(), l.lineaOrigenId(), false)
            .filter(b -> b.estado().equals("aplicado"))
            .orElseThrow(
                () ->
                    ErrorNegocio.invalido(
                        "LINEA_ORIGEN_INVALIDA",
                        donde + "la línea de origen no existe o no está aplicada"));
    switch (tipo) {
      case DEVOLUCION, REASIGNACION -> {
        if (!base.tipo().equals("salida") && !base.tipo().equals("reasignacion")) {
          throw ErrorNegocio.invalido(
              "LINEA_ORIGEN_INVALIDA", donde + "se devuelve o reasigna una línea de vale");
        }
        Restante restante = movimientos.restante(empresa(), base.id());
        if (restante.cantidad().signum() <= 0) {
          throw ErrorNegocio.conflicto(
              "SIN_RESTANTE",
              donde + "esa línea de " + base.folio() + " ya se devolvió o reasignó completa");
        }
      }
      case DEVOLUCION_PROVEEDOR -> {
        if (!base.tipo().equals("entrada")) {
          throw ErrorNegocio.invalido(
              "LINEA_ORIGEN_INVALIDA", donde + "se devuelve una línea de recepción");
        }
        if (base.almacenId() != almacen.id()) {
          throw ErrorNegocio.invalido(
              "RECEPCION_OTRO_ALMACEN", donde + "la recepción es de otro almacén");
        }
        if (!Objects.equals(base.proveedorId(), proveedor.id())) {
          throw ErrorNegocio.invalido(
              "RECEPCION_OTRO_PROVEEDOR", donde + "la recepción es de otro proveedor");
        }
      }
      case AJUSTE_COSTO -> {
        if (base.movimientoId() != origen.id()) {
          throw ErrorNegocio.invalido(
              "LINEA_ORIGEN_INVALIDA", donde + "la línea no es de la recepción " + origen.folio());
        }
      }
      default -> throw new IllegalStateException(tipo.clave());
    }
    return base;
  }

  // ------------------------------------------------------------------------------ Destinos

  private Destinos destinos(TipoMovimiento tipo, DatosMovimiento d) {
    if (tipo != TipoMovimiento.SALIDA && tipo != TipoMovimiento.REASIGNACION) {
      return new Destinos(Map.of(), Map.of(), Map.of(), Set.of());
    }
    Set<Long> contratoIds = new HashSet<>();
    Set<Long> unidadIds = new HashSet<>();
    Set<Long> paqueteIds = new HashSet<>();
    for (LineaCaptura l : d.lineas()) {
      if (l.contratoId() != null) {
        contratoIds.add(l.contratoId());
      }
      if (l.unidadObraIds() != null) {
        l.unidadObraIds().stream().filter(Objects::nonNull).forEach(unidadIds::add);
      }
      if (l.paqueteId() != null) {
        paqueteIds.add(l.paqueteId());
      }
    }
    return new Destinos(
        estructura.contratos(contratoIds).stream()
            .collect(Collectors.toMap(ContratoInfo::id, c -> c)),
        estructura.unidades(unidadIds).stream().collect(Collectors.toMap(UnidadInfo::id, u -> u)),
        estructura.paquetes(paqueteIds).stream().collect(Collectors.toMap(PaqueteInfo::id, p -> p)),
        catalogo.clasificaciones().stream().map(c -> c.id()).collect(Collectors.toSet()));
  }

  /** Contratos, lotes, paquetes y clasificaciones de las líneas, ya leídos. */
  private record Destinos(
      Map<Long, ContratoInfo> contratos,
      Map<Long, UnidadInfo> unidades,
      Map<Long, PaqueteInfo> paquetes,
      Set<Integer> clasificaciones) {

    /**
     * Regla 4: contrato activo, lotes de ese contrato y del frente del almacén, paquete y razón.
     */
    void validar(LineaCaptura l, List<Long> unidadIds, FilaAlmacen almacen, String donde) {
      if (l.contratoId() == null) {
        throw ErrorNegocio.invalido("FALTA_CONTRATO", donde + "toda salida lleva contrato");
      }
      ContratoInfo contrato = contratos.get(l.contratoId());
      if (contrato == null) {
        throw ErrorNegocio.invalido("CONTRATO_NO_ENCONTRADO", donde + "no existe el contrato");
      }
      if (!contrato.activo()) {
        throw ErrorNegocio.conflicto(
            "CONTRATO_NO_ACTIVO", donde + "el contrato " + contrato.codigo() + " no está activo");
      }
      for (Long id : unidadIds) {
        UnidadInfo unidad = unidades.get(id);
        if (unidad == null || unidad.contratoId() != contrato.id()) {
          throw ErrorNegocio.invalido(
              "LOTE_DE_OTRO_CONTRATO", donde + "el lote no es del contrato " + contrato.codigo());
        }
        if (!unidad.activa()) {
          throw ErrorNegocio.conflicto(
              "LOTE_INACTIVO", donde + "el lote " + unidad.clave() + " está desactivado");
        }
        if (unidad.frenteId() != null && unidad.frenteId() != almacen.frenteId()) {
          throw ErrorNegocio.invalido(
              "LOTE_DE_OTRO_FRENTE",
              donde
                  + "el lote "
                  + unidad.clave()
                  + " es de otro frente; usa un traspaso al almacén de ese frente");
        }
      }
      if (l.paqueteId() == null) {
        throw ErrorNegocio.invalido("FALTA_PAQUETE", donde + "elige el paquete");
      }
      PaqueteInfo paquete = paquetes.get(l.paqueteId());
      if (paquete == null || !paquete.activo()) {
        throw ErrorNegocio.invalido(
            "PAQUETE_NO_ENCONTRADO", donde + "no existe el paquete o está inactivo");
      }
      if (l.clasificacionId() == null || !clasificaciones.contains(l.clasificacionId())) {
        throw ErrorNegocio.invalido(
            "FALTA_CLASIFICACION", donde + "elige la razón (Programa, Garantía…)");
      }
    }
  }
}

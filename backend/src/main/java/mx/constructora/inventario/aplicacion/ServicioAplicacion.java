package mx.constructora.inventario.aplicacion;

import static mx.constructora.inventario.aplicacion.Sesion.CAPTURAR;
import static mx.constructora.inventario.aplicacion.Sesion.empresa;
import static mx.constructora.inventario.aplicacion.Sesion.hoy;
import static mx.constructora.inventario.aplicacion.Sesion.usuario;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import mx.constructora.catalogo.CatalogoApi.MaterialInfo;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Folios;
import mx.constructora.compartido.Textos;
import mx.constructora.estructura.EstructuraApi;
import mx.constructora.estructura.EstructuraApi.ContratoInfo;
import mx.constructora.estructura.EstructuraApi.UnidadInfo;
import mx.constructora.inventario.aplicacion.ServicioMovimientos.Movimiento;
import mx.constructora.inventario.dominio.AjusteCosto;
import mx.constructora.inventario.dominio.CostoRecepcion;
import mx.constructora.inventario.dominio.ExistenciaInsuficiente;
import mx.constructora.inventario.dominio.Reparto;
import mx.constructora.inventario.dominio.TipoMovimiento;
import mx.constructora.inventario.dominio.Valuacion;
import mx.constructora.inventario.dominio.Valuacion.Paso;
import mx.constructora.inventario.infraestructura.RepositorioAlmacenes;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.Cabecera;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.Consumo;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.FilaLinea;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.FilaMovimiento;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.LineaConDocumento;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.NuevaLinea;
import mx.constructora.inventario.infraestructura.RepositorioMovimientos.Restante;
import mx.constructora.inventario.infraestructura.RepositorioPeriodos;
import mx.constructora.inventario.infraestructura.RepositorioReservas;
import mx.constructora.inventario.infraestructura.RepositorioReservas.FilaReserva;
import mx.constructora.inventario.infraestructura.RepositorioSaldos;
import mx.constructora.inventario.infraestructura.RepositorioSaldos.FilaSaldo;
import mx.constructora.inventario.infraestructura.RepositorioSaldos.NuevoCargo;
import mx.constructora.inventario.infraestructura.RepositorioSaldos.NuevoKardex;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplicar un documento: lo único que mueve existencia, valor y costo de obra. Todo en una
 * transacción:
 *
 * <ol>
 *   <li>Se bloquea el documento ({@code FOR UPDATE}) y se revisa que esté listo para aplicar.
 *   <li>Se bloquean los saldos que toca, en orden de (almacén, material): dos vales simultáneos del
 *       mismo material esperan su turno y nunca se esperan en cruz.
 *   <li>Cada línea congela su costo (regla 3) y deja su renglón de kardex y, si va a obra, su
 *       cargo. La base rechaza cualquier existencia negativa (regla 2) aunque el código fallara.
 * </ol>
 *
 * <p>Lo reservado para requisiciones no está disponible para otros vales. Un vale que surte una
 * requisición usa su reserva y la consume; un ajuste de conteo que no alcanza recorta las reservas
 * más recientes del material (el conteo físico es el que vale). Las reservas se bloquean siempre
 * después de los saldos (ADR-012).
 */
@Service
public class ServicioAplicacion {

  private final RepositorioMovimientos movimientos;
  private final RepositorioSaldos saldos;
  private final RepositorioReservas reservas;
  private final RepositorioAlmacenes almacenes;
  private final RepositorioPeriodos periodos;
  private final ServicioMovimientos servicioMovimientos;
  private final Nombres nombres;
  private final EstructuraApi estructura;
  private final Folios folios;

  public ServicioAplicacion(
      RepositorioMovimientos movimientos,
      RepositorioSaldos saldos,
      RepositorioReservas reservas,
      RepositorioAlmacenes almacenes,
      RepositorioPeriodos periodos,
      ServicioMovimientos servicioMovimientos,
      Nombres nombres,
      EstructuraApi estructura,
      Folios folios) {
    this.movimientos = movimientos;
    this.saldos = saldos;
    this.reservas = reservas;
    this.almacenes = almacenes;
    this.periodos = periodos;
    this.servicioMovimientos = servicioMovimientos;
    this.nombres = nombres;
    this.estructura = estructura;
    this.folios = folios;
  }

  /**
   * Aplica el documento.
   *
   * @throws ErrorNegocio {@code EXISTENCIA_INSUFICIENTE} (409) si una salida no alcanza
   */
  @PreAuthorize(CAPTURAR)
  @Transactional
  public Movimiento aplicar(long id) {
    FilaMovimiento m = servicioMovimientos.bloquear(id);
    aplicarBloqueado(m, Map.of());
    return servicioMovimientos.movimiento(id);
  }

  /**
   * Cancela el documento. En borrador, pendiente o autorizado solo cambia de estado. Un vale o una
   * recepción ya aplicados se cancelan con el movimiento inverso (devolución de lo que queda en
   * obra, o devolución al proveedor de lo que queda de la recepción), que se aplica en el mismo
   * paso. Los demás documentos aplicados se corrigen con su movimiento inverso a mano.
   */
  @PreAuthorize(CAPTURAR)
  @Transactional
  public Movimiento cancelar(long id, String motivo) {
    String texto = Textos.limpio(motivo);
    if (texto == null) {
      throw ErrorNegocio.invalido("FALTA_MOTIVO", "Escribe por qué se cancela");
    }
    FilaMovimiento m = servicioMovimientos.bloquear(id);
    TipoMovimiento tipo = ServicioMovimientos.tipo(m);
    switch (m.estado()) {
      case "borrador", "pendiente", "autorizado" -> movimientos.cancelar(empresa(), id, texto);
      case "aplicado" -> {
        if (tipo == TipoMovimiento.SALIDA) {
          inverso(m, TipoMovimiento.DEVOLUCION, texto);
        } else if (tipo == TipoMovimiento.ENTRADA) {
          inverso(m, TipoMovimiento.DEVOLUCION_PROVEEDOR, texto);
        } else {
          throw ErrorNegocio.conflicto(
              "NO_SE_CANCELA",
              "Un documento aplicado de este tipo se corrige con su movimiento inverso");
        }
        movimientos.cancelar(empresa(), id, texto);
      }
      default ->
          throw ErrorNegocio.conflicto(
              "YA_CANCELADO", "El documento " + m.folio() + " ya está cancelado");
    }
    return servicioMovimientos.movimiento(id);
  }

  /** Crea y aplica el inverso de un vale o una recepción por lo que les queda. */
  private void inverso(FilaMovimiento m, TipoMovimiento tipo, String motivo) {
    List<NuevaLinea> lineas = new ArrayList<>();
    int renglon = 0;
    for (FilaLinea l : movimientos.lineas(empresa(), m.id())) {
      BigDecimal queda =
          tipo == TipoMovimiento.DEVOLUCION
              ? movimientos.restante(empresa(), l.id()).cantidad()
              : l.cantidad().subtract(movimientos.devueltoAProveedor(empresa(), l.id()));
      if (queda.signum() <= 0) {
        continue;
      }
      renglon++;
      lineas.add(
          new NuevaLinea(
              renglon,
              l.materialId(),
              queda,
              queda,
              unidadControl(l.materialId()),
              null,
              null,
              null,
              null,
              tipo == TipoMovimiento.DEVOLUCION ? l.contratoId() : null,
              tipo == TipoMovimiento.DEVOLUCION ? l.unidadObraId() : null,
              tipo == TipoMovimiento.DEVOLUCION ? l.paqueteId() : null,
              tipo == TipoMovimiento.DEVOLUCION ? l.clasificacionId() : null,
              l.id()));
    }
    if (lineas.isEmpty()) {
      return;
    }
    Cabecera cabecera =
        new Cabecera(
            tipo.clave(),
            hoy(),
            m.almacenId(),
            null,
            tipo == TipoMovimiento.DEVOLUCION_PROVEEDOR ? m.proveedorId() : null,
            null,
            null,
            m.solicitanteId(),
            m.id(),
            null,
            null,
            null,
            null,
            "Cancelación de " + m.folio() + ": " + motivo);
    long id =
        movimientos.insertar(
            empresa(), folios.siguiente(tipo.prefijoFolio()), cabecera, usuario(), null);
    for (NuevaLinea linea : lineas) {
      movimientos.insertarLinea(empresa(), id, tipo.clave(), linea);
    }
    aplicarBloqueado(movimientos.bloquear(empresa(), id).orElseThrow(), Map.of());
  }

  private long unidadControl(long material) {
    return nombres.materiales(List.of(material)).get(material).unidadControlId();
  }

  /**
   * Aplica un documento ya bloqueado. Lo usan {@link #aplicar}, la cancelación, el cierre del
   * conteo físico (que pasa los costos de los sobrantes en {@code costosSobrante}) y la apertura
   * firmada (que pasa los costos firmados).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void aplicarBloqueado(FilaMovimiento m, Map<Long, BigDecimal> costosSobrante) {
    TipoMovimiento tipo = ServicioMovimientos.tipo(m);
    String listo = tipo.requiereAutorizacion() ? "autorizado" : "borrador";
    if (!m.estado().equals(listo)) {
      throw ErrorNegocio.conflicto(
          "MOVIMIENTO_NO_APLICABLE",
          tipo.requiereAutorizacion() && !m.estado().equals("aplicado")
              ? "El documento " + m.folio() + " todavía no está autorizado"
              : "El documento " + m.folio() + " ya está " + m.estado());
    }
    if (m.fecha().isAfter(hoy())) {
      throw ErrorNegocio.invalido("FECHA_FUTURA", "La fecha no puede ser posterior a hoy");
    }
    if (periodos.cerrado(empresa(), m.fecha())) {
      throw ErrorNegocio.conflicto("PERIODO_CERRADO", "El mes del documento ya está cerrado");
    }
    for (Long almacen : new TreeSet<>(listaSinNulos(m.almacenId(), m.almacenDestinoId()))) {
      if (tipo != TipoMovimiento.REASIGNACION && almacenes.enConteo(empresa(), almacen)) {
        throw ErrorNegocio.conflicto(
            "ALMACEN_EN_CONTEO", "El almacén tiene un conteo físico abierto: ciérralo o cancélalo");
      }
    }
    List<FilaLinea> lineas = movimientos.lineas(empresa(), m.id());
    if (lineas.isEmpty()) {
      throw ErrorNegocio.invalido("SIN_LINEAS", "El documento no tiene materiales");
    }
    Corrida c = new Corrida(m, lineas);
    switch (tipo) {
      case ENTRADA -> entrada(c);
      case SALIDA, MERMA, AJUSTE_SALIDA -> {
        if (tipo == TipoMovimiento.SALIDA) {
          revisarDestinos(lineas);
        }
        c.bloquear(m.almacenId());
        List<Apartado> apartados =
            switch (tipo) {
              case SALIDA -> reservasQueSurte(m.almacenId(), lineas);
              case AJUSTE_SALIDA -> recortarReservas(c, m.almacenId(), lineas);
              default -> List.of();
            };
        Map<Long, BigDecimal> porMaterial = new HashMap<>();
        apartados.forEach(
            a -> porMaterial.merge(a.reserva().materialId(), a.usar(), BigDecimal::add));
        salidaAlPromedio(
            c,
            m.almacenId(),
            lineas,
            porMaterial,
            tipo == TipoMovimiento.SALIDA ? (l, imp) -> cargarVale(m, l, imp) : null);
        if (tipo == TipoMovimiento.SALIDA) {
          // Antes de guardar el saldo: lo reservado baja primero y la existencia después.
          for (Apartado a : apartados) {
            reservas.fijar(empresa(), a.reserva().id(), a.reserva().cantidad().subtract(a.usar()));
          }
        }
      }
      case AJUSTE_ENTRADA, APERTURA -> sobrante(c, costosSobrante);
      case DEVOLUCION -> devolucion(c);
      case DEVOLUCION_PROVEEDOR -> devolucionProveedor(c);
      case TRASPASO -> traspaso(c);
      case REASIGNACION -> reasignacion(c);
      case AJUSTE_COSTO -> ajusteCosto(c);
    }
    c.guardarSaldos();
    movimientos.aplicar(empresa(), m.id(), usuario());
  }

  // ------------------------------------------------------------------------------ Por tipo

  /** Recepción: costo sin IVA con descuento y flete prorrateado; recalcula el promedio. */
  private void entrada(Corrida c) {
    List<FilaLinea> lineas = c.lineas;
    List<CostoRecepcion.Costo> costos =
        CostoRecepcion.calcular(
            lineas.stream()
                .map(
                    l ->
                        new CostoRecepcion.Renglon(
                            l.cantidad(), l.cantidadCaptura(), l.precioCaptura(), l.descuento()))
                .toList(),
            c.m.flete());
    c.bloquear(c.m.almacenId());
    for (int i : ordenPorMaterial(lineas)) {
      FilaLinea l = lineas.get(i);
      Llave k = new Llave(c.m.almacenId(), l.materialId());
      CostoRecepcion.Costo costo = costos.get(i);
      Paso p =
          c.valuacion(k).entrarConImporte(l.cantidad(), costo.importe(), costo.costoUnitario());
      c.registrar(k, l, p);
      movimientos.congelarCosto(empresa(), l.id(), costo.costoUnitario(), costo.importe());
    }
  }

  /** Parte de una reserva que el documento puede usar. */
  private record Apartado(FilaReserva reserva, BigDecimal usar) {}

  /**
   * Reservas de los renglones de requisición que surte el vale, bloqueadas: cada una aporta hasta
   * lo que el vale lleva para su renglón.
   */
  private List<Apartado> reservasQueSurte(long almacen, List<FilaLinea> lineas) {
    Map<Long, BigDecimal> porRenglon = new HashMap<>();
    for (FilaLinea l : lineas) {
      if (l.requisicionLineaId() != null) {
        porRenglon.merge(l.requisicionLineaId(), l.cantidad(), BigDecimal::add);
      }
    }
    List<Apartado> apartados = new ArrayList<>();
    for (FilaReserva r : reservas.deRenglones(empresa(), porRenglon.keySet(), true)) {
      BigDecimal usar = r.cantidad().min(porRenglon.get(r.requisicionLineaId()));
      if (r.almacenId() == almacen && usar.signum() > 0) {
        apartados.add(new Apartado(r, usar));
      }
    }
    return apartados;
  }

  /**
   * Ajuste de conteo que no alcanza por lo reservado: libera las reservas más recientes del
   * material hasta cubrirlo. La requisición vuelve a tener ese faltante.
   */
  private List<Apartado> recortarReservas(Corrida c, long almacen, List<FilaLinea> lineas) {
    Map<Long, BigDecimal> pedido = new java.util.TreeMap<>();
    lineas.forEach(l -> pedido.merge(l.materialId(), l.cantidad(), BigDecimal::add));
    List<Apartado> liberados = new ArrayList<>();
    for (var e : pedido.entrySet()) {
      BigDecimal falta = e.getValue().subtract(c.disponible(new Llave(almacen, e.getKey())));
      if (falta.signum() <= 0) {
        continue;
      }
      for (FilaReserva r : reservas.vigentesBloqueadas(empresa(), almacen, e.getKey())) {
        if (falta.signum() <= 0) {
          break;
        }
        BigDecimal quitar = r.cantidad().min(falta);
        reservas.fijar(empresa(), r.id(), r.cantidad().subtract(quitar));
        liberados.add(new Apartado(r, quitar));
        falta = falta.subtract(quitar);
      }
    }
    return liberados;
  }

  /**
   * Salida al costo promedio. Las líneas de un mismo renglón (un bote repartido entre lotes) salen
   * juntas: se calcula el importe del total y se reparte, para que la suma cuadre exacta. {@code
   * apartado}: lo reservado que este documento puede usar, por material.
   */
  private void salidaAlPromedio(
      Corrida c,
      long almacen,
      List<FilaLinea> lineas,
      Map<Long, BigDecimal> apartado,
      BiConsumer<FilaLinea, BigDecimal> despues) {
    // Primero se revisa que alcance todo el documento, material por material.
    Map<Long, BigDecimal> porMaterial = new LinkedHashMap<>();
    lineas.forEach(l -> porMaterial.merge(l.materialId(), l.cantidad(), BigDecimal::add));
    for (var pedido : porMaterial.entrySet()) {
      Llave k = new Llave(almacen, pedido.getKey());
      BigDecimal disponible =
          c.disponible(k).add(apartado.getOrDefault(pedido.getKey(), BigDecimal.ZERO));
      if (pedido.getValue().compareTo(disponible) > 0) {
        throw insuficiente(pedido.getKey(), pedido.getValue(), disponible);
      }
    }
    Map<String, List<FilaLinea>> grupos = new LinkedHashMap<>();
    lineas.stream()
        .sorted(
            Comparator.comparingLong(FilaLinea::materialId)
                .thenComparingInt(FilaLinea::renglon)
                .thenComparingLong(FilaLinea::id))
        .forEach(
            l ->
                grupos
                    .computeIfAbsent(l.materialId() + "/" + l.renglon(), x -> new ArrayList<>())
                    .add(l));
    for (List<FilaLinea> grupo : grupos.values()) {
      Llave k = new Llave(almacen, grupo.getFirst().materialId());
      Valuacion antes = c.valuacion(k);
      BigDecimal total =
          grupo.stream().map(FilaLinea::cantidad).reduce(BigDecimal.ZERO, BigDecimal::add);
      Paso p;
      try {
        p = antes.salir(total);
      } catch (ExistenciaInsuficiente e) {
        throw insuficiente(k.material(), total, e.disponible());
      }
      List<BigDecimal> importes =
          grupo.size() == 1
              ? List.of(p.importe().negate())
              : Reparto.prorratear(
                  p.importe().negate(),
                  grupo.stream().map(FilaLinea::cantidad).toList(),
                  Valuacion.DECIMALES_IMPORTE);
      BigDecimal existencia = antes.existencia();
      BigDecimal valor = antes.valor();
      for (int i = 0; i < grupo.size(); i++) {
        FilaLinea l = grupo.get(i);
        BigDecimal importe = importes.get(i);
        existencia = existencia.subtract(l.cantidad());
        valor = valor.subtract(importe);
        c.kardex(
            k,
            l,
            l.cantidad().negate(),
            p.costoUnitario(),
            importe.negate(),
            existencia,
            valor,
            p.despues().costoPromedio());
        movimientos.congelarCosto(empresa(), l.id(), p.costoUnitario(), importe);
        if (despues != null) {
          despues.accept(l, importe);
        }
      }
      c.poner(k, p.despues());
    }
  }

  /** Cargo del vale a su lote: base de lo que después se devuelva o reasigne. */
  private void cargarVale(FilaMovimiento m, FilaLinea l, BigDecimal importe) {
    saldos.registrarCargo(
        empresa(),
        new NuevoCargo(
            l.movimientoId(),
            l.id(),
            l.id(),
            m.fecha(),
            l.materialId(),
            l.contratoId(),
            l.unidadObraId(),
            l.paqueteId(),
            l.clasificacionId(),
            l.cantidad(),
            importe));
  }

  /**
   * Sobrante de conteo o saldo de apertura: entra al costo indicado, o al promedio del almacén.
   */
  private void sobrante(Corrida c, Map<Long, BigDecimal> costos) {
    c.bloquear(c.m.almacenId());
    for (int i : ordenPorMaterial(c.lineas)) {
      FilaLinea l = c.lineas.get(i);
      Llave k = new Llave(c.m.almacenId(), l.materialId());
      Valuacion v = c.valuacion(k);
      BigDecimal costo = costos.getOrDefault(l.materialId(), v.costoPromedio());
      Paso p = v.entrar(l.cantidad(), costo);
      c.registrar(k, l, p);
      movimientos.congelarCosto(empresa(), l.id(), p.costoUnitario(), p.importe());
    }
  }

  /** Devolución de obra: entra al costo con que salió y resta del cargo del lote. */
  private void devolucion(Corrida c) {
    c.bloquear(c.m.almacenId());
    for (int i : ordenPorMaterial(c.lineas)) {
      FilaLinea l = c.lineas.get(i);
      LineaConDocumento base = baseEnObra(l);
      Retiro r = retirar(base, l.cantidad());
      Llave k = new Llave(c.m.almacenId(), l.materialId());
      Paso p = c.valuacion(k).entrarConImporte(l.cantidad(), r.importe(), r.costo());
      c.registrar(k, l, p);
      movimientos.congelarCosto(empresa(), l.id(), r.costo(), r.importe());
      saldos.registrarCargo(
          empresa(),
          new NuevoCargo(
              c.m.id(),
              l.id(),
              base.id(),
              c.m.fecha(),
              l.materialId(),
              base.contratoId(),
              base.unidadObraId(),
              base.paqueteId(),
              base.clasificacionId(),
              l.cantidad().negate(),
              r.importe().negate()));
    }
  }

  /** Reasignación: el costo pasa de un lote a otro, sin mover material. */
  private void reasignacion(Corrida c) {
    revisarDestinos(c.lineas);
    for (FilaLinea l : c.lineas) {
      LineaConDocumento base = baseEnObra(l);
      Retiro r = retirar(base, l.cantidad());
      movimientos.congelarCosto(empresa(), l.id(), r.costo(), r.importe());
      saldos.registrarCargo(
          empresa(),
          new NuevoCargo(
              c.m.id(),
              l.id(),
              base.id(),
              c.m.fecha(),
              l.materialId(),
              base.contratoId(),
              base.unidadObraId(),
              base.paqueteId(),
              base.clasificacionId(),
              l.cantidad().negate(),
              r.importe().negate()));
      saldos.registrarCargo(
          empresa(),
          new NuevoCargo(
              c.m.id(),
              l.id(),
              l.id(),
              c.m.fecha(),
              l.materialId(),
              l.contratoId(),
              l.unidadObraId(),
              l.paqueteId(),
              l.clasificacionId(),
              l.cantidad(),
              r.importe()));
    }
  }

  /** Devolución al proveedor: sale al costo con que entró (con sus ajustes de factura). */
  private void devolucionProveedor(Corrida c) {
    c.bloquear(c.m.almacenId());
    Map<Long, BigDecimal> enEsteDocumento = new HashMap<>();
    for (int i : ordenPorMaterial(c.lineas)) {
      FilaLinea l = c.lineas.get(i);
      LineaConDocumento base =
          movimientos
              .lineaConDocumento(empresa(), l.lineaOrigenId(), true)
              .filter(b -> b.tipo().equals("entrada") && b.estado().equals("aplicado"))
              .orElseThrow(
                  () ->
                      ErrorNegocio.conflicto(
                          "LINEA_ORIGEN_INVALIDA", "La recepción de origen no está aplicada"));
      BigDecimal yaDevuelto =
          movimientos
              .devueltoAProveedor(empresa(), base.id())
              .add(enEsteDocumento.getOrDefault(base.id(), BigDecimal.ZERO));
      if (yaDevuelto.add(l.cantidad()).compareTo(base.cantidad()) > 0) {
        throw ErrorNegocio.conflicto(
            "DEVOLUCION_EXCEDE",
            "Se devolvería más de lo recibido en "
                + base.folio()
                + " (quedan "
                + base.cantidad().subtract(yaDevuelto).stripTrailingZeros().toPlainString()
                + ")");
      }
      enEsteDocumento.merge(base.id(), l.cantidad(), BigDecimal::add);
      Llave k = new Llave(c.m.almacenId(), l.materialId());
      BigDecimal disponible = c.disponible(k);
      if (l.cantidad().compareTo(disponible) > 0) {
        throw insuficiente(l.materialId(), l.cantidad(), disponible);
      }
      BigDecimal costo =
          movimientos.ultimoCostoAjustado(empresa(), base.id()).orElse(base.costoUnitario());
      Paso p = c.valuacion(k).salirACosto(l.cantidad(), costo);
      c.registrar(k, l, p);
      movimientos.congelarCosto(empresa(), l.id(), p.costoUnitario(), p.importe().negate());
    }
  }

  /** Traspaso: sale del origen al promedio y entra al destino con el mismo valor. */
  private void traspaso(Corrida c) {
    long origen = c.m.almacenId();
    long destino = c.m.almacenDestinoId();
    c.bloquear(origen, destino);
    salidaAlPromedio(
        c,
        origen,
        c.lineas,
        Map.of(),
        (l, importe) -> {
          Llave k = new Llave(destino, l.materialId());
          BigDecimal costo =
              importe.divide(l.cantidad(), Valuacion.DECIMALES_COSTO, RoundingMode.HALF_EVEN);
          Paso p = c.valuacion(k).entrarConImporte(l.cantidad(), importe, costo);
          c.registrar(k, l, p);
        });
  }

  /**
   * Ajuste de costo por diferencia de factura (decisión de H4): lo que queda en el almacén ajusta
   * el valor; lo que ya salió se carga como ajuste a los lotes de los vales posteriores a la
   * recepción, en proporción a lo que cada uno conserva.
   */
  private void ajusteCosto(Corrida c) {
    c.bloquear(c.m.almacenId());
    for (int i : ordenPorMaterial(c.lineas)) {
      FilaLinea l = c.lineas.get(i);
      LineaConDocumento base =
          movimientos
              .lineaConDocumento(empresa(), l.lineaOrigenId(), true)
              .filter(b -> b.tipo().equals("entrada") && b.estado().equals("aplicado"))
              .orElseThrow(
                  () ->
                      ErrorNegocio.conflicto(
                          "LINEA_ORIGEN_INVALIDA", "La recepción de origen no está aplicada"));
      BigDecimal anterior =
          movimientos.ultimoCostoAjustado(empresa(), base.id()).orElse(base.costoUnitario());
      Llave k = new Llave(c.m.almacenId(), l.materialId());
      Valuacion v = c.valuacion(k);
      long desde = movimientos.kardexDeLinea(empresa(), base.id()).orElse(0L);
      List<Consumo> consumos =
          movimientos.consumosPosteriores(empresa(), k.almacen(), k.material(), desde);
      AjusteCosto.Resultado r;
      try {
        r =
            AjusteCosto.calcular(
                base.cantidad(),
                anterior,
                l.costoNuevo(),
                v.existencia(),
                v.valor(),
                consumos.stream()
                    .map(x -> new AjusteCosto.Consumo(x.lineaBaseId(), x.cantidad()))
                    .toList());
      } catch (AjusteCosto.SinDestino e) {
        throw ErrorNegocio.conflicto("AJUSTE_SIN_DESTINO", e.getMessage());
      }
      if (r.diferencia().signum() == 0) {
        throw ErrorNegocio.invalido("AJUSTE_SIN_DIFERENCIA", "El costo nuevo es igual al vigente");
      }
      if (r.enAlmacen().signum() != 0) {
        c.registrar(k, l, v.ajustarValor(r.enAlmacen()));
      }
      for (int j = 0; j < consumos.size(); j++) {
        BigDecimal parte = r.porConsumo().get(j);
        if (parte.signum() == 0) {
          continue;
        }
        Consumo x = consumos.get(j);
        saldos.registrarCargo(
            empresa(),
            new NuevoCargo(
                c.m.id(),
                l.id(),
                x.lineaBaseId(),
                c.m.fecha(),
                l.materialId(),
                x.contratoId(),
                x.unidadObraId(),
                x.paqueteId(),
                x.clasificacionId(),
                BigDecimal.ZERO,
                parte));
      }
      movimientos.congelarCosto(empresa(), l.id(), l.costoNuevo(), r.diferencia());
    }
  }

  // ------------------------------------------------------------------------------ Apoyo

  /** Lo que se retira de una línea base en obra: cantidad, importe y costo efectivo. */
  private record Retiro(BigDecimal importe, BigDecimal costo) {}

  private LineaConDocumento baseEnObra(FilaLinea l) {
    return movimientos
        .lineaConDocumento(empresa(), l.lineaOrigenId(), true)
        .filter(
            b ->
                b.estado().equals("aplicado")
                    && (b.tipo().equals("salida") || b.tipo().equals("reasignacion")))
        .orElseThrow(
            () ->
                ErrorNegocio.conflicto(
                    "LINEA_ORIGEN_INVALIDA", "La línea de origen no está aplicada"));
  }

  /**
   * Retira {@code cantidad} de lo que conserva una línea base, a su costo efectivo (importe que
   * queda ÷ cantidad que queda, con los ajustes de factura incluidos). Si se retira todo, se lleva
   * todo el importe que queda.
   */
  private Retiro retirar(LineaConDocumento base, BigDecimal cantidad) {
    Restante restante = movimientos.restante(empresa(), base.id());
    if (cantidad.compareTo(restante.cantidad()) > 0) {
      throw ErrorNegocio.conflicto(
          "DEVOLUCION_EXCEDE",
          "De "
              + base.folio()
              + " quedan "
              + restante.cantidad().stripTrailingZeros().toPlainString()
              + " en obra; no se puede regresar o reasignar más");
    }
    BigDecimal importeQueda = restante.importe().max(BigDecimal.ZERO);
    BigDecimal costo =
        importeQueda.divide(restante.cantidad(), Valuacion.DECIMALES_COSTO, RoundingMode.HALF_EVEN);
    BigDecimal importe =
        cantidad.compareTo(restante.cantidad()) == 0
            ? importeQueda.setScale(Valuacion.DECIMALES_IMPORTE, RoundingMode.HALF_EVEN)
            : Valuacion.importe(cantidad, costo).min(importeQueda);
    return new Retiro(importe, costo);
  }

  /** Al aplicar, los contratos y lotes siguen activos (pudieron cambiar desde la captura). */
  private void revisarDestinos(List<FilaLinea> lineas) {
    Map<Long, ContratoInfo> contratos =
        nombres.contratos(
            lineas.stream().map(FilaLinea::contratoId).filter(Objects::nonNull).toList());
    Map<Long, UnidadInfo> unidades =
        nombres.unidades(
            lineas.stream().map(FilaLinea::unidadObraId).filter(Objects::nonNull).toList());
    for (FilaLinea l : lineas) {
      ContratoInfo contrato = contratos.get(l.contratoId());
      if (contrato == null || !contrato.activo()) {
        throw ErrorNegocio.conflicto(
            "CONTRATO_NO_ACTIVO",
            "El contrato " + (contrato == null ? "" : contrato.codigo()) + " ya no está activo");
      }
      UnidadInfo unidad = unidades.get(l.unidadObraId());
      if (unidad == null || !unidad.activa()) {
        throw ErrorNegocio.conflicto(
            "LOTE_INACTIVO",
            "El lote " + (unidad == null ? "" : unidad.clave()) + " está desactivado");
      }
    }
  }

  private ErrorNegocio insuficiente(long material, BigDecimal pedida, BigDecimal disponible) {
    MaterialInfo info = nombres.materiales(List.of(material)).get(material);
    String nombre = info == null ? "el material" : info.nombre();
    String unidad = info == null ? "" : " " + info.unidadControl();
    return ErrorNegocio.conflicto(
        "EXISTENCIA_INSUFICIENTE",
        "La salida de "
            + pedida.stripTrailingZeros().toPlainString()
            + unidad
            + " de "
            + nombre
            + " supera lo disponible ("
            + disponible.stripTrailingZeros().toPlainString()
            + unidad
            + ")");
  }

  /** Índices de {@code lineas} en orden de material (y de captura dentro del material). */
  private static List<Integer> ordenPorMaterial(List<FilaLinea> lineas) {
    List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < lineas.size(); i++) {
      indices.add(i);
    }
    indices.sort(
        Comparator.<Integer>comparingLong(i -> lineas.get(i).materialId())
            .thenComparingInt(i -> lineas.get(i).renglon())
            .thenComparingLong(i -> lineas.get(i).id()));
    return indices;
  }

  private static List<Long> listaSinNulos(Long... ids) {
    List<Long> lista = new ArrayList<>();
    for (Long id : ids) {
      if (id != null) {
        lista.add(id);
      }
    }
    return lista;
  }

  /** Almacén y material: la llave de un saldo, ordenable para bloquear siempre igual. */
  record Llave(long almacen, long material) implements Comparable<Llave> {
    @Override
    public int compareTo(Llave otra) {
      int porAlmacen = Long.compare(almacen, otra.almacen);
      return porAlmacen != 0 ? porAlmacen : Long.compare(material, otra.material);
    }
  }

  /** Lo que una aplicación lleva en memoria: los saldos bloqueados y cómo van quedando. */
  private final class Corrida {
    private final FilaMovimiento m;
    private final List<FilaLinea> lineas;
    private final Map<Llave, FilaSaldo> filas = new HashMap<>();
    private final Map<Llave, Valuacion> valuaciones = new HashMap<>();

    Corrida(FilaMovimiento m, List<FilaLinea> lineas) {
      this.m = m;
      this.lineas = lineas;
    }

    /** Bloquea, en orden, los saldos de todos los materiales del documento en esos almacenes. */
    void bloquear(long... almacenesDelDocumento) {
      TreeSet<Llave> llaves = new TreeSet<>();
      for (long almacen : almacenesDelDocumento) {
        lineas.forEach(l -> llaves.add(new Llave(almacen, l.materialId())));
      }
      for (Llave k : llaves) {
        FilaSaldo fila = saldos.bloquear(empresa(), k.almacen(), k.material());
        filas.put(k, fila);
        valuaciones.put(k, new Valuacion(fila.existencia(), fila.valor(), fila.costoPromedio()));
      }
    }

    Valuacion valuacion(Llave k) {
      Valuacion v = valuaciones.get(k);
      if (v == null) {
        throw new IllegalStateException("Saldo sin bloquear: " + k);
      }
      return v;
    }

    BigDecimal disponible(Llave k) {
      FilaSaldo fila = filas.get(k);
      return valuacion(k).existencia().subtract(fila.reservado()).subtract(fila.prestado());
    }

    void poner(Llave k, Valuacion v) {
      valuaciones.put(k, v);
    }

    /** Kardex del paso y nueva valuación. */
    void registrar(Llave k, FilaLinea l, Paso p) {
      kardex(
          k,
          l,
          p.cantidad(),
          p.costoUnitario(),
          p.importe(),
          p.despues().existencia(),
          p.despues().valor(),
          p.despues().costoPromedio());
      poner(k, p.despues());
    }

    void kardex(
        Llave k,
        FilaLinea l,
        BigDecimal cantidad,
        BigDecimal costo,
        BigDecimal importe,
        BigDecimal existencia,
        BigDecimal valor,
        BigDecimal promedio) {
      saldos.registrarKardex(
          empresa(),
          new NuevoKardex(
              k.almacen(),
              k.material(),
              m.id(),
              l.id(),
              m.fecha(),
              cantidad,
              costo,
              importe,
              existencia,
              valor,
              promedio));
    }

    void guardarSaldos() {
      for (var e : valuaciones.entrySet()) {
        Valuacion v = e.getValue();
        FilaSaldo antes = filas.get(e.getKey());
        if (v.existencia().compareTo(antes.existencia()) != 0
            || v.valor().compareTo(antes.valor()) != 0
            || v.costoPromedio().compareTo(antes.costoPromedio()) != 0) {
          saldos.guardar(
              empresa(),
              e.getKey().almacen(),
              e.getKey().material(),
              v.existencia(),
              v.valor(),
              v.costoPromedio());
        }
      }
    }
  }
}

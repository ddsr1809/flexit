package mx.constructora.inventario.dominio;

import java.util.Arrays;
import java.util.Optional;

/**
 * Tipos de documento del almacén y lo que cada uno exige. La base repite estas reglas en sus CHECK
 * (V007); aquí se usan para dar un error claro antes de llegar a ella.
 */
public enum TipoMovimiento {
  /** Recepción de compra: entra al costo de la factura, sin IVA. */
  ENTRADA("entrada", "ENT", QuienAutoriza.NADIE, Efecto.ENTRA),
  /** Vale de salida a obra. */
  SALIDA("salida", "SAL", QuienAutoriza.VALE, Efecto.SALE),
  /** Devolución de obra: regresa al almacén al costo con que salió. */
  DEVOLUCION("devolucion", "DEV", QuienAutoriza.NADIE, Efecto.ENTRA),
  /** Devolución al proveedor: sale al costo con que entró. */
  DEVOLUCION_PROVEEDOR("devolucion_proveedor", "DPR", QuienAutoriza.NADIE, Efecto.SALE),
  /** Traspaso entre almacenes, al costo promedio del origen. */
  TRASPASO("traspaso", "TRA", QuienAutoriza.COORDINACION, Efecto.TRASLADA),
  /** Pasa el costo de un lote a otro sin mover material. */
  REASIGNACION("reasignacion", "REA", QuienAutoriza.COORDINACION, Efecto.NINGUNO),
  /** Material roto, vencido o perdido, con foto. */
  MERMA("merma", "MER", QuienAutoriza.COORDINACION, Efecto.SALE),
  /** Sobrante del conteo físico. */
  AJUSTE_ENTRADA("ajuste_entrada", "AJE", QuienAutoriza.NADIE, Efecto.ENTRA),
  /** Faltante del conteo físico. */
  AJUSTE_SALIDA("ajuste_salida", "AJS", QuienAutoriza.NADIE, Efecto.SALE),
  /** Diferencia de factura: corrige el costo de una recepción ya aplicada. */
  AJUSTE_COSTO("ajuste_costo", "ACO", QuienAutoriza.COORDINACION, Efecto.VALOR);

  /** Quién puede autorizar el documento antes de aplicarlo. */
  public enum QuienAutoriza {
    /** No se autoriza: quien captura lo aplica. */
    NADIE,
    /** El residente del contrato o coordinación (vale:autorizar). */
    VALE,
    /** Coordinación o dirección (documento:autorizar). */
    COORDINACION
  }

  /** Qué le hace a la existencia del almacén. */
  public enum Efecto {
    ENTRA,
    SALE,
    TRASLADA,
    NINGUNO,
    VALOR
  }

  private final String clave;
  private final String prefijoFolio;
  private final QuienAutoriza autoriza;
  private final Efecto efecto;

  TipoMovimiento(String clave, String prefijoFolio, QuienAutoriza autoriza, Efecto efecto) {
    this.clave = clave;
    this.prefijoFolio = prefijoFolio;
    this.autoriza = autoriza;
    this.efecto = efecto;
  }

  /** Como se guarda en la base y viaja en la API: {@code salida}, {@code ajuste_costo}… */
  public String clave() {
    return clave;
  }

  public String prefijoFolio() {
    return prefijoFolio;
  }

  public QuienAutoriza autoriza() {
    return autoriza;
  }

  public Efecto efecto() {
    return efecto;
  }

  public boolean requiereAutorizacion() {
    return autoriza != QuienAutoriza.NADIE;
  }

  /** Sus líneas llevan contrato, unidad de obra, paquete y clasificación (regla 4). */
  public boolean cargaAObra() {
    return this == SALIDA || this == DEVOLUCION || this == REASIGNACION;
  }

  /** Sus líneas apuntan a una línea anterior (la que se devuelve, reasigna o corrige). */
  public boolean requiereLineaOrigen() {
    return this == DEVOLUCION
        || this == DEVOLUCION_PROVEEDOR
        || this == REASIGNACION
        || this == AJUSTE_COSTO;
  }

  /** Se captura a mano; los ajustes de conteo los genera el cierre del conteo. */
  public boolean seCaptura() {
    return this != AJUSTE_ENTRADA && this != AJUSTE_SALIDA;
  }

  public static Optional<TipoMovimiento> deClave(String clave) {
    return Arrays.stream(values()).filter(t -> t.clave.equals(clave)).findFirst();
  }
}

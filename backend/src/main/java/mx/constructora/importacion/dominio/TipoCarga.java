package mx.constructora.importacion.dominio;

import java.util.List;

/**
 * Qué se carga, en qué orden y qué necesita antes. El sistema no deja subir una carga si alguna de
 * las que requiere no está confirmada ("Orden de carga" del plan por hitos).
 */
public enum TipoCarga {
  FRENTES(
      1,
      "Frentes",
      "Info. listas desplegables (almacén): columna Frente",
      List.of(),
      List.of("clienteId")),
  CONTRATOS(2, "Contratos", "CONTRATOS ACTIVOS (SUA)", List.of(FRENTES), List.of()),
  LOTES(
      2,
      "Lotes y prototipos",
      "Hojas de pagos del destajo (lote y prototipo) o una hoja \"Lotes\" con frente, contrato,"
          + " manzana, lote y prototipo",
      List.of(CONTRATOS),
      List.of()),
  PAQUETES(
      2,
      "Paquetes y precios de destajo",
      "Hojas de pagos del destajo: secciones, conceptos y precio por prototipo",
      List.of(LOTES),
      List.of()),
  MATERIALES(
      3,
      "Materiales",
      "Existencias, con el precio ponderado de Entradas y los materiales que solo aparecen en"
          + " movimientos",
      List.of(),
      List.of()),
  PROVEEDORES(3, "Proveedores", "Entradas (almacén): columna Proveedor", List.of(), List.of()),
  PERSONAS_SUA(4, "Personas del SUA", "IMSS, ASISTENCIA, SUA ANTERIOR y BD", List.of(), List.of()),
  PERSONAS_DESTAJO(
      4,
      "Trabajadores del destajo",
      "Columnas Trabajador de las hojas de pagos",
      List.of(PERSONAS_SUA),
      List.of()),
  PERSONAS_ALMACEN(
      4,
      "Solicitantes del almacén",
      "Solicitante y Entregar a de Salidas, Semanal, Préstamos y Faltantes; Quién recibe de"
          + " Entradas",
      List.of(PERSONAS_SUA),
      List.of());

  private final int paso;
  private final String nombre;
  private final String origen;
  private final List<TipoCarga> requiere;
  private final List<String> parametros;

  TipoCarga(
      int paso, String nombre, String origen, List<TipoCarga> requiere, List<String> parametros) {
    this.paso = paso;
    this.nombre = nombre;
    this.origen = origen;
    this.requiere = requiere;
    this.parametros = parametros;
  }

  /** Paso del orden de carga: 1 listas, 2 estructura, 3 catálogo, 4 personas. */
  public int paso() {
    return paso;
  }

  public String nombre() {
    return nombre;
  }

  /** De qué hojas sale. */
  public String origen() {
    return origen;
  }

  /** Cargas que deben estar confirmadas antes. */
  public List<TipoCarga> requiere() {
    return requiere;
  }

  /** Parámetros obligatorios al subir (el cliente de los frentes). */
  public List<String> parametros() {
    return parametros;
  }
}

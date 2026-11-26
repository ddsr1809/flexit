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
      List.of()),
  HIST_ENTRADAS(
      5,
      "Histórico: entradas",
      "Entradas (almacén): cada recepción con su precio sin IVA",
      List.of(MATERIALES, PROVEEDORES, PERSONAS_ALMACEN),
      List.of()),
  HIST_SALIDAS(
      5,
      "Histórico: salidas",
      "Salidas (almacén): vales por lote, valuados con las entradas del histórico",
      List.of(HIST_ENTRADAS, LOTES, PAQUETES),
      List.of()),
  HIST_REQUISICIONES(
      5,
      "Histórico: Semanal y faltantes",
      "Semanal y Faltante solicitud diaria (almacén)",
      List.of(MATERIALES, LOTES, PAQUETES, PERSONAS_ALMACEN),
      List.of()),
  HIST_PRESTAMOS(
      5,
      "Histórico: préstamos",
      "Prestamo Herramientas-Material (almacén)",
      List.of(MATERIALES, FRENTES, PERSONAS_ALMACEN),
      List.of()),
  HIST_SUA(
      6,
      "Histórico: SUA del mes",
      "IMSS, ASISTENCIA, SUA ANTERIOR y BD del SUA de un mes",
      List.of(CONTRATOS, PERSONAS_SUA),
      List.of("mes")),
  HIST_DESTAJO(
      7,
      "Histórico: pagos de destajo",
      "Hojas de pagos del destajo: pago por lote, concepto, semana y trabajador",
      List.of(PAQUETES, PERSONAS_DESTAJO),
      List.of("anio"));

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

  /**
   * Paso del orden de carga: 1 listas, 2 estructura, 3 catálogo, 4 personas, 5 histórico de
   * almacén, 6 histórico de personal, 7 histórico de destajo.
   */
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

  /** {@code true} si escribe en el histórico (H5) y no en los catálogos. */
  public boolean historico() {
    return name().startsWith("HIST_");
  }

  /**
   * Parámetros obligatorios al subir: el cliente de los frentes, el mes del SUA ({@code 2026-10}) o
   * el año de las semanas del destajo.
   */
  public List<String> parametros() {
    return parametros;
  }
}

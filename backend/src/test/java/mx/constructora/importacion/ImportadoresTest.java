package mx.constructora.importacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mx.constructora.importacion.dominio.Contexto;
import mx.constructora.importacion.dominio.Importador;
import mx.constructora.importacion.dominio.ImportadorPersonas;
import mx.constructora.importacion.dominio.Importadores;
import mx.constructora.importacion.dominio.Modelo.Accion;
import mx.constructora.importacion.dominio.Modelo.Decision;
import mx.constructora.importacion.dominio.Modelo.Decisiones;
import mx.constructora.importacion.dominio.Modelo.EstadoFila;
import mx.constructora.importacion.dominio.Modelo.FilaCruda;
import mx.constructora.importacion.dominio.Modelo.FilaValidada;
import mx.constructora.importacion.dominio.Modelo.Incidencia;
import mx.constructora.importacion.dominio.Modelo.Opcion;
import mx.constructora.importacion.dominio.TipoCarga;
import mx.constructora.importacion.dominio.xlsx.ArchivoInvalido;
import mx.constructora.importacion.dominio.xlsx.LibroXlsx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cada regla de transformación del plan con el renglón (anonimizado) que la motiva. Corre los
 * importadores contra las muestras de {@code tools/validacion-datos/muestras/} y un sistema en
 * memoria, en el mismo orden de carga que el centro de migración.
 */
class ImportadoresTest {

  private SistemaEnMemoria sistema;
  private long cliente;
  private final List<Decision> decisiones = new ArrayList<>();

  @BeforeEach
  void preparar() {
    sistema = new SistemaEnMemoria();
    cliente = sistema.cliente();
    sistema.parametro("clienteId", cliente);
  }

  private List<FilaCruda> leer(TipoCarga tipo, String muestra) {
    try (LibroXlsx libro = LibroXlsx.abrir(Muestras.ruta(muestra))) {
      return Importadores.de(tipo).leer(libro).filas();
    }
  }

  private List<FilaValidada> validar(TipoCarga tipo, String muestra) {
    return Importadores.de(tipo).validar(leer(tipo, muestra), sistema, Decisiones.de(decisiones));
  }

  /** Valida, y si no queda nada en cuarentena, confirma. */
  private List<FilaValidada> confirmar(TipoCarga tipo, String muestra) {
    List<FilaValidada> filas = validar(tipo, muestra);
    assertThat(filas).noneMatch(f -> f.estado() == EstadoFila.CUARENTENA);
    Importador importador = Importadores.de(tipo);
    importador.aplicar(filas, sistema, sistema);
    return filas;
  }

  private static FilaValidada fila(List<FilaValidada> filas, String campo, String valor) {
    return filas.stream()
        .filter(f -> valor.equals(f.fila().crudo().get(campo)) || valor.equals(f.dato(campo)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No hay renglón con " + campo + "=" + valor));
  }

  private static Incidencia incidencia(FilaValidada fila, String regla) {
    return fila.incidencias().stream()
        .filter(i -> i.regla().equals(regla))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("El renglón no tiene " + regla + ": " + fila.incidencias()));
  }

  private void aceptarAlias(Incidencia incidencia) {
    Map<String, Object> sugerencia = incidencia.sugerencia();
    sistema.ponerAlias(
        (String) sugerencia.get("entidad"),
        (String) sugerencia.get("texto"),
        ((Number) sugerencia.get("destinoId")).longValue());
  }

  private void decidir(FilaValidada fila, String regla, Opcion opcion, Long destino, String valor) {
    decisiones.add(new Decision(fila.numero(), regla, opcion, destino, valor, "prueba"));
  }

  // ------------------------------------------------------------------------------ estructura

  private void frentes() {
    List<FilaValidada> filas = validar(TipoCarga.FRENTES, "muestra-almacen.xlsx");
    decidir(fila(filas, "C", "Almacén"), "NO_ES_FRENTE", Opcion.DESCARTAR, null, null);
    confirmar(TipoCarga.FRENTES, "muestra-almacen.xlsx");
    decisiones.clear();
  }

  private Contexto.Frente frente(String clave) {
    return sistema.frentes().stream()
        .filter(f -> f.clave().equals(clave))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void frentesDeLaListaDesplegable() {
    List<FilaValidada> filas = validar(TipoCarga.FRENTES, "muestra-almacen.xlsx");
    assertThat(filas).hasSize(5);
    assertThat(fila(filas, "C", "Corceles").accion()).isEqualTo(Accion.CREAR);
    assertThat(fila(filas, "C", "Almacén").estado()).isEqualTo(EstadoFila.CUARENTENA);
    assertThat(incidencia(fila(filas, "C", "Almacén"), "NO_ES_FRENTE").opciones())
        .contains(Opcion.CREAR, Opcion.DESCARTAR);
    FilaValidada repetido = fila(filas, "C", "corceles");
    assertThat(repetido.accion()).isEqualTo(Accion.OMITIR);
    assertThat(incidencia(repetido, "DUPLICADO_EN_CARGA").detalle()).contains("renglón 1");

    frentes();
    assertThat(sistema.frentes())
        .extracting(Contexto.Frente::clave)
        .containsExactly("CORCELES", "GRANADA", "PALERMO");
    assertThat(sistema.frentes()).allMatch(f -> f.clienteId() == cliente);
  }

  @Test
  void sinClienteLosFrentesNoSeCargan() {
    sistema.parametros.remove("clienteId");
    List<FilaValidada> filas = validar(TipoCarga.FRENTES, "muestra-almacen.xlsx");
    assertThat(filas).allMatch(f -> f.estado() == EstadoFila.CUARENTENA);
    assertThat(incidencia(filas.getFirst(), "FALTA_CLIENTE").severidad().detiene()).isTrue();
  }

  private void contratos() {
    frentes();
    List<FilaValidada> filas = validar(TipoCarga.CONTRATOS, "muestra-sua.xlsx");
    for (FilaValidada f : filas) {
      f.incidencias().stream()
          .filter(i -> i.regla().equals("FRENTE_POR_CONFIRMAR") && i.pendiente())
          .findFirst()
          .ifPresent(this::aceptarAlias);
    }
    filas = validar(TipoCarga.CONTRATOS, "muestra-sua.xlsx");
    // Un bloqueo no se corrige desde aquí: se corrige el Excel o se descarta el renglón con motivo.
    assertThat(incidencia(fila(filas, "B", "60-DDD-MEG-C001-2026"), "FECHAS_INVERTIDAS").opciones())
        .containsExactly(Opcion.DESCARTAR);
    decidir(
        fila(filas, "B", "60-DDD-MEG-C001-2026"),
        "FECHAS_INVERTIDAS",
        Opcion.DESCARTAR,
        null,
        null);
    filas = validar(TipoCarga.CONTRATOS, "muestra-sua.xlsx");
    assertThat(fila(filas, "B", "60-DDD-MEG-C001-2026").estado()).isEqualTo(EstadoFila.DESCARTADA);
  }

  @Test
  void contratosDelSua() {
    frentes();
    List<FilaValidada> filas = validar(TipoCarga.CONTRATOS, "muestra-sua.xlsx");
    assertThat(filas).as("solo los renglones con código en B").hasSize(4);

    FilaValidada granada = fila(filas, "B", "60-bbb-mek/C001/2026");
    assertThat(granada.dato("codigo")).isEqualTo("60-BBB-MEK-C001-2026");
    assertThat(incidencia(granada, "CODIGO_NORMALIZADO").corrige()).isTrue();

    // "CORCELES FRACCIONAMIENTO" contiene el frente CORCELES: se sugiere y una persona confirma.
    FilaValidada corceles = fila(filas, "B", "60-AAA-MF6-C002-2026");
    Incidencia frente = incidencia(corceles, "FRENTE_POR_CONFIRMAR");
    assertThat(corceles.estado()).isEqualTo(EstadoFila.CUARENTENA);
    assertThat(frente.sugerencia())
        .containsEntry("entidad", "frente")
        .containsEntry("texto", "CORCELES FRACCIONAMIENTO")
        .containsEntry("destinoId", frente("CORCELES").id());
    aceptarAlias(frente);

    filas = validar(TipoCarga.CONTRATOS, "muestra-sua.xlsx");
    corceles = fila(filas, "B", "60-AAA-MF6-C002-2026");
    assertThat(corceles.estado()).isEqualTo(EstadoFila.VALIDA);
    assertThat(corceles.dato("monto")).as("redondeo a centavos").isEqualTo("4882033.11");
    assertThat(corceles.dato("inicio")).isEqualTo("2026-07-12");
    assertThat(corceles.id("clienteId")).isEqualTo(cliente);

    FilaValidada invertido = fila(filas, "B", "60-DDD-MEG-C001-2026");
    assertThat(incidencia(invertido, "FECHAS_INVERTIDAS").severidad().detiene()).isTrue();
  }

  @Test
  void lotesDelDestajoConElContratoQueEligeUnaPersona() {
    contratosConfirmados();
    List<FilaValidada> filas = validar(TipoCarga.LOTES, "muestra-destajo.xlsx");
    assertThat(filas)
        .extracting(f -> f.fila().crudo().get("lote"))
        .containsExactly("M20L10", "M20L15", "M21L3", "M56L6", "M56L7", "M16L3", "M716", "M20L10");
    // La hoja "Pagos Corceles P-65" se reconoce como el frente Corceles, con confirmación.
    for (FilaValidada f : filas) {
      f.incidencias().stream()
          .filter(i -> i.regla().equals("FRENTE_POR_CONFIRMAR") && i.pendiente())
          .findFirst()
          .ifPresent(this::aceptarAlias);
    }
    filas = validar(TipoCarga.LOTES, "muestra-destajo.xlsx");
    FilaValidada primero = filas.getFirst();
    assertThat(primero.dato("clave")).isEqualTo("M20-L10");
    assertThat(primero.dato("prototipoClave")).isEqualTo("P-65");
    assertThat(incidencia(primero, "LOTE_SIN_CONTRATO").sugerencia())
        .containsEntry("frenteId", frente("CORCELES").id())
        .doesNotContainKey("destinoId");

    // Elegir el contrato de un lote sugiere el mismo para los demás lotes de la manzana.
    long contratoCorceles =
        sistema.contratos().stream()
            .filter(c -> c.codigo().equals("60-AAA-MF6-C002-2026"))
            .findFirst()
            .orElseThrow()
            .id();
    decidir(primero, "LOTE_SIN_CONTRATO", Opcion.ELEGIR, contratoCorceles, null);
    filas = validar(TipoCarga.LOTES, "muestra-destajo.xlsx");
    assertThat(filas.getFirst().estado()).isEqualTo(EstadoFila.CORREGIDA);
    assertThat(incidencia(filas.get(1), "LOTE_SIN_CONTRATO").sugerencia())
        .containsEntry("destinoId", contratoCorceles);
    assertThat(incidencia(filas.get(2), "LOTE_SIN_CONTRATO").sugerencia())
        .as("otra manzana: sin sugerencia")
        .doesNotContainKey("destinoId");

    // Un contrato de otro frente no sirve
    long contratoGranada =
        sistema.contratos().stream()
            .filter(c -> c.codigo().equals("60-BBB-MEK-C001-2026"))
            .findFirst()
            .orElseThrow()
            .id();
    decidir(filas.get(2), "LOTE_SIN_CONTRATO", Opcion.ELEGIR, contratoGranada, null);
    filas = validar(TipoCarga.LOTES, "muestra-destajo.xlsx");
    assertThat(filas.get(2).estado()).isEqualTo(EstadoFila.CUARENTENA);

    // "M716" no es un lote; se corrige escribiendo el lote
    FilaValidada ilegible = fila(filas, "lote", "M716");
    assertThat(incidencia(ilegible, "LOTE_ILEGIBLE").opciones()).contains(Opcion.VALOR);
    decidir(ilegible, "LOTE_ILEGIBLE", Opcion.VALOR, null, "M7L16");
    filas = validar(TipoCarga.LOTES, "muestra-destajo.xlsx");
    assertThat(fila(filas, "lote", "M716").dato("clave")).isEqualTo("M7-L16");
  }

  private void contratosConfirmados() {
    contratos();
    confirmar(TipoCarga.CONTRATOS, "muestra-sua.xlsx");
    assertThat(sistema.contratos()).hasSize(3);
    decisiones.clear();
  }

  /** Lotes confirmados: cada lote con el primer contrato de su frente. */
  private void lotesConfirmados() {
    contratosConfirmados();
    List<FilaValidada> filas = validar(TipoCarga.LOTES, "muestra-destajo.xlsx");
    for (FilaValidada f : filas) {
      f.incidencias().stream()
          .filter(i -> i.regla().equals("FRENTE_POR_CONFIRMAR") && i.pendiente())
          .findFirst()
          .ifPresent(this::aceptarAlias);
    }
    filas = validar(TipoCarga.LOTES, "muestra-destajo.xlsx");
    for (FilaValidada f : filas) {
      if (f.fila().crudo().get("lote").equals("M716")) {
        decidir(f, "LOTE_ILEGIBLE", Opcion.DESCARTAR, null, null);
        continue;
      }
      long frenteId = f.id("frenteId");
      long contrato =
          sistema.contratos().stream()
              .filter(c -> c.frenteId() == frenteId)
              .findFirst()
              .orElseThrow()
              .id();
      decidir(f, "LOTE_SIN_CONTRATO", Opcion.ELEGIR, contrato, null);
    }
    confirmar(TipoCarga.LOTES, "muestra-destajo.xlsx");
    decisiones.clear();
  }

  @Test
  void elMismoArchivoNoDuplicaNada() {
    lotesConfirmados();
    assertThat(sistema.unidades()).hasSize(7);
    assertThat(sistema.prototipos())
        .extracting(Contexto.Prototipo::clave)
        .containsExactly("P-65", "P-51", "P-45");
    // M20L10 en Corceles y en Palermo son lotes distintos (otro frente)
    assertThat(sistema.unidades()).filteredOn(u -> u.clave().equals("M20-L10")).hasSize(2);

    for (TipoCarga tipo : List.of(TipoCarga.FRENTES, TipoCarga.CONTRATOS, TipoCarga.LOTES)) {
      String muestra =
          switch (tipo) {
            case FRENTES -> "muestra-almacen.xlsx";
            case CONTRATOS -> "muestra-sua.xlsx";
            default -> "muestra-destajo.xlsx";
          };
      assertThat(validar(tipo, muestra)).as(tipo.name()).noneMatch(f -> f.accion() == Accion.CREAR);
    }
  }

  @Test
  void plantillaDeLotes() {
    contratosConfirmados();
    List<FilaValidada> filas = validar(TipoCarga.LOTES, "muestra-lotes.xlsx");
    // "29-31, 35" se expande en cuatro lotes
    assertThat(filas).hasSize(7);
    FilaValidada expandido = filas.getFirst();
    assertThat(expandido.dato("clave")).isEqualTo("M20-L29");
    assertThat(incidencia(expandido, "LOTES_EXPANDIDOS").corrige()).isTrue();
    assertThat(incidencia(expandido, "PROTOTIPO_NORMALIZADO").detalle()).contains("P-69");
    assertThat(expandido.dato("contratoId")).as("el código con / se reconoce").isNotNull();
    assertThat(expandido.estado()).isEqualTo(EstadoFila.CORREGIDA);
    assertThat(fila(filas, "D", "36").dato("prototipoClave")).isEqualTo("P-79");
    assertThat(
            incidencia(fila(filas, "B", "60-ZZZ-NOEXISTE-2026"), "CONTRATO_DESCONOCIDO")
                .pendiente())
        .isTrue();
    assertThat(incidencia(fila(filas, "C", "x"), "LOTE_ILEGIBLE").severidad().detiene()).isTrue();
  }

  @Test
  void paquetesYPreciosDelDestajo() {
    lotesConfirmados();
    List<FilaValidada> filas = validar(TipoCarga.PAQUETES, "muestra-destajo.xlsx");
    assertThat(filas)
        .as("el resumen PRECIOS / TOTAL GASTADO no es catálogo")
        .noneMatch(f -> "PRECIOS".equals(f.fila().crudo().get("nombre")));

    FilaValidada repetido = fila(filas, "nombre", "COLOCACION DE CAJAS ELECTRICAS (2)");
    assertThat(incidencia(repetido, "CONCEPTO_REPETIDO").corrige()).isTrue();

    FilaValidada nota = fila(filas, "nombre", "acuerdo con la cuadrilla, precio anterior");
    assertThat(nota.estado()).isEqualTo(EstadoFila.DESCARTADA);

    FilaValidada sinNombre =
        filas.stream()
            .filter(
                f ->
                    f.incidencias().stream().anyMatch(i -> i.regla().equals("CONCEPTO_SIN_NOMBRE")))
            .findFirst()
            .orElseThrow();
    assertThat(sinNombre.estado()).isEqualTo(EstadoFila.CUARENTENA);
    decidir(sinNombre, "CONCEPTO_SIN_NOMBRE", Opcion.VALOR, null, "Dinteles");
    filas = confirmar(TipoCarga.PAQUETES, "muestra-destajo.xlsx");

    // Catálogo propio: CIMENTACIÓN (S01) y MUROS 1ER NIVEL (S02) con sus conceptos
    assertThat(sistema.paquetes())
        .filteredOn(p -> p.clienteId() == null && p.padreId() == null)
        .extracting(Contexto.Paquete::clave)
        .containsExactly("S01", "S02");
    assertThat(sistema.paquetes())
        .filteredOn(p -> p.clienteId() == null)
        .extracting(Contexto.Paquete::nombreNormal)
        .contains(
            "COLOCACION DE CAJAS ELECTRICAS", "COLOCACION DE CAJAS ELECTRICAS (2)", "DINTELES");
    // Catálogo del cliente (Ruba) con su equivalencia por sección y precios de cobro
    assertThat(sistema.paquetes())
        .filteredOn(p -> p.clienteId() != null && p.padreId() != null)
        .extracting(Contexto.Paquete::nombreNormal)
        .containsExactly("PAQUETE 01 TRAZO Y EXCAVACION", "PAQUETE 03 COLADO DE CIMENTACION");
    assertThat(sistema.equivalencias()).isNotEmpty();
    assertThat(sistema.valoresPrecio.values())
        .anyMatch(v -> v[0] != null && v[0].compareTo(new java.math.BigDecimal("4250.03")) == 0);
    assertThat(sistema.valoresPrecio.values())
        .anyMatch(v -> v[1] != null && v[1].compareTo(new java.math.BigDecimal("2400")) == 0);

    assertThat(validar(TipoCarga.PAQUETES, "muestra-destajo.xlsx"))
        .as("segunda vez: nada que crear")
        .noneMatch(f -> f.accion() == Accion.CREAR);
  }

  // -------------------------------------------------------------------------------- catálogo

  @Test
  void materialesConUnidadYPrecioPonderadoSinIva() {
    List<FilaValidada> filas = validar(TipoCarga.MATERIALES, "muestra-almacen.xlsx");

    FilaValidada abrazadera = fila(filas, "E", "001");
    assertThat(incidencia(abrazadera, "UNIDAD_NORMALIZADA").detalle()).contains("PZA");
    // (40 × 1.50 + 60 × 2.00 − 10 de descuento) / 100 = 1.70
    assertThat(abrazadera.dato("precio")).isEqualTo("1.700000");

    assertThat(fila(filas, "E", "004").estado()).as("\"Bolsas \"").isEqualTo(EstadoFila.CORREGIDA);
    assertThat(incidencia(fila(filas, "E", "002"), "PRECIO_VARIABLE").detalle())
        .contains("220", "600");
    assertThat(incidencia(fila(filas, "E", "003"), "SIN_PRECIO").pendiente()).isFalse();
    assertThat(incidencia(fila(filas, "E", "006"), "UNIDAD_DESCONOCIDA").pendiente()).isTrue();

    // "Lija 221" solo está en Salidas: se sugiere "Lija 220"
    FilaValidada lija = fila(filas, "texto", "Lija 221");
    Incidencia suelto = incidencia(lija, "MATERIAL_SOLO_EN_MOVIMIENTOS");
    assertThat(suelto.detalle()).contains("2 veces");
    assertThat(lija.fila().crudo().get("_celdas")).isEqualTo("J4,J5");
    // El material todavía no existe en el sistema: no hay a quién ligarlo, solo darlo de alta.
    assertThat(suelto.opciones()).contains(Opcion.VALOR, Opcion.DESCARTAR);
    FilaValidada tubo = fila(filas, "texto", "Tubo ABS 1\" (6 metros)");
    assertThat(incidencia(tubo, "MATERIAL_SOLO_EN_MOVIMIENTOS").sugerencia())
        .as("la unidad sale de Semanal")
        .containsEntry("unidad", "Piezas");

    // Decisiones: unidad para "Juego", alta de los sueltos con su unidad, precio capturado.
    sistema.ponerAlias("unidad_medida", "JUEGO", sistema.unidadesMedida().getFirst().id());
    for (FilaValidada f : filas) {
      if (f.fila().crudo().containsKey("texto")) {
        decidir(f, "MATERIAL_SOLO_EN_MOVIMIENTOS", Opcion.VALOR, null, "PZA");
      }
    }
    decidir(fila(filas, "E", "003"), "SIN_PRECIO", Opcion.VALOR, null, "45.5");
    filas = confirmar(TipoCarga.MATERIALES, "muestra-almacen.xlsx");
    assertThat(fila(filas, "E", "003").estado()).isEqualTo(EstadoFila.CORREGIDA);
    assertThat(sistema.materiales()).hasSize(8 + 4);
    assertThat(sistema.materiales())
        .extracting(Contexto.Material::clave)
        .contains("001", "008", "009");

    assertThat(validar(TipoCarga.MATERIALES, "muestra-almacen.xlsx"))
        .noneMatch(f -> f.accion() == Accion.CREAR);
  }

  @Test
  void proveedoresSinRepetirConSuIva() {
    List<FilaValidada> filas = validar(TipoCarga.PROVEEDORES, "muestra-almacen.xlsx");
    assertThat(filas)
        .as("FERRETERA  NORTE es el mismo; la celda con fórmula no se lee")
        .extracting(f -> f.dato("nombre"))
        .containsExactly(
            "Ferretera Norte", "Materiales del Valle", "Pinturas Sol", "Ferreteria Nortte");
    assertThat(fila(filas, "nombre", "Ferretera Norte").fila().crudo())
        .containsEntry("_apariciones", "2");
    assertThat(fila(filas, "nombre", "Materiales del Valle").dato("tasaIva")).isEqualTo("0.16");
    assertThat(fila(filas, "nombre", "Pinturas Sol").dato("tasaIva")).isEqualTo("0.08");

    FilaValidada parecido = fila(filas, "nombre", "Ferreteria Nortte");
    Incidencia duplicado = incidencia(parecido, "POSIBLE_DUPLICADO");
    assertThat(duplicado.sugerencia()).containsEntry("numero", 1).doesNotContainKey("entidad");
    decidir(parecido, "POSIBLE_DUPLICADO", Opcion.ACEPTAR, null, null);
    confirmar(TipoCarga.PROVEEDORES, "muestra-almacen.xlsx");
    assertThat(sistema.proveedores()).hasSize(3);
    assertThat(sistema.alias(" proveedor".trim(), "FERRETERIA NORTTE")).isPresent();
  }

  // -------------------------------------------------------------------------------- personas

  @Test
  void personasDelSua() {
    List<FilaValidada> filas = validar(TipoCarga.PERSONAS_SUA, "muestra-sua.xlsx");
    assertThat(filas).as("\"salas  ruíz ana maría\" es la misma que la de IMSS").hasSize(8);
    assertThat(fila(filas, "nombre", "SALAS RUIZ ANA MARIA").fila().crudo().get("_hojas"))
        .isEqualTo("IMSS OCTUBRE, SUA ANTERIOR");
    assertThat(fila(filas, "nombre", "PEREZ LUNA MARIO ALBERTO").accion()).isEqualTo(Accion.CREAR);

    FilaValidada peres = fila(filas, "nombre", "PERES LUNA MARIO ALBERTO");
    assertThat(incidencia(peres, "POSIBLE_DUPLICADO").sugerencia()).containsKey("numero");
    FilaValidada orden = fila(filas, "nombre", "EDUARDO GARZA LEAL");
    assertThat(incidencia(orden, "POSIBLE_DUPLICADO").pendiente()).isTrue();

    decidir(peres, "POSIBLE_DUPLICADO", Opcion.ACEPTAR, null, null);
    decidir(orden, "POSIBLE_DUPLICADO", Opcion.ACEPTAR, null, null);
    confirmar(TipoCarga.PERSONAS_SUA, "muestra-sua.xlsx");
    assertThat(sistema.personas()).hasSize(6);
    assertThat(sistema.alias("persona", "EDUARDO GARZA LEAL")).isPresent();
  }

  @Test
  void apodosDelDestajo() {
    personasDelSua();
    decisiones.clear();
    List<FilaValidada> filas = validar(TipoCarga.PERSONAS_DESTAJO, "muestra-destajo.xlsx");
    assertThat(filas)
        .extracting(f -> f.dato("nombre"))
        .containsExactlyInAnyOrder("LALO", "LALO G", "PEPE", "MARI", "1000");
    assertThat(fila(filas, "nombre", "LALO").fila().crudo().get("_apariciones"))
        .as("\"LALO LALO\" también es Lalo")
        .isEqualTo("8");
    assertThat(fila(filas, "nombre", "1000").estado()).isEqualTo(EstadoFila.DESCARTADA);
    assertThat(incidencia(fila(filas, "nombre", "PEPE"), "NOMBRES_SEPARADOS").corrige()).isTrue();
    assertThat(filas)
        .filteredOn(f -> f.estado() == EstadoFila.CUARENTENA)
        .allMatch(f -> f.accion() == Accion.OMITIR);

    // Ningún apodo se da de alta solo. "PEPE" se elige; "LALO" se crea para revisión.
    long jose =
        sistema.personas().stream()
            .filter(p -> p.nombreNormal().equals("TORRES VEGA JOSE PEDRO"))
            .findFirst()
            .orElseThrow()
            .id();
    sistema.ponerAlias("persona", "PEPE", jose);
    decidir(fila(filas, "nombre", "LALO"), "PERSONA_SIN_LIGAR", Opcion.CREAR, null, null);
    decidir(fila(filas, "nombre", "LALO G"), "PERSONA_SIN_LIGAR", Opcion.DESCARTAR, null, null);
    decidir(fila(filas, "nombre", "MARI"), "PERSONA_SIN_LIGAR", Opcion.DESCARTAR, null, null);
    filas = confirmar(TipoCarga.PERSONAS_DESTAJO, "muestra-destajo.xlsx");
    assertThat(fila(filas, "nombre", "PEPE").accion()).isEqualTo(Accion.EXISTE);
    long lalo =
        sistema.personas().stream()
            .filter(p -> p.nombreNormal().equals("LALO"))
            .findFirst()
            .orElseThrow()
            .id();
    assertThat(sistema.personaRevisar.get(lalo)).as("alta desde apodo: para revisión").isTrue();
  }

  @Test
  void apodoConVariasCandidatas() {
    sistema.crearPersona("MORA DIAZ MARIA ELENA", false, null);
    sistema.crearPersona("SALAS RUIZ MARIA", false, null);
    assertThat(ImportadorPersonas.candidatas("MARIA", sistema.personas())).hasSize(2);
    assertThat(ImportadorPersonas.candidatas("MARIA S", sistema.personas()))
        .extracting(Contexto.Persona::nombreNormal)
        .containsExactly("SALAS RUIZ MARIA");
  }

  // ------------------------------------------------------------------------------ perfiles

  @Test
  void unLibroSinLasHojasEsperadasSeRechazaAlSubir() {
    try (LibroXlsx libro = LibroXlsx.abrir(Muestras.ruta("muestra-sua.xlsx"))) {
      assertThatThrownBy(() -> Importadores.de(TipoCarga.MATERIALES).leer(libro))
          .isInstanceOf(ArchivoInvalido.class)
          .hasMessageContaining("Existencias");
      assertThatThrownBy(() -> Importadores.de(TipoCarga.PAQUETES).leer(libro))
          .isInstanceOf(ArchivoInvalido.class);
    }
  }
}

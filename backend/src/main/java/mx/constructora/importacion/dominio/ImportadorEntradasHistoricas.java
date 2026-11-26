package mx.constructora.importacion.dominio;

import static mx.constructora.importacion.dominio.Comun.limpio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.constructora.historico.Registros.Entrada;
import mx.constructora.historico.Registros.EntradaValorada;
import mx.constructora.historico.Registros.Lote;
import mx.constructora.importacion.dominio.Modelo.Decision;
import mx.constructora.importacion.dominio.Modelo.Decisiones;
import mx.constructora.importacion.dominio.Modelo.FilaCruda;
import mx.constructora.importacion.dominio.Modelo.FilaValidada;
import mx.constructora.importacion.dominio.Modelo.Incidencia;
import mx.constructora.importacion.dominio.Modelo.Lectura;
import mx.constructora.importacion.dominio.Modelo.Opcion;
import mx.constructora.importacion.dominio.Modelo.Revision;
import mx.constructora.importacion.dominio.Modelo.Severidad;
import mx.constructora.importacion.dominio.Resolutor.Escritura;
import mx.constructora.importacion.dominio.xlsx.ArchivoInvalido;
import mx.constructora.importacion.dominio.xlsx.LibroXlsx;

/**
 * Histórico de la hoja Entradas (issue 47): cada recepción con su precio sin IVA y neto de
 * descuento. Columnas capturadas: A semana, B fecha, D cantidad, F artículo, G precio sin IVA, I
 * descuento, J tasa de IVA, M proveedor, N folio, O quién recibe, P observaciones. Unidad, subtotal
 * y totales son fórmulas y no se leen.
 *
 * <ul>
 *   <li>Cantidad o precio capturados como {@code =206.95/30}: se evalúan (aritmética simple).
 *   <li>Sin precio (180 renglones): ponderado de las entradas del material en las 4 semanas
 *       cercanas; si no hay, de todas; si no, el precio de referencia. Marcado "precio estimado".
 *   <li>IVA vacío: la tasa del proveedor; si no la tiene, 8%.
 *   <li>Año mal capturado (2027 o 2024 en la Semana 32): si con el año de la carga la fecha cae en
 *       la semana capturada, se corrige el año.
 * </ul>
 */
public final class ImportadorEntradasHistoricas extends ImportadorHistorico {

  static final String HOJA = "Entradas";

  /** Días alrededor de la recepción para estimar su precio. */
  static final int VENTANA_PRECIO = 28;

  static final BigDecimal IVA_POR_DEFECTO = new BigDecimal("0.08");

  private static final Pattern SEMANA = Pattern.compile("(?i)\\bSEM(?:ANA|\\.)?\\s*(\\d{1,2})\\b");

  @Override
  public TipoCarga tipo() {
    return TipoCarga.HIST_ENTRADAS;
  }

  @Override
  public Lectura leer(LibroXlsx libro) {
    String hoja =
        Comun.hoja(libro, HOJA)
            .orElseThrow(() -> new ArchivoInvalido("El libro no tiene la hoja \"" + HOJA + "\""));
    List<FilaCruda> filas = new ArrayList<>();
    libro.leer(
        hoja,
        Set.of("A", "B", "D", "F", "G", "I", "J", "M", "N", "O", "P"),
        r -> {
          if (r.numero() == 1) {
            Comun.exigirEncabezado(r, hoja, "B", "Fecha");
            Comun.exigirEncabezado(r, hoja, "D", "Cant");
            Comun.exigirEncabezado(r, hoja, "F", "Artículo");
            Comun.exigirEncabezado(r, hoja, "G", "Precio Unitario");
            Comun.exigirEncabezado(r, hoja, "M", "Proveedor");
            return true;
          }
          String articulo = limpio(r.capturado("F"));
          if (articulo == null) {
            return true;
          }
          Map<String, String> crudo = new LinkedHashMap<>();
          for (String c : List.of("A", "B", "F", "M", "N", "O", "P")) {
            String v = limpio(r.capturado(c));
            if (v != null) {
              crudo.put(c, v);
            }
          }
          for (String c : List.of("D", "G", "I", "J")) {
            Resolutor.leerNumero(
                crudo, c, limpio(r.capturado(c)), r.esFormula(c) ? r.celda(c).formula() : null);
          }
          if (r.esFormula("M")) {
            crudo.put("_proveedor_formula", "1");
          }
          filas.add(new FilaCruda(filas.size() + 1, hoja, r.numero(), crudo));
          return true;
        });
    return new Lectura(List.of(hoja), filas);
  }

  // ------------------------------------------------------------------------------- validación

  @Override
  public List<FilaValidada> validar(
      List<FilaCruda> filas, Contexto contexto, Decisiones decisiones) {
    int anio = anioDeLaCarga(filas);
    List<EntradaValorada> conPrecio = conPrecio(filas, contexto);
    List<FilaValidada> resultado = new ArrayList<>();
    LocalDate anterior = null;
    for (FilaCruda fila : filas) {
      Revision revision = new Revision(fila, decisiones);
      LocalDate fecha = validarFila(fila, revision, contexto, anio, anterior, conPrecio);
      if (fecha != null) {
        anterior = fecha;
      }
      resultado.add(revision.cerrar());
    }
    return resultado;
  }

  /** El año de la mayoría de las fechas: con él se corrigen las mal capturadas. */
  static int anioDeLaCarga(List<FilaCruda> filas) {
    Map<Integer, Integer> cuenta = new HashMap<>();
    for (FilaCruda fila : filas) {
      Comun.fecha(fila.valor("B")).ifPresent(f -> cuenta.merge(f.getYear(), 1, Integer::sum));
    }
    return cuenta.entrySet().stream()
        .max(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey)
        .orElse(LocalDate.now().getYear());
  }

  /** Las entradas que traen precio, para estimar el de las que no. */
  private static List<EntradaValorada> conPrecio(List<FilaCruda> filas, Contexto contexto) {
    List<EntradaValorada> lista = new ArrayList<>();
    Map<String, Long> materiales = new HashMap<>();
    for (Contexto.Material m : contexto.materiales()) {
      materiales.put(m.nombreNormal(), m.id());
    }
    for (FilaCruda fila : filas) {
      String normal = Normal.texto(fila.valor("F"));
      Long material = contexto.alias("material", normal).orElseGet(() -> materiales.get(normal));
      BigDecimal cantidad = Resolutor.numeroExcel(fila, "D");
      BigDecimal precio = Resolutor.numeroExcel(fila, "G");
      Optional<LocalDate> fecha = Comun.fecha(fila.valor("B"));
      if (material != null
          && cantidad != null
          && cantidad.signum() > 0
          && precio != null
          && precio.signum() > 0
          && fecha.isPresent()) {
        BigDecimal descuento =
            Optional.ofNullable(Resolutor.numeroExcel(fila, "I")).orElse(BigDecimal.ZERO);
        lista.add(
            new EntradaValorada(
                material, fecha.get(), cantidad, cantidad.multiply(precio).subtract(descuento)));
      }
    }
    return lista;
  }

  private static LocalDate validarFila(
      FilaCruda fila,
      Revision revision,
      Contexto contexto,
      int anio,
      LocalDate anterior,
      List<EntradaValorada> conPrecio) {
    BigDecimal excel = Resolutor.numeroExcel(fila, "D");
    revision.dato("_material", Normal.texto(fila.valor("F")));
    if (excel != null) {
      revision.dato("_excel", excel.toPlainString());
    }

    LocalDate fecha = fecha(fila, revision, anio, anterior);
    Contexto.Material material = Resolutor.material(fila.valor("F"), revision, contexto);
    Optional<BigDecimal> cantidad = Resolutor.numero(fila, "D", "La cantidad", revision);
    if (cantidad.isEmpty() && fila.valor("D") == null && fila.valor("D=") == null) {
      Optional<Decision> decision =
          revision.pedir(
              "SIN_CANTIDAD",
              Severidad.CUARENTENA,
              "La recepción no dice cuánto se recibió: escribe la cantidad o descártala",
              null,
              EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
      cantidad = decision.flatMap(d -> Comun.decimal(d.valor()));
    }
    if (cantidad.isPresent() && cantidad.get().signum() <= 0) {
      revision.pedir(
          "CANTIDAD_NO_POSITIVA",
          Severidad.CUARENTENA,
          "Una recepción de " + cantidad.get().toPlainString() + ": escribe la cantidad correcta",
          null,
          EnumSet.of(Opcion.DESCARTAR));
      cantidad = Optional.empty();
    }
    if (material == null || fecha == null || cantidad.isEmpty()) {
      return fecha;
    }
    revision
        .dato("materialId", material.id())
        .dato("fecha", fecha)
        .dato("cantidad", cantidad.get().toPlainString());

    // Proveedor y tasa de IVA
    Long proveedorId = null;
    if (fila.valor("M") != null) {
      Contexto.Proveedor proveedor = Resolutor.proveedor(fila.valor("M"), revision, contexto);
      if (proveedor == null) {
        return fecha;
      }
      proveedorId = proveedor.id();
      revision.dato("proveedorId", proveedorId);
    } else {
      revision.agregar(
          new Incidencia(
              "SIN_PROVEEDOR",
              Severidad.ADVERTENCIA,
              fila.valor("_proveedor_formula") != null
                  ? "El proveedor es una fórmula y no se lee: entra sin proveedor"
                  : "La recepción no dice el proveedor",
              false,
              null,
              Set.of(),
              null));
    }
    tasaIva(fila, revision, contexto, proveedorId);

    // Precio, descuento e importe
    Optional<BigDecimal> precio = Resolutor.numero(fila, "G", "El precio", revision);
    boolean estimado = false;
    if (precio.isEmpty() && fila.valor("G") == null && fila.valor("G=") == null) {
      BigDecimal cercano = Valuacion.precioCercano(conPrecio, material.id(), fecha, VENTANA_PRECIO);
      BigDecimal referencia = contexto.preciosReferencia().get(material.id());
      if (cercano != null) {
        precio = Optional.of(cercano);
        estimado = true;
        revision.agregar(
            Incidencia.correccion(
                "PRECIO_ESTIMADO",
                "Sin precio: se valora con el ponderado de las entradas del material cercanas"
                    + " ($%s sin IVA)".formatted(cercano.setScale(2, RoundingMode.HALF_UP))));
      } else if (referencia != null && referencia.signum() > 0) {
        precio = Optional.of(referencia);
        estimado = true;
        revision.agregar(
            Incidencia.correccion(
                "PRECIO_ESTIMADO",
                "Sin precio ni otras entradas del material: se valora con su precio de referencia"
                    + " ($%s)".formatted(referencia.setScale(2, RoundingMode.HALF_UP))));
      } else {
        Optional<Decision> decision =
            revision.pedir(
                "SIN_PRECIO",
                Severidad.ADVERTENCIA,
                "Sin precio y sin otras entradas del material para estimarlo: entra sin valuar"
                    + " (puedes escribir el precio sin IVA)",
                null,
                EnumSet.of(Opcion.VALOR));
        precio = decision.flatMap(d -> Comun.decimal(d.valor())).filter(p -> p.signum() > 0);
      }
    }
    BigDecimal descuento =
        Resolutor.numero(fila, "I", "El descuento", revision).orElse(BigDecimal.ZERO);
    if (descuento.signum() < 0) {
      descuento = descuento.negate();
      revision.agregar(
          Incidencia.correccion(
              "DESCUENTO_NEGATIVO", "El descuento venía negativo: se toma positivo"));
    }
    revision.dato("descuento", descuento.toPlainString());
    if (precio.isPresent()) {
      BigDecimal importe =
          cantidad
              .get()
              .multiply(precio.get())
              .subtract(descuento)
              .setScale(4, RoundingMode.HALF_UP);
      if (importe.signum() < 0) {
        revision.pedir(
            "DESCUENTO_MAYOR",
            Severidad.CUARENTENA,
            "El descuento (%s) es mayor que el subtotal: corrígelo en el Excel o descarta el renglón"
                .formatted(descuento.toPlainString()),
            null,
            EnumSet.of(Opcion.DESCARTAR));
        return fecha;
      }
      revision
          .dato("precio", precio.get().setScale(6, RoundingMode.HALF_UP).toPlainString())
          .dato("importe", importe.toPlainString())
          .dato("estimado", estimado ? "1" : null);
    }

    // Quién recibe (no es obligatorio)
    List<Long> recibe =
        Resolutor.personas(
            fila.valor("O"), Escritura.APODO, false, "RECIBE_SIN_LIGAR", revision, contexto);
    if (recibe != null && !recibe.isEmpty()) {
      revision.dato("recibeId", recibe.getFirst());
    }
    return fecha;
  }

  private static LocalDate fecha(FilaCruda fila, Revision revision, int anio, LocalDate anterior) {
    Optional<LocalDate> leida = Comun.fecha(fila.valor("B"));
    if (leida.isEmpty()) {
      Optional<Decision> decision =
          revision.pedir(
              "SIN_FECHA",
              Severidad.CUARENTENA,
              "La recepción no tiene fecha%s: escribe la fecha (dd/mm/aaaa) o descártala"
                  .formatted(anterior == null ? "" : " (la anterior es del " + anterior + ")"),
              null,
              EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
      return decision.flatMap(d -> Comun.fecha(d.valor())).orElse(null);
    }
    LocalDate fecha = leida.get();
    if (fecha.getYear() == anio) {
      return fecha;
    }
    Integer semana = semana(fila.valor("A"));
    LocalDate conAnio = conAnio(fecha, anio);
    if (semana != null
        && conAnio != null
        && conAnio.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) == semana) {
      revision.agregar(
          Incidencia.correccion(
              "ANIO_CORREGIDO",
              "%s está en la semana %d de %d: se corrige el año".formatted(fecha, semana, anio)));
      return conAnio;
    }
    Optional<Decision> decision =
        revision.pedir(
            "FECHA_FUERA_DE_RANGO",
            Severidad.CUARENTENA,
            "La fecha %s no es de %d como las demás: escribe la fecha correcta (dd/mm/aaaa)"
                .formatted(fecha, anio),
            null,
            EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
    return decision.flatMap(d -> Comun.fecha(d.valor())).orElse(null);
  }

  /** "Semana 32" → 32. */
  static Integer semana(String texto) {
    if (texto == null) {
      return null;
    }
    Matcher m = SEMANA.matcher(texto);
    return m.find() ? Integer.valueOf(m.group(1)) : null;
  }

  private static LocalDate conAnio(LocalDate fecha, int anio) {
    try {
      return fecha.withYear(anio);
    } catch (java.time.DateTimeException e) {
      return null;
    }
  }

  private static void tasaIva(
      FilaCruda fila, Revision revision, Contexto contexto, Long proveedorId) {
    Optional<BigDecimal> capturada = Resolutor.numero(fila, "J", "El IVA", revision);
    BigDecimal tasa;
    if (capturada.isPresent()) {
      tasa = capturada.get();
      if (tasa.compareTo(BigDecimal.ONE) > 0) {
        tasa = tasa.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        revision.agregar(
            Incidencia.correccion(
                "IVA_EN_PORCIENTO", "El IVA venía en porciento: se toma " + tasa));
      }
    } else {
      BigDecimal delProveedor = proveedorId == null ? null : contexto.tasasIva().get(proveedorId);
      tasa = delProveedor != null ? delProveedor : IVA_POR_DEFECTO;
      revision.agregar(
          Incidencia.correccion(
              "IVA_POR_DEFECTO",
              "Sin IVA capturado: se toma %s%%%s"
                  .formatted(
                      tasa.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString(),
                      delProveedor != null ? " del proveedor" : " (tasa por defecto)")));
    }
    if (tasa.signum() < 0 || tasa.compareTo(new BigDecimal("0.16")) > 0) {
      revision.pedir(
          "IVA_INVALIDO",
          Severidad.CUARENTENA,
          "La tasa de IVA " + tasa.toPlainString() + " no es válida (0 a 16%)",
          null,
          EnumSet.of(Opcion.DESCARTAR));
      return;
    }
    revision.dato("tasaIva", tasa.toPlainString());
  }

  // ---------------------------------------------------------------------- registrar y conciliar

  @Override
  void registrar(FilaValidada fila, Contexto contexto, Lote lote) {
    FilaCruda c = fila.fila();
    lote.entradas.add(
        new Entrada(
            fecha(fila, "fecha"),
            texto(c.valor("A"), 60),
            fila.id("materialId"),
            decimal(fila, "cantidad"),
            decimal(fila, "precio"),
            decimal(fila, "descuento"),
            decimal(fila, "importe"),
            decimal(fila, "tasaIva"),
            si(fila, "estimado"),
            fila.id("proveedorId"),
            texto(c.valor("M"), 160),
            texto(c.valor("N"), 60),
            fila.id("recibeId"),
            texto(c.valor("O"), 160),
            texto(c.valor("P"), 500),
            traza(fila)));
  }

  @Override
  void conciliar(FilaValidada fila, Lote lote, Conciliacion conciliacion) {
    FilaCruda c = fila.fila();
    String mes = mes(Comun.fecha(c.valor("B")).orElse(null));
    BigDecimal cantidad = suma(lote.entradas.stream().map(Entrada::cantidad).toList());
    conciliacion.sumar(
        "Cantidad por material y mes",
        fila.dato("_material") + " · " + mes,
        fila.estado(),
        excel(fila, "D", "cantidad"),
        cantidad);
    BigDecimal precioExcel = Resolutor.numeroExcel(c, "G");
    BigDecimal cantidadExcel = Resolutor.numeroExcel(c, "D");
    if (precioExcel != null && cantidadExcel != null) {
      BigDecimal descuento =
          Optional.ofNullable(Resolutor.numeroExcel(c, "I")).orElse(BigDecimal.ZERO).abs();
      BigDecimal importeExcel =
          cantidadExcel.multiply(precioExcel).subtract(descuento).setScale(4, RoundingMode.HALF_UP);
      conciliacion.sumar(
          "Importe sin IVA por proveedor y mes (capturado)",
          (c.valor("M") == null ? "sin proveedor" : Normal.texto(c.valor("M"))) + " · " + mes,
          fila.estado(),
          importeExcel,
          suma(lote.entradas.stream().map(Entrada::importe).filter(i -> i != null).toList()));
    } else {
      conciliacion.sumar(
          "Recepciones sin precio (renglones)",
          "precio estimado o sin valuar",
          fila.estado(),
          BigDecimal.ONE,
          BigDecimal.valueOf(lote.entradas.size()));
    }
  }
}

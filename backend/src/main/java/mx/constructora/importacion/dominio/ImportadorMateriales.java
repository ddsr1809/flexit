package mx.constructora.importacion.dominio;

import static mx.constructora.importacion.dominio.Comun.limpio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import mx.constructora.importacion.dominio.Comun.Empate;
import mx.constructora.importacion.dominio.Modelo.Accion;
import mx.constructora.importacion.dominio.Modelo.Decision;
import mx.constructora.importacion.dominio.Modelo.Decisiones;
import mx.constructora.importacion.dominio.Modelo.FilaCruda;
import mx.constructora.importacion.dominio.Modelo.FilaValidada;
import mx.constructora.importacion.dominio.Modelo.Incidencia;
import mx.constructora.importacion.dominio.Modelo.Lectura;
import mx.constructora.importacion.dominio.Modelo.Opcion;
import mx.constructora.importacion.dominio.Modelo.Revision;
import mx.constructora.importacion.dominio.Modelo.Severidad;
import mx.constructora.importacion.dominio.xlsx.ArchivoInvalido;
import mx.constructora.importacion.dominio.xlsx.LibroXlsx;

/**
 * Materiales desde la hoja Existencias del almacén (E clave, F nombre, I unidad), con el precio de
 * referencia recalculado de Entradas: promedio ponderado, sin IVA y neto de descuento. El precio de
 * Existencias!A es una fórmula (promedio simple con IVA) y no se lee.
 *
 * <p>También busca los materiales que aparecen en Salidas, Semanal, Faltantes y Préstamos pero no
 * en Existencias ("Lija 221", "Tubo ABS 1\""): van a la cuarentena para ligarlos a uno existente o
 * darlos de alta antes de cargar el histórico (H5).
 */
public final class ImportadorMateriales implements Importador {

  static final String EXISTENCIAS = "Existencias";
  static final String ENTRADAS = "Entradas";
  private static final Pattern CLAVE = Pattern.compile("^[A-Z0-9][A-Z0-9-]{0,19}$");
  private static final int NOMBRE_MAXIMO = 200;

  /** Diferencia entre el precio más alto y el más bajo de las entradas que amerita revisar. */
  private static final BigDecimal VARIACION = new BigDecimal("1.5");

  /** Hoja → columnas de material y de unidad (si la unidad se capturó) en los movimientos. */
  private static final Map<String, String[]> MOVIMIENTOS =
      Map.of(
          "Salidas", new String[] {"J", null, "3"},
          "Semanal", new String[] {"N", "L", "3"},
          "Faltante solicitud diaria", new String[] {"E", "D", "3"},
          "Prestamo Herramientas-Material", new String[] {"E", "D", "2"});

  @Override
  public TipoCarga tipo() {
    return TipoCarga.MATERIALES;
  }

  // ------------------------------------------------------------------------------------ leer

  @Override
  public Lectura leer(LibroXlsx libro) {
    String existencias =
        Comun.hoja(libro, EXISTENCIAS)
            .orElseThrow(
                () -> new ArchivoInvalido("El libro no tiene la hoja \"" + EXISTENCIAS + "\""));
    List<String> hojas = new ArrayList<>(List.of(existencias));
    List<FilaCruda> filas = new ArrayList<>();
    Set<String> conocidos = new HashSet<>();
    libro.leer(
        existencias,
        Set.of("E", "F", "I"),
        r -> {
          if (r.numero() == 3) {
            Comun.exigirEncabezado(r, existencias, "E", "Clave");
            Comun.exigirEncabezado(r, existencias, "F", "Artículo");
            Comun.exigirEncabezado(r, existencias, "I", "Unidad");
          }
          if (r.numero() < 5) {
            return true;
          }
          String nombre = limpio(r.capturado("F"));
          if (nombre == null) {
            return true;
          }
          Map<String, String> crudo = new LinkedHashMap<>();
          putSi(crudo, "E", limpio(r.capturado("E")));
          crudo.put("F", nombre);
          putSi(crudo, "I", limpio(r.capturado("I")));
          filas.add(new FilaCruda(filas.size() + 1, existencias, r.numero(), crudo));
          conocidos.add(Normal.texto(nombre));
          return true;
        });
    if (filas.isEmpty()) {
      throw new ArchivoInvalido("La hoja \"" + existencias + "\" no tiene materiales");
    }

    // Precio ponderado sin IVA desde Entradas
    Map<String, Precio> precios = new HashMap<>();
    Optional<String> entradas = Comun.hoja(libro, ENTRADAS);
    if (entradas.isPresent()) {
      hojas.add(entradas.get());
      libro.leer(
          entradas.get(),
          Set.of("D", "F", "G", "I"),
          r -> {
            if (r.numero() == 1) {
              Comun.exigirEncabezado(r, entradas.get(), "D", "Cant");
              Comun.exigirEncabezado(r, entradas.get(), "F", "Artículo");
              Comun.exigirEncabezado(r, entradas.get(), "G", "Precio Unitario");
              return true;
            }
            String nombre = Normal.texto(limpio(r.capturado("F")));
            Optional<BigDecimal> cantidad = Comun.decimal(r.capturado("D"));
            Optional<BigDecimal> unitario = Comun.decimal(r.capturado("G"));
            if (nombre != null
                && cantidad.isPresent()
                && cantidad.get().signum() > 0
                && unitario.isPresent()
                && unitario.get().signum() > 0) {
              BigDecimal descuento = Comun.decimal(r.capturado("I")).orElse(BigDecimal.ZERO);
              precios
                  .computeIfAbsent(nombre, n -> new Precio())
                  .agregar(cantidad.get(), unitario.get(), descuento);
            }
            return true;
          });
    }
    for (FilaCruda fila : filas) {
      Precio precio = precios.get(Normal.texto(fila.valor("F")));
      if (precio != null) {
        fila.crudo().put("_cantidad_entradas", precio.cantidad.toPlainString());
        fila.crudo().put("_importe_entradas", precio.importe.toPlainString());
        fila.crudo().put("_precio_minimo", precio.minimo.toPlainString());
        fila.crudo().put("_precio_maximo", precio.maximo.toPlainString());
        fila.crudo().put("_entradas", Integer.toString(precio.entradas));
      }
    }

    // Materiales que solo aparecen en movimientos
    Map<String, FilaCruda> sueltos = new LinkedHashMap<>();
    for (Map.Entry<String, String[]> movimiento :
        MOVIMIENTOS.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      Optional<String> hoja = Comun.hoja(libro, movimiento.getKey());
      if (hoja.isEmpty()) {
        continue;
      }
      hojas.add(hoja.get());
      String columna = movimiento.getValue()[0];
      String columnaUnidad = movimiento.getValue()[1];
      int desde = Integer.parseInt(movimiento.getValue()[2]);
      Set<String> columnas =
          columnaUnidad == null ? Set.of(columna) : Set.of(columna, columnaUnidad);
      libro.leer(
          hoja.get(),
          columnas,
          r -> {
            if (r.numero() < desde) {
              return true;
            }
            String nombre = limpio(r.capturado(columna));
            String normal = Normal.texto(nombre);
            if (normal == null || conocidos.contains(normal)) {
              return true;
            }
            FilaCruda fila = sueltos.get(normal);
            if (fila == null) {
              Map<String, String> crudo = new LinkedHashMap<>();
              crudo.put("texto", nombre);
              crudo.put("_celdas", columna + r.numero());
              crudo.put("_apariciones", "0");
              fila = new FilaCruda(0, hoja.get(), r.numero(), crudo);
              sueltos.put(normal, fila);
            } else if (fila.crudo().get("_celdas").split(",").length < 5) {
              fila.crudo().put("_celdas", fila.crudo().get("_celdas") + "," + columna + r.numero());
            }
            fila.crudo()
                .put(
                    "_apariciones",
                    Integer.toString(Integer.parseInt(fila.crudo().get("_apariciones")) + 1));
            String unidad = columnaUnidad == null ? null : limpio(r.capturado(columnaUnidad));
            if (unidad != null && !fila.crudo().containsKey("unidad")) {
              fila.crudo().put("unidad", unidad);
            }
            return true;
          });
    }
    for (FilaCruda suelto : sueltos.values()) {
      filas.add(new FilaCruda(filas.size() + 1, suelto.hoja(), suelto.filaExcel(), suelto.crudo()));
    }
    return new Lectura(hojas, filas);
  }

  /** Acumula cantidad e importe sin IVA de las entradas de un material. */
  private static final class Precio {
    BigDecimal cantidad = BigDecimal.ZERO;
    BigDecimal importe = BigDecimal.ZERO;
    BigDecimal minimo;
    BigDecimal maximo;
    int entradas;

    void agregar(BigDecimal cant, BigDecimal unitario, BigDecimal descuento) {
      cantidad = cantidad.add(cant);
      importe = importe.add(cant.multiply(unitario)).subtract(descuento);
      minimo = minimo == null || unitario.compareTo(minimo) < 0 ? unitario : minimo;
      maximo = maximo == null || unitario.compareTo(maximo) > 0 ? unitario : maximo;
      entradas++;
    }
  }

  private static void putSi(Map<String, String> mapa, String clave, String valor) {
    if (valor != null) {
      mapa.put(clave, valor);
    }
  }

  // ------------------------------------------------------------------------------- validación

  @Override
  public List<FilaValidada> validar(
      List<FilaCruda> filas, Contexto contexto, Decisiones decisiones) {
    Map<String, Integer> nombres = new HashMap<>();
    Map<String, Integer> claves = new HashMap<>();
    List<FilaValidada> resultado = new ArrayList<>();
    for (FilaCruda fila : filas) {
      Revision revision = new Revision(fila, decisiones);
      if (fila.crudo().containsKey("F")) {
        deExistencias(fila, revision, contexto, nombres, claves);
      } else {
        deMovimientos(fila, revision, contexto);
      }
      resultado.add(revision.cerrar());
    }
    return resultado;
  }

  private static void deExistencias(
      FilaCruda fila,
      Revision revision,
      Contexto contexto,
      Map<String, Integer> nombres,
      Map<String, Integer> claves) {
    String nombre = fila.valor("F");
    String normal = Normal.texto(nombre);
    revision.dato("nombre", nombre);
    if (nombre.length() > NOMBRE_MAXIMO) {
      revision.pedir(
          "NOMBRE_LARGO",
          Severidad.BLOQUEO,
          "El nombre pasa de 200 letras",
          null,
          EnumSet.of(Opcion.DESCARTAR));
      return;
    }

    // ¿Ya existe?
    Empate<Contexto.Material> empate =
        Comun.empatar(
            "material",
            normal,
            contexto,
            contexto.materiales(),
            Contexto.Material::id,
            Contexto.Material::nombreNormal);
    if (empate.exacto() != null) {
      revision
          .dato("materialId", empate.exacto().id())
          .accion(Accion.EXISTE)
          .agregar(
              Incidencia.info(
                  "YA_EXISTE",
                  "Ya existe como " + empate.exacto().clave() + " " + empate.exacto().nombre()));
      return;
    }
    Integer repetido = nombres.putIfAbsent(normal, fila.numero());
    if (repetido != null) {
      revision.pedir(
          "DUPLICADO_EN_HOJA",
          Severidad.CUARENTENA,
          "El mismo material ya viene en el renglón " + repetido,
          null,
          EnumSet.of(Opcion.DESCARTAR));
      return;
    }

    // Clave
    String clave = clave(fila.valor("E"), revision);
    if (clave != null) {
      String claveFinal = clave;
      Optional<Contexto.Material> ocupada =
          contexto.materiales().stream().filter(m -> m.clave().equals(claveFinal)).findFirst();
      Integer enCarga = claves.putIfAbsent(clave, fila.numero());
      if (ocupada.isPresent() || enCarga != null) {
        Optional<Decision> decision =
            revision.pedir(
                "CLAVE_OCUPADA",
                Severidad.CUARENTENA,
                ocupada
                        .map(m -> "La clave " + claveFinal + " ya es de \"" + m.nombre() + "\"")
                        .orElse("La clave " + claveFinal + " se repite en el renglón " + enCarga)
                    + ". ¿Se le asigna otra clave?",
                null,
                EnumSet.of(Opcion.CREAR, Opcion.DESCARTAR));
        if (decision.isEmpty()) {
          return;
        }
        clave = null;
      }
    }
    if (clave == null) {
      revision.agregar(Incidencia.info("CLAVE_ASIGNADA", "Se le asigna la siguiente clave"));
    }
    revision.dato("clave", clave);

    // Unidad
    if (!unidad(fila.valor("I"), revision, contexto)) {
      return;
    }

    // Precio
    precio(fila, revision);
  }

  private static String clave(String texto, Revision revision) {
    String clave = limpio(texto);
    if (clave == null) {
      return null;
    }
    clave = clave.toUpperCase();
    if (clave.matches("^[0-9]{1,2}$")) {
      String conCeros = "%03d".formatted(Integer.parseInt(clave));
      revision.agregar(
          Incidencia.correccion("CLAVE_CON_CEROS", clave + " se guarda como " + conCeros));
      clave = conCeros;
    }
    if (!CLAVE.matcher(clave).matches()) {
      revision.agregar(
          Incidencia.correccion(
              "CLAVE_INVALIDA", "\"" + texto + "\" no sirve como clave: se asigna otra"));
      return null;
    }
    return clave;
  }

  /** Liga la unidad del Excel con una del sistema; {@code false} si queda en cuarentena. */
  private static boolean unidad(String texto, Revision revision, Contexto contexto) {
    String normal = Normal.texto(texto);
    if (normal == null) {
      Optional<Decision> decision =
          revision.pedir(
              "SIN_UNIDAD",
              Severidad.CUARENTENA,
              "El material no tiene unidad de medida: elige una",
              null,
              EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
      if (decision.isEmpty()) {
        return false;
      }
      if (!unidadPorClave(decision.get().valor(), revision, contexto)) {
        unidadInexistente(revision);
        return false;
      }
      return true;
    }
    Optional<Long> alias = contexto.alias("unidad_medida", normal);
    if (alias.isPresent()
        && contexto.unidadesMedida().stream().anyMatch(u -> u.id() == alias.get())) {
      revision.dato("unidadId", alias.get());
      return true;
    }
    Optional<String> clave = UnidadesExcel.clave(texto);
    Optional<Contexto.UnidadMedida> unidad =
        clave.flatMap(
            c -> contexto.unidadesMedida().stream().filter(u -> u.clave().equals(c)).findFirst());
    if (unidad.isEmpty()) {
      revision.pedir(
          "UNIDAD_DESCONOCIDA",
          Severidad.CUARENTENA,
          "\"" + texto + "\" no es una unidad conocida: elige cuál es",
          Comun.sugerencia("unidad_medida", normal, null, null, null),
          EnumSet.of(Opcion.ELEGIR, Opcion.DESCARTAR));
      return false;
    }
    revision.dato("unidadId", unidad.get().id());
    if (!normal.equals(Normal.texto(unidad.get().nombre()))
        && !normal.equals(unidad.get().clave())) {
      revision.agregar(
          Incidencia.correccion(
              "UNIDAD_NORMALIZADA", "\"" + texto + "\" se guarda como " + unidad.get().clave()));
    }
    return true;
  }

  private static void unidadInexistente(Revision revision) {
    revision.pedir(
        "UNIDAD_ELEGIDA_INEXISTENTE",
        Severidad.CUARENTENA,
        "La unidad elegida no existe: elige otra clave (PZA, KG, L…)",
        null,
        EnumSet.of(Opcion.DESCARTAR));
  }

  private static boolean unidadPorClave(String clave, Revision revision, Contexto contexto) {
    Optional<Contexto.UnidadMedida> unidad =
        contexto.unidadesMedida().stream()
            .filter(u -> u.clave().equalsIgnoreCase(limpio(clave) == null ? "" : limpio(clave)))
            .findFirst();
    unidad.ifPresent(u -> revision.dato("unidadId", u.id()));
    return unidad.isPresent();
  }

  private static void precio(FilaCruda fila, Revision revision) {
    String cantidad = fila.valor("_cantidad_entradas");
    if (cantidad == null) {
      Optional<Decision> decision =
          revision.pedir(
              "SIN_PRECIO",
              Severidad.ADVERTENCIA,
              "No tiene entradas con precio: entra sin precio de referencia (se puede capturar"
                  + " aquí)",
              null,
              EnumSet.of(Opcion.VALOR));
      decision
          .flatMap(d -> Comun.decimal(d.valor()))
          .filter(p -> p.signum() > 0)
          .ifPresent(
              p -> revision.dato("precio", p.setScale(6, RoundingMode.HALF_UP).toPlainString()));
      return;
    }
    BigDecimal suma = new BigDecimal(cantidad);
    BigDecimal importe = new BigDecimal(fila.valor("_importe_entradas"));
    BigDecimal ponderado = importe.divide(suma, 6, RoundingMode.HALF_UP);
    if (ponderado.signum() <= 0) {
      revision.agregar(
          new Incidencia(
              "PRECIO_INVALIDO",
              Severidad.ADVERTENCIA,
              "Los descuentos dejan el precio en cero o negativo: entra sin precio",
              false,
              null,
              Set.of(),
              null));
      return;
    }
    revision
        .dato("precio", ponderado.toPlainString())
        .agregar(
            Incidencia.info(
                "PRECIO_PONDERADO",
                "Precio ponderado sin IVA de %s entradas: %s"
                    .formatted(
                        fila.valor("_entradas"), ponderado.setScale(2, RoundingMode.HALF_UP))));
    BigDecimal minimo = new BigDecimal(fila.valor("_precio_minimo"));
    BigDecimal maximo = new BigDecimal(fila.valor("_precio_maximo"));
    if (maximo.compareTo(minimo.multiply(VARIACION)) > 0) {
      revision.agregar(
          new Incidencia(
              "PRECIO_VARIABLE",
              Severidad.ADVERTENCIA,
              "El precio de las entradas va de %s a %s: revisa si alguna se capturó por caja y no por pieza"
                  .formatted(minimo.toPlainString(), maximo.toPlainString()),
              false,
              null,
              Set.of(),
              null));
    }
  }

  private static void deMovimientos(FilaCruda fila, Revision revision, Contexto contexto) {
    String texto = fila.valor("texto");
    String normal = Normal.texto(texto);
    revision.dato("nombre", texto);
    Empate<Contexto.Material> empate =
        Comun.empatar(
            "material",
            normal,
            contexto,
            contexto.materiales(),
            Contexto.Material::id,
            Contexto.Material::nombreNormal);
    if (empate.exacto() != null) {
      revision
          .dato("materialId", empate.exacto().id())
          .accion(Accion.EXISTE)
          .agregar(Incidencia.info("YA_EXISTE", "Es el material " + empate.exacto().clave()));
      return;
    }
    String detalle =
        "\"%s\" aparece %s veces en %s (%s) y no está en Existencias"
            .formatted(texto, fila.valor("_apariciones"), fila.hoja(), fila.valor("_celdas"));
    Map<String, Object> sugerencia = Comun.sugerencia("material", normal, null, null, null);
    if (empate.parecido() != null) {
      sugerencia =
          Comun.sugerencia(
              "material",
              normal,
              empate.parecido().id(),
              empate.parecido().clave() + " " + empate.parecido().nombre(),
              empate.puntos());
      detalle += ". Se parece a " + empate.parecido().nombre();
    }
    if (fila.valor("unidad") != null) {
      sugerencia.put("unidad", fila.valor("unidad"));
    }
    Optional<Decision> decision =
        revision.pedir(
            "MATERIAL_SOLO_EN_MOVIMIENTOS",
            Severidad.CUARENTENA,
            detalle + ". Lígalo a uno existente o dalo de alta con su unidad",
            sugerencia,
            EnumSet.of(Opcion.ACEPTAR, Opcion.ELEGIR, Opcion.VALOR, Opcion.DESCARTAR));
    if (decision.isEmpty()) {
      return;
    }
    // VALOR: dar de alta con la unidad elegida (clave de la unidad: PZA, KG…).
    if (!unidadPorClave(decision.get().valor(), revision, contexto)) {
      unidadInexistente(revision);
      return;
    }
    revision.dato("clave", null);
    if (texto.length() > NOMBRE_MAXIMO) {
      revision.pedir(
          "NOMBRE_LARGO",
          Severidad.BLOQUEO,
          "El nombre pasa de 200 letras",
          null,
          EnumSet.of(Opcion.DESCARTAR));
    }
  }

  // ---------------------------------------------------------------------------------- aplicar

  @Override
  public void aplicar(List<FilaValidada> filas, Contexto contexto, Destino destino) {
    for (FilaValidada fila : filas) {
      if (fila.accion() != Accion.CREAR) {
        continue;
      }
      destino.crearMaterial(
          fila.dato("clave"),
          fila.dato("nombre"),
          fila.id("unidadId"),
          fila.dato("precio") == null ? null : new BigDecimal(fila.dato("precio")));
    }
  }
}

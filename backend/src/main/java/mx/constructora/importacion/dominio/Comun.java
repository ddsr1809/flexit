package mx.constructora.importacion.dominio;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.constructora.importacion.dominio.Modelo.Opcion;
import mx.constructora.importacion.dominio.xlsx.ArchivoInvalido;
import mx.constructora.importacion.dominio.xlsx.LibroXlsx;
import mx.constructora.importacion.dominio.xlsx.Renglon;

/** Piezas que comparten los importadores: limpieza, fechas, números, perfiles y empates. */
public final class Comun {

  /** Versión de las reglas: cambia cuando cambia el resultado de alguna regla. */
  public static final String VERSION_REGLAS = "h3.1";

  /** Opciones para ligar un nombre a un registro. */
  public static final Set<Opcion> LIGAR =
      EnumSet.of(Opcion.ACEPTAR, Opcion.ELEGIR, Opcion.CREAR, Opcion.DESCARTAR);

  /** Opciones cuando no hay registro parecido y crear no tiene sentido. */
  public static final Set<Opcion> ELEGIR_O_DESCARTAR = EnumSet.of(Opcion.ELEGIR, Opcion.DESCARTAR);

  private static final Pattern FECHA_DMA =
      Pattern.compile("^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})$");

  private Comun() {}

  /** Texto capturado limpio: espacio duro de Excel como espacio, espacios simples; o null. */
  public static String limpio(String texto) {
    if (texto == null) {
      return null;
    }
    String limpio = texto.replace(' ', ' ').trim().replaceAll("\\s+", " ");
    return limpio.isEmpty() ? null : limpio;
  }

  /** Fecha ISO de una celda de fecha ({@code 2026-06-22}, con o sin hora) o {@code dd/mm/aaaa}. */
  public static Optional<LocalDate> fecha(String texto) {
    String limpio = limpio(texto);
    if (limpio == null) {
      return Optional.empty();
    }
    try {
      if (limpio.length() >= 10 && limpio.charAt(4) == '-') {
        return Optional.of(LocalDate.parse(limpio.substring(0, 10)));
      }
      Matcher dma = FECHA_DMA.matcher(limpio);
      if (dma.matches()) {
        return Optional.of(
            LocalDate.of(
                Integer.parseInt(dma.group(3)),
                Integer.parseInt(dma.group(2)),
                Integer.parseInt(dma.group(1))));
      }
    } catch (DateTimeException | NumberFormatException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  /** Número capturado; acepta {@code $1,250.50}. Vacío si no es número. */
  public static Optional<BigDecimal> decimal(String texto) {
    String limpio = limpio(texto);
    if (limpio == null) {
      return Optional.empty();
    }
    String numero = limpio.replace("$", "").replace(",", "").replace(" ", "");
    try {
      return Optional.of(new BigDecimal(numero));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /** Sugerencia para la cuarentena. Con {@code entidad}, aceptarla guarda un alias. */
  public static Map<String, Object> sugerencia(
      String entidad, String texto, Long destinoId, String destino, Integer parecido) {
    Map<String, Object> sugerencia = new LinkedHashMap<>();
    if (entidad != null) {
      sugerencia.put("entidad", entidad);
    }
    if (texto != null) {
      sugerencia.put("texto", texto);
    }
    if (destinoId != null) {
      sugerencia.put("destinoId", destinoId);
    }
    if (destino != null) {
      sugerencia.put("destino", destino);
    }
    if (parecido != null) {
      sugerencia.put("parecido", Similitud.comoDecimal(parecido));
    }
    return sugerencia;
  }

  // ---------------------------------------------------------------------------------- perfiles

  /** La hoja con ese nombre, sin importar mayúsculas ni espacios de más. */
  public static Optional<String> hoja(LibroXlsx libro, String nombre) {
    String buscado = Normal.texto(nombre);
    return libro.hojas().stream().filter(h -> buscado.equals(Normal.texto(h))).findFirst();
  }

  /** Hojas cuyo nombre normalizado empieza con {@code prefijo}. */
  public static List<String> hojasQueEmpiezan(LibroXlsx libro, String prefijo) {
    String buscado = Normal.texto(prefijo);
    return libro.hojas().stream()
        .filter(h -> Normal.texto(h) != null && Normal.texto(h).startsWith(buscado))
        .toList();
  }

  /** Renglones de la hoja entre dos filas (incluidas), solo con esas columnas. */
  public static List<Renglon> renglones(
      LibroXlsx libro, String hoja, Set<String> columnas, int desde, int hasta) {
    List<Renglon> renglones = new ArrayList<>();
    libro.leer(
        hoja,
        columnas,
        r -> {
          if (r.numero() >= desde && r.numero() <= hasta) {
            renglones.add(r);
          }
          return r.numero() < hasta;
        });
    return renglones;
  }

  /**
   * Revisa que la celda tenga el encabezado esperado (normalizado, que empiece igual). Así se
   * detecta al subir que el formato del Excel cambió.
   *
   * @throws ArchivoInvalido con la hoja, la celda y lo que se esperaba
   */
  public static void exigirEncabezado(
      Renglon renglon, String hoja, String columna, String esperado) {
    String valor = renglon == null ? null : Normal.texto(renglon.capturado(columna));
    if (valor == null || !valor.startsWith(Normal.texto(esperado))) {
      throw new ArchivoInvalido(
          "La hoja \"%s\" no tiene el formato esperado: en %s%s debería decir \"%s\"%s"
              .formatted(
                  hoja,
                  columna,
                  renglon == null ? "" : renglon.numero(),
                  esperado,
                  valor == null ? "" : " y dice \"" + renglon.capturado(columna) + "\""));
    }
  }

  // ----------------------------------------------------------------------------------- empates

  /** Resultado de buscar un nombre del Excel en un catálogo. */
  public record Empate<T>(T exacto, T parecido, int puntos) {
    static <T> Empate<T> ninguno() {
      return new Empate<>(null, null, 0);
    }
  }

  /**
   * Busca {@code normal} en {@code lista}: primero el alias, después el nombre normalizado igual y
   * por último el más parecido (si pasa el umbral o lo contiene).
   */
  public static <T> Empate<T> empatar(
      String entidad,
      String normal,
      Contexto contexto,
      List<T> lista,
      Function<T, Long> id,
      Function<T, String> nombreNormal) {
    if (normal == null) {
      return Empate.ninguno();
    }
    Optional<Long> alias = contexto.alias(entidad, normal);
    if (alias.isPresent()) {
      for (T elemento : lista) {
        if (id.apply(elemento).equals(alias.get())) {
          return new Empate<>(elemento, null, 1000);
        }
      }
    }
    for (T elemento : lista) {
      if (normal.equals(nombreNormal.apply(elemento))) {
        return new Empate<>(elemento, null, 1000);
      }
    }
    return parecido(normal, lista, nombreNormal);
  }

  /** El elemento más parecido a {@code normal} que pasa el umbral o contiene sus palabras. */
  public static <T> Empate<T> parecido(
      String normal, List<T> lista, Function<T, String> nombreNormal) {
    T mejor = null;
    int puntos = 0;
    for (T elemento : lista) {
      String otro = nombreNormal.apply(elemento);
      int p = Similitud.parecido(normal, otro);
      if (p < Similitud.UMBRAL
          && (Similitud.contenido(normal, otro) || Similitud.contenido(otro, normal))) {
        p = Math.max(p, Similitud.UMBRAL);
      }
      if (p > puntos) {
        puntos = p;
        mejor = elemento;
      }
    }
    if (mejor == null || puntos < Similitud.UMBRAL) {
      return Empate.ninguno();
    }
    return new Empate<>(null, mejor, puntos);
  }

  /** Los que tienen una palabra igual a la única palabra de {@code apodo} ("BLADIMIR"). */
  public static <T> List<T> porPalabra(
      String apodo, List<T> lista, Function<T, String> nombreNormal) {
    List<T> encontrados = new ArrayList<>();
    for (T elemento : lista) {
      String otro = nombreNormal.apply(elemento);
      if (otro != null && List.of(otro.split(" ")).contains(apodo)) {
        encontrados.add(elemento);
      }
    }
    encontrados.sort(Comparator.comparing(nombreNormal));
    return encontrados;
  }
}

package mx.constructora.importacion.dominio;

import static mx.constructora.importacion.dominio.Comun.limpio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import mx.constructora.importacion.dominio.xlsx.ArchivoInvalido;
import mx.constructora.importacion.dominio.xlsx.LibroXlsx;
import mx.constructora.importacion.dominio.xlsx.Renglon;

/**
 * Personas: un renglón por nombre distinto, aunque aparezca en cientos de celdas. Tres perfiles:
 *
 * <ul>
 *   <li><b>SUA</b>: nombres completos de IMSS, SUA ANTERIOR, BD y ASISTENCIA. Si no se parecen a
 *       nadie, se dan de alta.
 *   <li><b>Destajo</b>: la columna Trabajador de las hojas de pagos ("Bladimir" de 13 formas).
 *   <li><b>Almacén</b>: quién solicita, a quién se entrega y quién recibe.
 * </ul>
 *
 * En destajo y almacén casi siempre es un apodo: nunca se da de alta solo. Se sugiere la persona
 * del SUA que tiene esas palabras y una persona decide; su decisión queda como alias.
 */
public final class ImportadorPersonas implements Importador {

  /** De dónde salen los nombres. */
  public enum Perfil {
    SUA,
    DESTAJO,
    ALMACEN
  }

  private static final Pattern PARENTESIS = Pattern.compile("\\([^)]*\\)?");
  private static final Pattern SEPARADORES = Pattern.compile("\\s*(?:,|/|;|\\s+Y\\s+)\\s*");
  private static final Pattern SIN_LETRAS = Pattern.compile("^[^A-Z]*$");
  private static final Pattern CON_NUMEROS = Pattern.compile(".*[0-9].*");

  /** Almacén: hoja → columnas de nombre y primera fila de datos. */
  private static final List<String[]> ALMACEN =
      List.of(
          new String[] {"Salidas", "K", "3"},
          new String[] {"Salidas", "M", "3"},
          new String[] {"Entradas", "O", "2"},
          new String[] {"Semanal", "F", "3"},
          new String[] {"Faltante solicitud diaria", "F", "3"},
          new String[] {"Prestamo Herramientas-Material", "H", "2"},
          new String[] {"Prestamo Herramientas-Material", "J", "2"});

  /** SUA: prefijo del nombre de la hoja (el mes cambia: "IMSS OCTUBRE"). */
  private static final List<String> SUA = List.of("IMSS", "SUA ANTERIOR", "BD", "ASISTENCIA");

  private final Perfil perfil;

  public ImportadorPersonas(Perfil perfil) {
    this.perfil = perfil;
  }

  @Override
  public TipoCarga tipo() {
    return switch (perfil) {
      case SUA -> TipoCarga.PERSONAS_SUA;
      case DESTAJO -> TipoCarga.PERSONAS_DESTAJO;
      case ALMACEN -> TipoCarga.PERSONAS_ALMACEN;
    };
  }

  // ------------------------------------------------------------------------------------ leer

  @Override
  public Lectura leer(LibroXlsx libro) {
    Acumulador acumulador = new Acumulador(perfil != Perfil.SUA);
    List<String> hojas = new ArrayList<>();
    switch (perfil) {
      case SUA -> leerSua(libro, hojas, acumulador);
      case DESTAJO -> leerDestajo(libro, hojas, acumulador);
      case ALMACEN -> leerAlmacen(libro, hojas, acumulador);
    }
    if (hojas.isEmpty()) {
      throw new ArchivoInvalido(
          switch (perfil) {
            case SUA -> "El libro no tiene las hojas del SUA (IMSS, SUA ANTERIOR, BD, ASISTENCIA)";
            case DESTAJO -> "El libro no tiene hojas con columna Trabajador en la fila 5";
            case ALMACEN -> "El libro no tiene las hojas de almacén (Salidas, Entradas, Semanal…)";
          });
    }
    return new Lectura(hojas, acumulador.filas());
  }

  private static void leerSua(LibroXlsx libro, List<String> hojas, Acumulador acumulador) {
    for (String prefijo : SUA) {
      for (String hoja : Comun.hojasQueEmpiezan(libro, prefijo)) {
        String[] columna = {null};
        libro.leer(
            hoja,
            r -> {
              if (r.numero() == 1) {
                columna[0] = columnaNombre(r);
                if (columna[0] == null) {
                  throw new ArchivoInvalido(
                      "La hoja \"" + hoja + "\" no tiene la columna NOMBRE en la fila 1");
                }
                return true;
              }
              acumulador.agregar(hoja, columna[0], r);
              return true;
            });
        hojas.add(hoja);
      }
    }
  }

  private static String columnaNombre(Renglon encabezado) {
    return encabezado.celdas().values().stream()
        .filter(c -> c.capturado() != null)
        .filter(c -> Normal.texto(c.capturado()).startsWith("NOMBRE"))
        .map(c -> c.columna())
        .sorted((a, b) -> LibroXlsx.indiceColumna(a) - LibroXlsx.indiceColumna(b))
        .findFirst()
        .orElse(null);
  }

  private static void leerDestajo(LibroXlsx libro, List<String> hojas, Acumulador acumulador) {
    for (String hoja : libro.hojas()) {
      List<Renglon> fila5 = Comun.renglones(libro, hoja, null, 5, 5);
      if (fila5.isEmpty()) {
        continue;
      }
      Set<String> columnas = new LinkedHashSet<>();
      fila5.getFirst().celdas().values().stream()
          .filter(c -> "TRABAJADOR".equals(Normal.texto(c.capturado())))
          .forEach(c -> columnas.add(c.columna()));
      if (columnas.isEmpty()) {
        continue;
      }
      hojas.add(hoja);
      libro.leer(
          hoja,
          columnas,
          r -> {
            if (r.numero() >= 7) {
              for (String columna : columnas) {
                acumulador.agregar(hoja, columna, r);
              }
            }
            return true;
          });
    }
  }

  private static void leerAlmacen(LibroXlsx libro, List<String> hojas, Acumulador acumulador) {
    for (String[] origen : ALMACEN) {
      Optional<String> hoja = Comun.hoja(libro, origen[0]);
      if (hoja.isEmpty()) {
        continue;
      }
      if (!hojas.contains(hoja.get())) {
        hojas.add(hoja.get());
      }
      int desde = Integer.parseInt(origen[2]);
      libro.leer(
          hoja.get(),
          Set.of(origen[1]),
          r -> {
            if (r.numero() >= desde) {
              acumulador.agregar(hoja.get(), origen[1], r);
            }
            return true;
          });
    }
  }

  /** Junta las celdas por nombre limpio; los apodos se limpian y se separan. */
  private static final class Acumulador {
    private final boolean apodos;
    private final Map<String, FilaCruda> porNombre = new LinkedHashMap<>();

    Acumulador(boolean apodos) {
      this.apodos = apodos;
    }

    void agregar(String hoja, String columna, Renglon r) {
      String original = limpio(r.capturado(columna));
      if (original == null) {
        return;
      }
      List<String> nombres = apodos ? separar(original) : List.of(original);
      for (String nombre : nombres) {
        String normal = apodos ? limpiarApodo(nombre) : Normal.texto(nombre);
        if (normal == null) {
          normal = Normal.texto(nombre);
        }
        String llave = normal;
        FilaCruda fila =
            porNombre.computeIfAbsent(
                llave,
                n -> {
                  Map<String, String> crudo = new LinkedHashMap<>();
                  crudo.put("texto", original);
                  crudo.put("_celdas", columna + r.numero());
                  crudo.put("_hojas", hoja);
                  crudo.put("_apariciones", "0");
                  if (nombres.size() > 1) {
                    crudo.put("_separado_de", original);
                  }
                  return new FilaCruda(0, hoja, r.numero(), crudo);
                });
        Map<String, String> crudo = fila.crudo();
        crudo.put(
            "_apariciones", Integer.toString(Integer.parseInt(crudo.get("_apariciones")) + 1));
        if (crudo.get("_celdas").split(",").length < 5) {
          crudo.put("_celdas", crudo.get("_celdas") + "," + columna + r.numero());
        }
        if (!Arrays.asList(crudo.get("_hojas").split(", ")).contains(hoja)) {
          crudo.put("_hojas", crudo.get("_hojas") + ", " + hoja);
        }
      }
    }

    List<FilaCruda> filas() {
      List<FilaCruda> filas = new ArrayList<>();
      for (Map.Entry<String, FilaCruda> entrada : porNombre.entrySet()) {
        FilaCruda fila = entrada.getValue();
        Map<String, String> crudo = new LinkedHashMap<>(fila.crudo());
        crudo.put(
            "_celdas",
            String.join(",", new LinkedHashSet<>(Arrays.asList(crudo.get("_celdas").split(",")))));
        crudo.put("nombre", entrada.getKey());
        filas.add(new FilaCruda(filas.size() + 1, fila.hoja(), fila.filaExcel(), crudo));
      }
      return filas;
    }
  }

  /** "ALEXIS, ANGEL" y "ABRAHAM Y GERA" son dos personas. */
  static List<String> separar(String texto) {
    // Primero los paréntesis: "OSCAR S(1,2)" no son dos personas.
    String normal = Normal.texto(PARENTESIS.matcher(Normal.texto(texto)).replaceAll(" "));
    if (normal == null) {
      return List.of(Normal.texto(texto));
    }
    List<String> partes =
        Arrays.stream(SEPARADORES.split(normal))
            .map(String::trim)
            .filter(p -> !p.isEmpty())
            .toList();
    return partes.isEmpty() ? List.of(normal) : partes;
  }

  /**
   * "OSCAR S(1,2)" → "OSCAR S"; "HERIBERTO M." → "HERIBERTO M"; "RAMON ARMENTA RAMON ARMENTA" →
   * "RAMON ARMENTA". Los paréntesis marcan pagos parciales, no son parte del nombre.
   */
  static String limpiarApodo(String texto) {
    String normal = Normal.texto(texto);
    if (normal == null) {
      return null;
    }
    String sinParentesis = PARENTESIS.matcher(normal).replaceAll(" ");
    String palabras = Normal.palabras(sinParentesis);
    if (palabras == null) {
      return null;
    }
    List<String> lista = Arrays.asList(palabras.split(" "));
    int mitad = lista.size() / 2;
    if (lista.size() % 2 == 0
        && lista.size() >= 2
        && lista.subList(0, mitad).equals(lista.subList(mitad, lista.size()))) {
      return String.join(" ", lista.subList(0, mitad));
    }
    return palabras;
  }

  // ------------------------------------------------------------------------------- validación

  @Override
  public List<FilaValidada> validar(
      List<FilaCruda> filas, Contexto contexto, Decisiones decisiones) {
    List<FilaValidada> resultado = new ArrayList<>();
    List<Contexto.Persona> nuevas = new ArrayList<>();
    for (FilaCruda fila : filas) {
      Revision revision = new Revision(fila, decisiones);
      validarFila(fila, revision, contexto, nuevas);
      resultado.add(revision.cerrar());
    }
    return resultado;
  }

  private void validarFila(
      FilaCruda fila, Revision revision, Contexto contexto, List<Contexto.Persona> nuevas) {
    String normal = fila.valor("nombre");
    String texto = fila.valor("texto");
    revision.dato("nombre", perfil == Perfil.SUA ? texto : normal);
    if (fila.valor("_separado_de") != null) {
      revision.agregar(
          Incidencia.correccion(
              "NOMBRES_SEPARADOS", "\"" + fila.valor("_separado_de") + "\" son varias personas"));
    } else if (perfil != Perfil.SUA && !normal.equals(Normal.texto(texto))) {
      revision.agregar(
          Incidencia.correccion("NOMBRE_LIMPIO", "\"" + texto + "\" se lee como " + normal));
    }
    if (SIN_LETRAS.matcher(normal).matches()
        || (perfil != Perfil.SUA && CON_NUMEROS.matcher(normal).matches())) {
      revision.descartarPorRegla("NO_ES_NOMBRE", "\"" + texto + "\" no es un nombre de persona");
      return;
    }

    Empate<Contexto.Persona> empate =
        Comun.empatar(
            "persona",
            normal,
            contexto,
            contexto.personas(),
            Contexto.Persona::id,
            Contexto.Persona::nombreNormal);
    if (empate.exacto() != null) {
      revision
          .dato("personaId", empate.exacto().id())
          .accion(Accion.EXISTE)
          .agregar(Incidencia.info("YA_EXISTE", "Es " + empate.exacto().nombre()));
      return;
    }
    if (perfil == Perfil.SUA) {
      sua(fila, revision, normal, empate, nuevas);
    } else {
      apodo(fila, revision, normal, contexto);
    }
  }

  /** Nombre completo del SUA: se da de alta salvo que se parezca a alguien. */
  private static void sua(
      FilaCruda fila,
      Revision revision,
      String normal,
      Empate<Contexto.Persona> empate,
      List<Contexto.Persona> nuevas) {
    Empate<Contexto.Persona> parecido =
        empate.parecido() != null
            ? empate
            : Comun.parecido(normal, nuevas, Contexto.Persona::nombreNormal);
    if (parecido.parecido() != null) {
      Contexto.Persona otra = parecido.parecido();
      boolean deEstaCarga = otra.id() < 0;
      Map<String, Object> sugerencia =
          deEstaCarga
              ? Comun.sugerencia(null, normal, null, otra.nombre(), parecido.puntos())
              : Comun.sugerencia("persona", normal, otra.id(), otra.nombre(), parecido.puntos());
      if (deEstaCarga) {
        sugerencia.put("numero", Math.toIntExact(-otra.id()));
      }
      Optional<Decision> decision =
          revision.pedir(
              "POSIBLE_DUPLICADO",
              Modelo.Severidad.CUARENTENA,
              "\"%s\" se parece a \"%s\"%s. ¿Es la misma persona?"
                  .formatted(
                      fila.valor("texto"),
                      otra.nombre(),
                      deEstaCarga ? " (renglón " + -otra.id() + ")" : ""),
              sugerencia,
              deEstaCarga
                  ? EnumSet.of(Opcion.ACEPTAR, Opcion.CREAR, Opcion.DESCARTAR)
                  : EnumSet.of(Opcion.ACEPTAR, Opcion.ELEGIR, Opcion.CREAR, Opcion.DESCARTAR));
      if (decision.isEmpty()) {
        return;
      }
      if (decision.get().opcion() == Opcion.ACEPTAR && deEstaCarga) {
        revision.dato("mismaQue", -otra.id());
        return;
      }
    }
    nuevas.add(new Contexto.Persona(-fila.numero(), fila.valor("texto"), normal));
  }

  /** Apodo del destajo o del almacén: nunca se da de alta sin que una persona lo decida. */
  private static void apodo(FilaCruda fila, Revision revision, String normal, Contexto contexto) {
    List<Contexto.Persona> candidatas = candidatas(normal, contexto.personas());
    Map<String, Object> sugerencia = Comun.sugerencia("persona", normal, null, null, null);
    String detalle;
    if (candidatas.size() == 1) {
      Contexto.Persona unica = candidatas.getFirst();
      sugerencia = Comun.sugerencia("persona", normal, unica.id(), unica.nombre(), null);
      detalle =
          "\"%s\" (%s veces) parece ser %s"
              .formatted(fila.valor("texto"), fila.valor("_apariciones"), unica.nombre());
    } else if (candidatas.isEmpty()) {
      detalle =
          "\"%s\" (%s veces) no se parece a nadie del SUA: elige quién es o dalo de alta para revisión"
              .formatted(fila.valor("texto"), fila.valor("_apariciones"));
    } else {
      List<Map<String, Object>> lista = new ArrayList<>();
      for (Contexto.Persona candidata : candidatas.subList(0, Math.min(5, candidatas.size()))) {
        Map<String, Object> opcion = new LinkedHashMap<>();
        opcion.put("id", candidata.id());
        opcion.put("nombre", candidata.nombre());
        lista.add(opcion);
      }
      sugerencia.put("candidatos", lista);
      detalle =
          "\"%s\" (%s veces) puede ser %d personas del SUA: elige quién es"
              .formatted(fila.valor("texto"), fila.valor("_apariciones"), candidatas.size());
    }
    Optional<Decision> decision =
        revision.pedir(
            "PERSONA_SIN_LIGAR",
            Modelo.Severidad.CUARENTENA,
            detalle,
            sugerencia,
            candidatas.size() == 1
                ? EnumSet.of(Opcion.ACEPTAR, Opcion.ELEGIR, Opcion.CREAR, Opcion.DESCARTAR)
                : EnumSet.of(Opcion.ELEGIR, Opcion.CREAR, Opcion.DESCARTAR));
    if (decision.isPresent() && decision.get().opcion() == Opcion.CREAR) {
      revision
          .dato("revisar", "1")
          .dato(
              "nota",
              "Alta desde \"%s\" (%s): falta el nombre completo"
                  .formatted(fila.valor("texto"), fila.valor("_hojas")));
    }
  }

  /** Personas que tienen todas las palabras del apodo ("OSCAR S" → Oscar Sobera …). */
  public static List<Contexto.Persona> candidatas(String apodo, List<Contexto.Persona> personas) {
    List<String> palabras = Arrays.asList(apodo.split(" "));
    List<Contexto.Persona> encontradas = new ArrayList<>();
    for (Contexto.Persona persona : personas) {
      List<String> suyas = new ArrayList<>(Arrays.asList(persona.nombreNormal().split(" ")));
      boolean todas = true;
      for (String palabra : palabras) {
        int indice = -1;
        for (int i = 0; i < suyas.size() && indice < 0; i++) {
          String suya = suyas.get(i);
          if (suya.equals(palabra)
              || (palabra.length() <= 4 && suya.startsWith(palabra))
              || (palabra.length() >= 5 && Similitud.distancia(palabra, suya) <= 1)) {
            indice = i;
          }
        }
        if (indice < 0) {
          todas = false;
          break;
        }
        suyas.remove(indice);
      }
      if (todas) {
        encontradas.add(persona);
      }
    }
    encontradas.sort((a, b) -> a.nombreNormal().compareTo(b.nombreNormal()));
    return encontradas;
  }

  // ---------------------------------------------------------------------------------- aplicar

  @Override
  public void aplicar(List<FilaValidada> filas, Contexto contexto, Destino destino) {
    Map<Integer, Long> creadas = new LinkedHashMap<>();
    for (FilaValidada fila : filas) {
      if (fila.accion() != Accion.CREAR) {
        continue;
      }
      String normal = Normal.texto(fila.dato("nombre"));
      if (fila.dato("mismaQue") != null) {
        Long id = creadas.get(Integer.parseInt(fila.dato("mismaQue")));
        if (id != null) {
          destino.alias("persona", normal, id);
        }
        continue;
      }
      long id =
          destino.crearPersona(
              fila.dato("nombre"), "1".equals(fila.dato("revisar")), fila.dato("nota"));
      creadas.put(fila.numero(), id);
    }
  }
}

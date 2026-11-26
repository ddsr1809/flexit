package mx.constructora.importacion.dominio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Tipos con los que trabaja el motor de reglas, sin Spring ni base de datos. */
public final class Modelo {

  private Modelo() {}

  /** Cómo termina un renglón. Nada se descarta en silencio: descartar siempre lleva motivo. */
  public enum EstadoFila {
    VALIDA("valida"),
    CORREGIDA("corregida"),
    CUARENTENA("cuarentena"),
    DESCARTADA("descartada");

    private final String clave;

    EstadoFila(String clave) {
      this.clave = clave;
    }

    public String clave() {
      return clave;
    }

    public static EstadoFila deClave(String clave) {
      for (EstadoFila estado : values()) {
        if (estado.clave.equals(clave)) {
          return estado;
        }
      }
      throw new IllegalArgumentException("Estado desconocido: " + clave);
    }
  }

  /** Qué hace el renglón al confirmar. */
  public enum Accion {
    /** Da de alta un registro nuevo. */
    CREAR("crear"),
    /** Ya existe en el sistema (o se liga a uno existente): no cambia nada. */
    EXISTE("existe"),
    /** No hace nada: repetido dentro de la misma carga, descartado o en cuarentena. */
    OMITIR("omitir");

    private final String clave;

    Accion(String clave) {
      this.clave = clave;
    }

    public String clave() {
      return clave;
    }

    public static Accion deClave(String clave) {
      for (Accion accion : values()) {
        if (accion.clave.equals(clave)) {
          return accion;
        }
      }
      throw new IllegalArgumentException("Acción desconocida: " + clave);
    }
  }

  /** Gravedad de una incidencia. */
  public enum Severidad {
    /** Para saber: el sistema la resolvió solo con una regla. */
    INFO("info"),
    /** Entra, pero conviene revisarla (precio muy variable, sin precio). */
    ADVERTENCIA("advertencia"),
    /** Necesita que una persona decida; mientras tanto el renglón no entra. */
    CUARENTENA("cuarentena"),
    /**
     * Dato imposible (fechas al revés, lote ilegible): solo se puede descartar o corregir en el
     * Excel.
     */
    BLOQUEO("bloqueo");

    private final String clave;

    Severidad(String clave) {
      this.clave = clave;
    }

    public String clave() {
      return clave;
    }

    public boolean detiene() {
      return this == CUARENTENA || this == BLOQUEO;
    }
  }

  /** Lo que una persona puede decidir sobre una incidencia. */
  public enum Opcion {
    /** Aceptar la sugerencia del sistema. */
    ACEPTAR,
    /** Elegir otro registro existente ({@code destinoId}). */
    ELEGIR,
    /** Dar de alta un registro nuevo en lugar de ligarlo. */
    CREAR,
    /** Capturar un valor ({@code valor}): un precio, una unidad. */
    VALOR,
    /** No cargar el renglón ({@code motivo} obligatorio). */
    DESCARTAR
  }

  /**
   * Un renglón tal como se leyó.
   *
   * @param numero consecutivo dentro de la carga, desde 1
   * @param filaExcel fila de la hoja; {@code null} si junta varias celdas (un nombre repetido)
   * @param crudo columna → texto capturado, sin tocar; claves que empiezan con "_" son datos de
   *     contexto (celdas de origen, número de apariciones)
   */
  public record FilaCruda(int numero, String hoja, Integer filaExcel, Map<String, String> crudo) {
    public FilaCruda {
      crudo = new LinkedHashMap<>(crudo);
    }

    public String valor(String columna) {
      return crudo.get(columna);
    }
  }

  /** Lo que se leyó del archivo para una carga. */
  public record Lectura(List<String> hojas, List<FilaCruda> filas) {}

  /** La decisión que tomó una persona sobre una incidencia de un renglón. */
  public record Decision(
      int numero, String regla, Opcion opcion, Long destinoId, String valor, String motivo) {}

  /** Decisiones guardadas de la carga. */
  @FunctionalInterface
  public interface Decisiones {
    Optional<Decision> de(int numero, String regla);

    static Decisiones ninguna() {
      return (numero, regla) -> Optional.empty();
    }

    static Decisiones de(List<Decision> lista) {
      Map<String, Decision> porClave = new LinkedHashMap<>();
      for (Decision decision : lista) {
        porClave.put(decision.numero() + "|" + decision.regla(), decision);
      }
      return (numero, regla) -> Optional.ofNullable(porClave.get(numero + "|" + regla));
    }
  }

  /**
   * Algo que el sistema encontró en un renglón.
   *
   * @param corrige el sistema cambió el dato con una regla: el renglón queda "corregido"
   * @param sugerencia lo que el sistema propone; si trae {@code entidad} y {@code texto}, aceptar o
   *     elegir guarda un alias que vale para todas las cargas
   * @param opciones lo que la persona puede decidir
   * @param resolucion la decisión aplicada, si ya la hay
   */
  public record Incidencia(
      String regla,
      Severidad severidad,
      String detalle,
      boolean corrige,
      Map<String, Object> sugerencia,
      Set<Opcion> opciones,
      Decision resolucion) {

    public Incidencia {
      sugerencia = sugerencia == null ? null : new LinkedHashMap<>(sugerencia);
      opciones = opciones == null ? Set.of() : Set.copyOf(opciones);
    }

    public static Incidencia info(String regla, String detalle) {
      return new Incidencia(regla, Severidad.INFO, detalle, false, null, Set.of(), null);
    }

    public static Incidencia correccion(String regla, String detalle) {
      return new Incidencia(regla, Severidad.INFO, detalle, true, null, Set.of(), null);
    }

    public boolean pendiente() {
      return severidad.detiene() && resolucion == null;
    }

    public Incidencia resuelta(Decision decision) {
      return new Incidencia(regla, severidad, detalle, corrige, sugerencia, opciones, decision);
    }
  }

  /** El renglón después de validar: estado, qué hará al confirmar y sus datos normalizados. */
  public record FilaValidada(
      FilaCruda fila,
      EstadoFila estado,
      Accion accion,
      Map<String, String> normal,
      List<Incidencia> incidencias) {

    public FilaValidada {
      normal = new LinkedHashMap<>(normal);
      incidencias = List.copyOf(incidencias);
    }

    public int numero() {
      return fila.numero();
    }

    public String dato(String campo) {
      return normal.get(campo);
    }

    public Long id(String campo) {
      String valor = normal.get(campo);
      return valor == null || valor.isBlank() ? null : Long.valueOf(valor);
    }
  }

  /**
   * Arma un {@link FilaValidada}: los importadores agregan incidencias y datos, y al final el
   * estado sale de las reglas generales (descartada, en cuarentena, corregida o válida).
   */
  public static final class Revision {
    private final FilaCruda fila;
    private final Decisiones decisiones;
    private final Map<String, String> normal = new LinkedHashMap<>();
    private final List<Incidencia> incidencias = new ArrayList<>();
    private Accion accion = Accion.CREAR;
    private Decision descarte;

    public Revision(FilaCruda fila, Decisiones decisiones) {
      this.fila = fila;
      this.decisiones = decisiones;
    }

    public FilaCruda fila() {
      return fila;
    }

    public int numero() {
      return fila.numero();
    }

    public Revision dato(String campo, Object valor) {
      if (valor == null) {
        normal.remove(campo);
      } else {
        normal.put(campo, valor.toString());
      }
      return this;
    }

    public String dato(String campo) {
      return normal.get(campo);
    }

    public Revision accion(Accion nueva) {
      accion = nueva;
      return this;
    }

    public Accion accion() {
      return accion;
    }

    public Revision agregar(Incidencia incidencia) {
      incidencias.add(incidencia);
      return this;
    }

    /** La decisión guardada para esta regla en este renglón, si la hay. */
    public Optional<Decision> decision(String regla) {
      return decisiones.de(fila.numero(), regla);
    }

    /**
     * Agrega una incidencia que pide decisión. Si ya hay una decisión de descartar, el renglón se
     * descarta; si hay otra decisión, la incidencia queda resuelta y se devuelve para que el
     * importador la aplique.
     */
    public Optional<Decision> pedir(
        String regla,
        Severidad severidad,
        String detalle,
        Map<String, Object> sugerencia,
        Set<Opcion> opciones) {
      Optional<Decision> decision = decisiones.de(fila.numero(), regla);
      Incidencia incidencia =
          new Incidencia(regla, severidad, detalle, false, sugerencia, opciones, null);
      if (decision.isPresent()
          && decision.get().opcion() != null
          && opciones.contains(decision.get().opcion())) {
        incidencias.add(incidencia.resuelta(decision.get()));
        if (decision.get().opcion() == Opcion.DESCARTAR) {
          descarte = decision.get();
          return Optional.empty();
        }
        return decision;
      }
      incidencias.add(incidencia);
      return Optional.empty();
    }

    public boolean descartada() {
      return descarte != null;
    }

    public boolean pendiente() {
      return incidencias.stream().anyMatch(Incidencia::pendiente);
    }

    public FilaValidada cerrar() {
      EstadoFila estado;
      Accion accionFinal = accion;
      if (descarte != null) {
        estado = EstadoFila.DESCARTADA;
        accionFinal = Accion.OMITIR;
      } else if (pendiente()) {
        estado = EstadoFila.CUARENTENA;
        accionFinal = Accion.OMITIR;
      } else if (incidencias.stream().anyMatch(i -> i.corrige() || i.resolucion() != null)) {
        estado = EstadoFila.CORREGIDA;
      } else {
        estado = EstadoFila.VALIDA;
      }
      return new FilaValidada(fila, estado, accionFinal, normal, incidencias);
    }

    /** Se descarta sola por una regla (no es un nombre, renglón repetido): sigue visible. */
    public Revision descartarPorRegla(String regla, String detalle) {
      incidencias.add(Incidencia.info(regla, detalle));
      descarte = new Decision(fila.numero(), regla, Opcion.DESCARTAR, null, null, detalle);
      return this;
    }
  }
}

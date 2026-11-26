package mx.constructora.importacion.dominio;

import static mx.constructora.importacion.dominio.Comun.limpio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import mx.constructora.estructura.CodigoContrato;
import mx.constructora.importacion.dominio.Modelo.Accion;
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
 * Contratos desde la hoja "CONTRATOS ACTIVOS" del SUA. La hoja no tiene encabezados: un renglón es
 * contrato si la columna B tiene un código como {@code 60-KBN-MEO-C001-2026}. Columnas: B código, C
 * frente, D inicio, E fin, F No. de obra, G monto.
 */
public final class ImportadorContratos implements Importador {

  static final String HOJA = "CONTRATOS ACTIVOS";
  private static final Set<String> COLUMNAS = Set.of("B", "C", "D", "E", "F", "G");

  /** {@code 60-KBN-MEO-C001-2026} o {@code 60-KBU-MEK/C001/2026}: al menos 4 partes. */
  private static final Pattern PARECE_CODIGO =
      Pattern.compile(
          "^\\s*[0-9]{2}[-/ ][A-Z0-9]{2,4}([-/ ][A-Z0-9]{2,6}){2,}\\s*$", Pattern.CASE_INSENSITIVE);

  private static final Pattern NUMERO_OBRA = Pattern.compile("^[A-Z0-9-]{1,20}$");

  @Override
  public TipoCarga tipo() {
    return TipoCarga.CONTRATOS;
  }

  @Override
  public Lectura leer(LibroXlsx libro) {
    String hoja =
        Comun.hoja(libro, HOJA)
            .orElseThrow(() -> new ArchivoInvalido("El libro no tiene la hoja \"" + HOJA + "\""));
    List<FilaCruda> filas = new ArrayList<>();
    libro.leer(
        hoja,
        COLUMNAS,
        r -> {
          String codigo = r.capturado("B");
          if (codigo != null && PARECE_CODIGO.matcher(codigo).matches()) {
            Map<String, String> crudo = new LinkedHashMap<>();
            for (String columna : List.of("B", "C", "D", "E", "F", "G")) {
              String valor = r.capturado(columna);
              if (valor != null) {
                crudo.put(columna, valor);
              }
            }
            filas.add(new FilaCruda(filas.size() + 1, hoja, r.numero(), crudo));
          }
          return true;
        });
    if (filas.isEmpty()) {
      throw new ArchivoInvalido(
          "La hoja \"" + hoja + "\" no tiene contratos en la columna B (60-XXX-XXX-C001-2026)");
    }
    return new Lectura(List.of(hoja), filas);
  }

  @Override
  public List<FilaValidada> validar(
      List<FilaCruda> filas, Contexto contexto, Decisiones decisiones) {
    Map<String, Integer> vistos = new HashMap<>();
    List<FilaValidada> resultado = new ArrayList<>();
    for (FilaCruda fila : filas) {
      Revision revision = new Revision(fila, decisiones);
      validarFila(fila, revision, contexto, vistos);
      resultado.add(revision.cerrar());
    }
    return resultado;
  }

  private static void validarFila(
      FilaCruda fila, Revision revision, Contexto contexto, Map<String, Integer> vistos) {
    String original = fila.valor("B");
    Optional<String> codigo = CodigoContrato.normalizar(original);
    if (codigo.isEmpty()) {
      bloquear(revision, "CODIGO_INVALIDO", "\"" + original + "\" no es un código válido");
      return;
    }
    revision.dato("codigo", codigo.get());
    if (!codigo.get().equals(limpio(original))) {
      revision.agregar(
          Incidencia.correccion(
              "CODIGO_NORMALIZADO", original.trim() + " se guarda como " + codigo.get()));
    }

    // Frente
    Contexto.Frente frente = Frentes.resolver(fila.valor("C"), revision, contexto);
    if (frente != null) {
      revision.dato("frenteId", frente.id()).dato("clienteId", frente.clienteId());
    }

    // Fechas, No. de obra y monto
    LocalDate inicio = fecha(fila, "D", "inicio", revision);
    LocalDate fin = fecha(fila, "E", "fin", revision);
    if (inicio != null && fin != null && fin.isBefore(inicio)) {
      bloquear(
          revision,
          "FECHAS_INVERTIDAS",
          "La fecha de fin " + fin + " es anterior al inicio " + inicio);
    }
    String obra = limpio(fila.valor("F"));
    if (obra != null) {
      String mayusculas = obra.toUpperCase();
      if (NUMERO_OBRA.matcher(mayusculas).matches()) {
        revision.dato("numeroObra", mayusculas);
      } else {
        revision.agregar(
            new Incidencia(
                "NUMERO_OBRA_INVALIDO",
                Severidad.ADVERTENCIA,
                "\"" + obra + "\" no es un No. de obra válido: el contrato se carga sin él",
                true,
                null,
                Set.of(),
                null));
      }
    }
    String monto = fila.valor("G");
    if (monto != null) {
      Optional<BigDecimal> valor = Comun.decimal(monto);
      if (valor.isPresent() && valor.get().signum() >= 0) {
        // Excel guarda 1072615.5299999984: se redondea a centavos, que es lo que se capturó.
        revision.dato("monto", valor.get().setScale(2, RoundingMode.HALF_UP).toPlainString());
      } else {
        revision.agregar(
            new Incidencia(
                "MONTO_INVALIDO",
                Severidad.ADVERTENCIA,
                "\"" + monto + "\" no es un monto: el contrato se carga sin monto",
                true,
                null,
                Set.of(),
                null));
      }
    }

    // ¿Ya existe?
    Optional<Contexto.Contrato> existente =
        contexto.contratos().stream().filter(c -> c.codigo().equals(codigo.get())).findFirst();
    if (existente.isPresent()) {
      revision
          .dato("contratoId", existente.get().id())
          .accion(Accion.EXISTE)
          .agregar(Incidencia.info("YA_EXISTE", "El contrato ya está en el sistema"));
      if (frente != null && !java.util.Objects.equals(existente.get().frenteId(), frente.id())) {
        revision.agregar(
            new Incidencia(
                "DIFIERE_DEL_SISTEMA",
                Severidad.ADVERTENCIA,
                "En el sistema el contrato es de otro frente; no se cambia",
                false,
                null,
                Set.of(),
                null));
      }
      return;
    }
    Integer anterior = vistos.putIfAbsent(codigo.get(), fila.numero());
    if (anterior != null) {
      revision
          .accion(Accion.OMITIR)
          .agregar(
              Incidencia.info(
                  "DUPLICADO_EN_CARGA", "Se repite el contrato del renglón " + anterior));
    }
  }

  private static LocalDate fecha(FilaCruda fila, String columna, String campo, Revision revision) {
    String valor = fila.valor(columna);
    if (valor == null) {
      return null;
    }
    Optional<LocalDate> fecha = Comun.fecha(valor);
    if (fecha.isEmpty()) {
      bloquear(revision, "FECHA_INVALIDA", "\"" + valor + "\" en " + columna + " no es una fecha");
      return null;
    }
    revision.dato(campo, fecha.get());
    return fecha.get();
  }

  /** Dato imposible: se corrige en el Excel y se vuelve a subir, o se descarta el renglón. */
  private static void bloquear(Revision revision, String regla, String detalle) {
    revision.pedir(regla, Severidad.BLOQUEO, detalle, null, EnumSet.of(Opcion.DESCARTAR));
  }

  @Override
  public void aplicar(List<FilaValidada> filas, Contexto contexto, Destino destino) {
    for (FilaValidada fila : filas) {
      if (fila.accion() != Accion.CREAR) {
        continue;
      }
      destino.crearContrato(
          fila.id("clienteId"),
          fila.id("frenteId"),
          fila.dato("codigo"),
          fila.dato("numeroObra"),
          fila.dato("inicio") == null ? null : LocalDate.parse(fila.dato("inicio")),
          fila.dato("fin") == null ? null : LocalDate.parse(fila.dato("fin")),
          fila.dato("monto") == null ? null : new BigDecimal(fila.dato("monto")));
    }
  }
}

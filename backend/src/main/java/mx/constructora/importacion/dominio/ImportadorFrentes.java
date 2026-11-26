package mx.constructora.importacion.dominio;

import static mx.constructora.importacion.dominio.Comun.limpio;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mx.constructora.importacion.dominio.Comun.Empate;
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
import mx.constructora.importacion.dominio.xlsx.Renglon;

/**
 * Frentes desde la hoja "Info. listas desplegables" del almacén (columna Frente). Todos quedan con
 * el cliente que se elige al subir. Las demás listas (documento, estado, urgencia) son de
 * documentos que llegan en H4 y H6: se leen pero no crean nada todavía.
 */
public final class ImportadorFrentes implements Importador {

  static final String HOJA = "Info. listas desplegables";

  /** Valores de la lista de frentes que suelen ser lugares, no obras: se preguntan. */
  private static final Set<String> NO_SON_FRENTES = Set.of("ALMACEN", "OFICINA", "BODEGA", "RM");

  @Override
  public TipoCarga tipo() {
    return TipoCarga.FRENTES;
  }

  @Override
  public Lectura leer(LibroXlsx libro) {
    String hoja =
        Comun.hoja(libro, HOJA)
            .orElseThrow(() -> new ArchivoInvalido("El libro no tiene la hoja \"" + HOJA + "\""));
    List<FilaCruda> filas = new ArrayList<>();
    String[] columnaFrente = {null};
    libro.leer(
        hoja,
        r -> {
          if (r.numero() == 1) {
            columnaFrente[0] = columnaDe(r, "Frente");
            if (columnaFrente[0] == null) {
              Comun.exigirEncabezado(r, hoja, "C", "Frente");
            }
            return true;
          }
          String valor = limpio(r.capturado(columnaFrente[0]));
          if (valor != null) {
            filas.add(
                new FilaCruda(filas.size() + 1, hoja, r.numero(), Map.of(columnaFrente[0], valor)));
          }
          return true;
        });
    if (columnaFrente[0] == null) {
      throw new ArchivoInvalido("La hoja \"" + hoja + "\" está vacía");
    }
    return new Lectura(List.of(hoja), filas);
  }

  private static String columnaDe(Renglon encabezado, String titulo) {
    for (var celda : encabezado.celdas().values()) {
      if (Normal.texto(titulo).equals(Normal.texto(celda.capturado()))) {
        return celda.columna();
      }
    }
    return null;
  }

  @Override
  public List<FilaValidada> validar(
      List<FilaCruda> filas, Contexto contexto, Decisiones decisiones) {
    String cliente = contexto.parametros().get("clienteId");
    Map<String, Integer> vistos = new HashMap<>();
    List<FilaValidada> resultado = new ArrayList<>();
    for (FilaCruda fila : filas) {
      Revision revision = new Revision(fila, decisiones);
      String valor = fila.crudo().values().iterator().next();
      String normal = Normal.texto(valor);
      String clave = Normal.clave(valor, 12);
      revision.dato("nombre", valor).dato("clave", clave).dato("clienteId", cliente);
      if (cliente == null) {
        revision.agregar(
            new Incidencia(
                "FALTA_CLIENTE",
                Severidad.BLOQUEO,
                "La carga de frentes necesita el cliente al que pertenecen",
                false,
                null,
                Set.of(),
                null));
        resultado.add(revision.cerrar());
        continue;
      }
      Empate<Contexto.Frente> empate =
          Comun.empatar(
              "frente",
              normal,
              contexto,
              contexto.frentes(),
              Contexto.Frente::id,
              Contexto.Frente::nombreNormal);
      Contexto.Frente existente = empate.exacto();
      if (existente == null) {
        existente =
            contexto.frentes().stream()
                .filter(f -> f.clave().equals(clave))
                .findFirst()
                .orElse(null);
      }
      if (existente != null) {
        revision
            .dato("frenteId", existente.id())
            .accion(Accion.EXISTE)
            .agregar(Incidencia.info("YA_EXISTE", "Ya existe el frente " + existente.clave()));
      } else if (vistos.containsKey(clave)) {
        revision
            .accion(Accion.OMITIR)
            .agregar(
                Incidencia.info(
                    "DUPLICADO_EN_CARGA", "Se repite el frente del renglón " + vistos.get(clave)));
      } else if (NO_SON_FRENTES.contains(normal)) {
        var decision =
            revision.pedir(
                "NO_ES_FRENTE",
                Severidad.CUARENTENA,
                "\""
                    + valor
                    + "\" parece un lugar (almacén, oficina) y no una obra. ¿Es un frente?",
                null,
                EnumSet.of(Opcion.CREAR, Opcion.DESCARTAR));
        if (decision.isPresent()) {
          vistos.put(clave, fila.numero());
        }
      } else {
        vistos.put(clave, fila.numero());
        if (!Normal.texto(clave).equals(normal)) {
          revision.agregar(
              Incidencia.info("CLAVE_CORTA", "Clave corta " + clave + " (para claves de lote)"));
        }
      }
      resultado.add(revision.cerrar());
    }
    return resultado;
  }

  @Override
  public void aplicar(List<FilaValidada> filas, Contexto contexto, Destino destino) {
    for (FilaValidada fila : filas) {
      if (fila.accion() == Accion.CREAR) {
        destino.crearFrente(fila.id("clienteId"), fila.dato("clave"), fila.dato("nombre"));
      }
    }
  }
}

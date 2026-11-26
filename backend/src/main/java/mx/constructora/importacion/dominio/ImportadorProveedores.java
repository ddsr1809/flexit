package mx.constructora.importacion.dominio;

import static mx.constructora.importacion.dominio.Comun.limpio;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * Proveedores desde la columna Proveedor de Entradas: un renglón por nombre distinto. La tasa de
 * IVA por defecto es la que más usan sus entradas (columna J); la usa el histórico (H5) cuando una
 * entrada no trae IVA. Algunas celdas de la columna son fórmulas: no se leen.
 */
public final class ImportadorProveedores implements Importador {

  static final String HOJA = "Entradas";

  @Override
  public TipoCarga tipo() {
    return TipoCarga.PROVEEDORES;
  }

  @Override
  public Lectura leer(LibroXlsx libro) {
    String hoja =
        Comun.hoja(libro, HOJA)
            .orElseThrow(() -> new ArchivoInvalido("El libro no tiene la hoja \"" + HOJA + "\""));
    Map<String, FilaCruda> porNombre = new LinkedHashMap<>();
    libro.leer(
        hoja,
        Set.of("J", "M"),
        r -> {
          if (r.numero() == 1) {
            Comun.exigirEncabezado(r, hoja, "M", "Proveedor");
            return true;
          }
          String nombre = limpio(r.capturado("M"));
          String normal = Normal.texto(nombre);
          if (normal == null) {
            return true;
          }
          FilaCruda fila =
              porNombre.computeIfAbsent(
                  normal,
                  n -> {
                    Map<String, String> crudo = new LinkedHashMap<>();
                    crudo.put("texto", nombre);
                    crudo.put("_celdas", "M" + r.numero());
                    crudo.put("_apariciones", "0");
                    return new FilaCruda(0, hoja, r.numero(), crudo);
                  });
          sumar(fila, "_apariciones");
          Comun.decimal(r.capturado("J"))
              .ifPresent(t -> sumar(fila, "_iva_" + t.stripTrailingZeros().toPlainString()));
          return true;
        });
    List<FilaCruda> filas = new ArrayList<>();
    for (FilaCruda fila : porNombre.values()) {
      filas.add(new FilaCruda(filas.size() + 1, fila.hoja(), fila.filaExcel(), fila.crudo()));
    }
    return new Lectura(List.of(hoja), filas);
  }

  private static void sumar(FilaCruda fila, String clave) {
    fila.crudo().merge(clave, "1", (a, b) -> Integer.toString(Integer.parseInt(a) + 1));
  }

  @Override
  public List<FilaValidada> validar(
      List<FilaCruda> filas, Contexto contexto, Decisiones decisiones) {
    List<FilaValidada> resultado = new ArrayList<>();
    List<Contexto.Proveedor> nuevos = new ArrayList<>();
    for (FilaCruda fila : filas) {
      Revision revision = new Revision(fila, decisiones);
      String nombre = fila.valor("texto");
      String normal = Normal.texto(nombre);
      revision.dato("nombre", nombre);
      Empate<Contexto.Proveedor> empate =
          Comun.empatar(
              "proveedor",
              normal,
              contexto,
              contexto.proveedores(),
              Contexto.Proveedor::id,
              Contexto.Proveedor::nombreNormal);
      if (empate.exacto() != null) {
        revision
            .dato("proveedorId", empate.exacto().id())
            .accion(Accion.EXISTE)
            .agregar(Incidencia.info("YA_EXISTE", "Ya existe como " + empate.exacto().nombre()));
        resultado.add(revision.cerrar());
        continue;
      }
      if (nombre.length() > 160) {
        revision.pedir(
            "NOMBRE_LARGO",
            Severidad.BLOQUEO,
            "El nombre pasa de 160 letras",
            null,
            EnumSet.of(Opcion.DESCARTAR));
        resultado.add(revision.cerrar());
        continue;
      }
      // Parecido a uno existente o a otro nombre de esta carga ("DCH" y "D.C.H.").
      Empate<Contexto.Proveedor> parecido =
          empate.parecido() != null
              ? empate
              : Comun.parecido(normal, nuevos, Contexto.Proveedor::nombreNormal);
      if (parecido.parecido() != null) {
        Contexto.Proveedor otro = parecido.parecido();
        boolean deEstaCarga = otro.id() < 0;
        Map<String, Object> sugerencia =
            deEstaCarga
                ? Comun.sugerencia(null, normal, null, otro.nombre(), parecido.puntos())
                : Comun.sugerencia(
                    "proveedor", normal, otro.id(), otro.nombre(), parecido.puntos());
        if (deEstaCarga) {
          sugerencia.put("numero", Math.toIntExact(-otro.id()));
        }
        Optional<Decision> decision =
            revision.pedir(
                "POSIBLE_DUPLICADO",
                Severidad.CUARENTENA,
                "\"%s\" se parece a \"%s\"%s"
                    .formatted(
                        nombre, otro.nombre(), deEstaCarga ? " (renglón " + -otro.id() + ")" : ""),
                sugerencia,
                deEstaCarga
                    ? EnumSet.of(Opcion.ACEPTAR, Opcion.CREAR, Opcion.DESCARTAR)
                    : EnumSet.of(Opcion.ACEPTAR, Opcion.ELEGIR, Opcion.CREAR, Opcion.DESCARTAR));
        if (decision.isEmpty()) {
          resultado.add(revision.cerrar());
          continue;
        }
        if (decision.get().opcion() == Opcion.ACEPTAR && deEstaCarga) {
          revision
              .dato("mismaQue", -otro.id())
              .agregar(Incidencia.info("ALIAS", "Se liga al proveedor del renglón " + -otro.id()));
          resultado.add(revision.cerrar());
          continue;
        }
      }
      nuevos.add(new Contexto.Proveedor(-fila.numero(), nombre, normal));
      tasaIva(fila, revision);
      resultado.add(revision.cerrar());
    }
    return resultado;
  }

  /** La tasa que más se repite en las entradas del proveedor; ninguna si empatan. */
  private static void tasaIva(FilaCruda fila, Revision revision) {
    String mejor = null;
    int veces = 0;
    boolean empate = false;
    for (Map.Entry<String, String> entrada : fila.crudo().entrySet()) {
      if (!entrada.getKey().startsWith("_iva_")) {
        continue;
      }
      int n = Integer.parseInt(entrada.getValue());
      if (n > veces) {
        mejor = entrada.getKey().substring(5);
        veces = n;
        empate = false;
      } else if (n == veces) {
        empate = true;
      }
    }
    if (mejor == null || empate) {
      revision.agregar(
          Incidencia.info("IVA_SIN_DATO", "Sus entradas no dicen qué IVA usa: queda sin tasa"));
      return;
    }
    BigDecimal tasa = new BigDecimal(mejor);
    if (tasa.signum() < 0 || tasa.compareTo(new BigDecimal("0.16")) > 0) {
      revision.agregar(
          Incidencia.info("IVA_SIN_DATO", "La tasa " + mejor + " no es válida: queda sin tasa"));
      return;
    }
    revision
        .dato("tasaIva", tasa.toPlainString())
        .agregar(
            Incidencia.correccion(
                "TASA_DE_SUS_ENTRADAS",
                "IVA por defecto %s (en %d de sus entradas)"
                    .formatted(tasa.toPlainString(), veces)));
  }

  @Override
  public void aplicar(List<FilaValidada> filas, Contexto contexto, Destino destino) {
    Map<Integer, Long> creados = new LinkedHashMap<>();
    for (FilaValidada fila : filas) {
      if (fila.accion() != Accion.CREAR) {
        continue;
      }
      if (fila.dato("mismaQue") != null) {
        Long id = creados.get(Integer.parseInt(fila.dato("mismaQue")));
        if (id != null) {
          destino.alias("proveedor", Normal.texto(fila.dato("nombre")), id);
        }
        continue;
      }
      long id =
          destino.crearProveedor(
              fila.dato("nombre"),
              fila.dato("tasaIva") == null ? null : new BigDecimal(fila.dato("tasaIva")));
      creados.put(fila.numero(), id);
    }
  }
}

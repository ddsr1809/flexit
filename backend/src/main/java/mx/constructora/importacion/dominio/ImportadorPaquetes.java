package mx.constructora.importacion.dominio;

import static mx.constructora.importacion.dominio.Comun.limpio;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import mx.constructora.importacion.dominio.xlsx.Renglon;

/**
 * Paquetes y precios desde las hojas de pagos del destajo. Cada hoja es un prototipo de un frente y
 * trae, por sección, dos listas lado a lado:
 *
 * <ul>
 *   <li>Columnas B y C: los paquetes del cliente (Ruba) con su precio de cobro.
 *   <li>Columnas D a G: los conceptos de destajo propios (RM) con unidad, cantidad y precio.
 * </ul>
 *
 * Las dos listas solo se corresponden por sección (los renglones no son equivalentes uno a uno),
 * así que la equivalencia Ruba ↔ RM se guarda por sección. Los conceptos se empatan entre hojas por
 * sección y nombre; un concepto que se repite en la misma sección (dos "COLOCACION DE CAJAS
 * ELECTRICAS", planta baja y alta) es otro paquete: se le agrega "(2)". El precio se fija en cada
 * contrato del frente que tiene lotes de ese prototipo; por eso esta carga va después de los lotes.
 */
public final class ImportadorPaquetes implements Importador {

  static final String PROPIO = "propio";
  static final String CLIENTE = "cliente";

  private static final Pattern NUMERO_SECCION = Pattern.compile("^\\d{1,3}$");
  private static final Pattern CLAVE_SECCION = Pattern.compile("^S(\\d{1,4})$");
  private static final Pattern CLAVE_CONCEPTO = Pattern.compile("^S\\d{1,4}\\.(\\d{1,4})$");
  private static final int NOMBRE_MAXIMO = 160;
  private static final int UNIDAD_MAXIMA = 20;

  @Override
  public TipoCarga tipo() {
    return TipoCarga.PAQUETES;
  }

  // ------------------------------------------------------------------------------------ leer

  @Override
  public Lectura leer(LibroXlsx libro) {
    List<String> hojas = ImportadorLotes.hojasDeDestajo(libro);
    if (hojas.isEmpty()) {
      throw new ArchivoInvalido(
          "El libro no tiene hojas de pagos del destajo (\"Pagos Corceles P-65\")");
    }
    List<FilaCruda> filas = new ArrayList<>();
    Set<String> columnas = Set.of("A", "B", "C", "D", "E", "F", "G");
    for (String hoja : hojas) {
      String prototipo = ImportadorLotes.prototipoDeHoja(hoja);
      List<Renglon> encabezado = Comun.renglones(libro, hoja, columnas, 6, 6);
      Renglon fila6 = encabezado.isEmpty() ? null : encabezado.getFirst();
      Comun.exigirEncabezado(fila6, hoja, "A", "No.");
      Comun.exigirEncabezado(fila6, hoja, "D", "Destajo");
      String[] seccion = {null, null}; // cliente, propio
      libro.leer(
          hoja,
          columnas,
          r -> {
            if (r.numero() < 7) {
              return true;
            }
            // Debajo de las secciones viene el resumen ("PRECIOS RUBA DIF", "TOTAL GASTADO…"):
            // son totales, no paquetes.
            String b = Normal.texto(r.capturado("B"));
            if (b != null && (b.equals("PRECIOS") || b.startsWith("TOTAL GASTADO"))) {
              return false;
            }
            leerRenglon(r, hoja, prototipo, seccion, filas);
            return true;
          });
    }
    return new Lectura(hojas, filas);
  }

  private static void leerRenglon(
      Renglon r, String hoja, String prototipo, String[] seccion, List<FilaCruda> filas) {
    String numero = limpio(r.capturado("A"));
    String cliente = limpio(r.capturado("B"));
    String propio = limpio(r.capturado("D"));
    String precioDestajo = limpio(r.capturado("G"));
    boolean esSeccion =
        numero != null
            && NUMERO_SECCION.matcher(numero).matches()
            && precioDestajo == null
            && (cliente != null || propio != null);
    if (esSeccion) {
      seccion[0] = cliente;
      seccion[1] = propio;
      if (propio != null) {
        filas.add(fila(filas, hoja, r, prototipo, "seccion", PROPIO, propio, null, cliente));
      }
      if (cliente != null) {
        filas.add(fila(filas, hoja, r, prototipo, "seccion", CLIENTE, cliente, null, propio));
      }
      return;
    }
    boolean conDatos =
        precioDestajo != null
            || limpio(r.capturado("E")) != null
            || limpio(r.capturado("F")) != null;
    if (propio != null && !conDatos) {
      // Texto suelto en la columna D (una nota junto al subtotal): se muestra y se descarta.
      FilaCruda nota = fila(filas, hoja, r, prototipo, "nota", PROPIO, propio, seccion[1], null);
      filas.add(nota);
    } else if (propio != null || precioDestajo != null) {
      FilaCruda concepto =
          fila(filas, hoja, r, prototipo, "concepto", PROPIO, propio, seccion[1], null);
      putSi(concepto.crudo(), "unidad", limpio(r.capturado("E")));
      putSi(concepto.crudo(), "cantidad", limpio(r.capturado("F")));
      putSi(concepto.crudo(), "precio", precioDestajo);
      putSi(concepto.crudo(), "A", numero);
      filas.add(concepto);
    }
    if (cliente != null) {
      FilaCruda concepto =
          fila(filas, hoja, r, prototipo, "concepto", CLIENTE, cliente, seccion[0], null);
      putSi(concepto.crudo(), "precio", limpio(r.capturado("C")));
      putSi(concepto.crudo(), "A", numero);
      filas.add(concepto);
    }
  }

  private static FilaCruda fila(
      List<FilaCruda> filas,
      String hoja,
      Renglon r,
      String prototipo,
      String tipo,
      String lado,
      String nombre,
      String seccion,
      String par) {
    Map<String, String> crudo = new LinkedHashMap<>();
    crudo.put("_tipo", tipo);
    crudo.put("_lado", lado);
    putSi(crudo, "nombre", nombre);
    putSi(crudo, "_seccion", seccion);
    putSi(crudo, "_par", par);
    putSi(crudo, "_prototipo", prototipo);
    return new FilaCruda(filas.size() + 1, hoja, r.numero(), crudo);
  }

  private static void putSi(Map<String, String> mapa, String clave, String valor) {
    if (valor != null) {
      mapa.put(clave, valor);
    }
  }

  // ------------------------------------------------------------------------------- validación

  /** Un paquete del catálogo (existente o por crear) identificado por su llave de empate. */
  private static final class Nodo {
    final String llave;
    final Long clienteId;
    final String padreLlave;
    Long id;
    String clave;
    String nombre;
    String unidad;
    int orden;
    Integer creadoEn;

    Nodo(String llave, Long clienteId, String padreLlave) {
      this.llave = llave;
      this.clienteId = clienteId;
      this.padreLlave = padreLlave;
    }
  }

  /** Asigna claves S01, S01.01… sin chocar con las que ya hay en cada catálogo. */
  private static final class Catalogos {
    final Map<String, Nodo> nodos = new LinkedHashMap<>();
    final Map<String, Integer> ultimaSeccion = new HashMap<>();
    final Map<String, Integer> ultimoConcepto = new HashMap<>();
    final Map<String, Integer> ordenSeccion = new HashMap<>();
    final Map<String, Integer> ordenConcepto = new HashMap<>();
    final Contexto contexto;

    Catalogos(Contexto contexto) {
      this.contexto = contexto;
      for (Contexto.Paquete p : contexto.paquetes()) {
        String catalogo = catalogo(p.clienteId());
        Matcher s = CLAVE_SECCION.matcher(p.clave());
        if (p.padreId() == null && s.matches()) {
          ultimaSeccion.merge(catalogo, Integer.parseInt(s.group(1)), Math::max);
        }
        ordenSeccion.merge(catalogo, p.padreId() == null ? p.orden() : 0, Math::max);
      }
    }

    static String catalogo(Long clienteId) {
      return clienteId == null ? PROPIO : CLIENTE + clienteId;
    }

    Nodo seccion(Long clienteId, String nombre, int numero) {
      String normal = Normal.texto(nombre);
      String llave = catalogo(clienteId) + "|" + normal;
      Nodo nodo = nodos.get(llave);
      if (nodo != null) {
        return nodo;
      }
      nodo = new Nodo(llave, clienteId, null);
      nodo.nombre = nombre;
      Optional<Contexto.Paquete> existente =
          contexto.paquetes().stream()
              .filter(
                  p ->
                      Objects.equals(p.clienteId(), clienteId)
                          && p.padreId() == null
                          && normal.equals(p.nombreNormal()))
              .findFirst();
      if (existente.isPresent()) {
        nodo.id = existente.get().id();
        nodo.clave = existente.get().clave();
      } else {
        int siguiente = ultimaSeccion.merge(catalogo(clienteId), 1, Integer::sum);
        nodo.clave = "S%02d".formatted(siguiente);
        nodo.orden = ordenSeccion.merge(catalogo(clienteId), 1, Integer::sum);
        nodo.creadoEn = numero;
      }
      nodos.put(llave, nodo);
      return nodo;
    }

    Nodo concepto(Nodo seccion, String nombre, int numero) {
      String normal = Normal.texto(nombre);
      String llave = seccion.llave + "|" + normal;
      Nodo nodo = nodos.get(llave);
      if (nodo != null) {
        return nodo;
      }
      nodo = new Nodo(llave, seccion.clienteId, seccion.llave);
      nodo.nombre = nombre;
      if (seccion.id != null) {
        long padre = seccion.id;
        for (Contexto.Paquete p : contexto.paquetes()) {
          if (Objects.equals(p.padreId(), padre)) {
            Matcher c = CLAVE_CONCEPTO.matcher(p.clave());
            if (c.matches()) {
              ultimoConcepto.merge(seccion.llave, Integer.parseInt(c.group(1)), Math::max);
            }
            ordenConcepto.merge(seccion.llave, p.orden(), Math::max);
            if (normal.equals(p.nombreNormal())) {
              nodo.id = p.id();
              nodo.clave = p.clave();
            }
          }
        }
      }
      if (nodo.id == null) {
        int siguiente = ultimoConcepto.merge(seccion.llave, 1, Integer::sum);
        nodo.clave = seccion.clave + ".%02d".formatted(siguiente);
        nodo.orden = ordenConcepto.merge(seccion.llave, 1, Integer::sum);
        nodo.creadoEn = numero;
      }
      nodos.put(llave, nodo);
      return nodo;
    }
  }

  @Override
  public List<FilaValidada> validar(
      List<FilaCruda> filas, Contexto contexto, Decisiones decisiones) {
    Catalogos catalogos = new Catalogos(contexto);
    Map<String, Contexto.Frente> frentePorHoja = new HashMap<>();
    Map<String, Nodo> seccionActual = new HashMap<>(); // hoja|lado → sección
    Map<String, Integer> ocurrencias = new HashMap<>();
    List<FilaValidada> resultado = new ArrayList<>();
    for (FilaCruda fila : filas) {
      Revision revision = new Revision(fila, decisiones);
      Contexto.Frente frente =
          frentePorHoja.containsKey(fila.hoja())
              ? frentePorHoja.get(fila.hoja())
              : Frentes.resolver(fila.hoja(), revision, contexto);
      if (frente == null) {
        resultado.add(revision.cerrar());
        continue;
      }
      frentePorHoja.put(fila.hoja(), frente);
      String lado = fila.valor("_lado");
      Long clienteId = CLIENTE.equals(lado) ? frente.clienteId() : null;
      revision.dato("lado", lado).dato("frenteId", frente.id());
      if (clienteId != null) {
        revision.dato("clienteId", clienteId);
      }
      String nombre = nombre(fila.valor("nombre"), revision);

      if ("seccion".equals(fila.valor("_tipo"))) {
        Nodo seccion = catalogos.seccion(clienteId, nombre, fila.numero());
        seccionActual.put(fila.hoja() + "|" + lado, seccion);
        registrar(revision, seccion, fila.numero());
        revision.dato("tipo", "seccion");
        if (CLIENTE.equals(lado) && fila.valor("_par") != null) {
          Nodo par = catalogos.seccion(null, nombre(fila.valor("_par"), null), fila.numero());
          boolean existe =
              seccion.id != null
                  && par.id != null
                  && contexto.equivalencias().stream()
                      .anyMatch(
                          e -> e.paqueteClienteId() == seccion.id && e.paquetePropioId() == par.id);
          if (!existe) {
            revision.dato("equivalente", par.llave);
            if (revision.accion() == Accion.EXISTE) {
              revision.accion(Accion.CREAR);
            }
          }
        }
        resultado.add(revision.cerrar());
        continue;
      }

      if ("nota".equals(fila.valor("_tipo"))) {
        revision.descartarPorRegla(
            "NOTA",
            "\""
                + fila.valor("nombre")
                + "\" no trae unidad ni precio: es una nota, no un concepto");
        resultado.add(revision.cerrar());
        continue;
      }

      // Concepto
      revision.dato("tipo", "concepto");
      Nodo seccion = seccionActual.get(fila.hoja() + "|" + lado);
      if (seccion == null) {
        revision.pedir(
            "SIN_SECCION",
            Severidad.BLOQUEO,
            "El concepto está antes de la primera sección de la hoja",
            null,
            EnumSet.of(Opcion.DESCARTAR));
        resultado.add(revision.cerrar());
        continue;
      }
      if (nombre == null) {
        Optional<Decision> decision =
            revision.pedir(
                "CONCEPTO_SIN_NOMBRE",
                Severidad.CUARENTENA,
                "Hay un precio (%s) sin concepto en la sección %s: escribe el nombre o descártalo"
                    .formatted(fila.valor("precio"), seccion.nombre),
                null,
                EnumSet.of(Opcion.VALOR, Opcion.DESCARTAR));
        if (decision.isEmpty() || limpio(decision.get().valor()) == null) {
          resultado.add(revision.cerrar());
          continue;
        }
        nombre = nombre(decision.get().valor(), revision);
      }
      String llaveOcurrencia = fila.hoja() + "|" + seccion.llave + "|" + Normal.texto(nombre);
      int ocurrencia = ocurrencias.merge(llaveOcurrencia, 1, Integer::sum);
      if (ocurrencia > 1) {
        nombre = nombre + " (" + ocurrencia + ")";
        revision.agregar(
            Incidencia.correccion(
                "CONCEPTO_REPETIDO",
                "El concepto se repite en la sección; este es otro paquete: " + nombre));
      }
      if (PROPIO.equals(lado) && fila.valor("A") == null) {
        revision.agregar(Incidencia.info("CONCEPTO_SIN_NUMERO", "El concepto no trae número"));
      }
      Nodo concepto = catalogos.concepto(seccion, nombre, fila.numero());
      String unidad = limpio(fila.valor("unidad"));
      if (unidad != null && Comun.decimal(unidad).isEmpty()) {
        concepto.unidad =
            unidad.length() > UNIDAD_MAXIMA ? unidad.substring(0, UNIDAD_MAXIMA) : unidad;
      }
      registrar(revision, concepto, fila.numero());
      precio(fila, revision, contexto, frente, concepto, lado);
      resultado.add(revision.cerrar());
    }
    return resultado;
  }

  private static String nombre(String texto, Revision revision) {
    String nombre = limpio(texto);
    if (nombre != null && nombre.length() > NOMBRE_MAXIMO) {
      nombre = nombre.substring(0, NOMBRE_MAXIMO).trim();
      if (revision != null) {
        revision.agregar(
            Incidencia.correccion("NOMBRE_RECORTADO", "El nombre pasa de 160 letras: se recortó"));
      }
    }
    return nombre;
  }

  private static void registrar(Revision revision, Nodo nodo, int numero) {
    revision.dato("llave", nodo.llave).dato("clave", nodo.clave).dato("nombre", nodo.nombre);
    if (nodo.padreLlave != null) {
      revision.dato("padre", nodo.padreLlave);
    }
    if (nodo.unidad != null) {
      revision.dato("unidad", nodo.unidad);
    }
    if (nodo.id != null) {
      revision
          .dato("paqueteId", nodo.id)
          .accion(Accion.EXISTE)
          .agregar(
              Incidencia.info("YA_EXISTE", "El paquete " + nodo.clave + " ya está en el sistema"));
    } else if (Objects.equals(nodo.creadoEn, numero)) {
      revision.dato("crear", "1").dato("orden", nodo.orden).accion(Accion.CREAR);
    } else {
      revision
          .accion(Accion.EXISTE)
          .agregar(
              Incidencia.info(
                  "MISMO_PAQUETE",
                  "El paquete " + nodo.clave + " se crea en el renglón " + nodo.creadoEn));
    }
  }

  /** Precio de cobro o de destajo para cada contrato del frente con lotes de este prototipo. */
  private static void precio(
      FilaCruda fila,
      Revision revision,
      Contexto contexto,
      Contexto.Frente frente,
      Nodo concepto,
      String lado) {
    String texto = fila.valor("precio");
    if (texto == null) {
      if (PROPIO.equals(lado)) {
        revision.agregar(Incidencia.info("SIN_PRECIO", "El concepto no trae precio en esta hoja"));
      }
      return;
    }
    Optional<BigDecimal> precio = Comun.decimal(texto);
    if (precio.isEmpty() || precio.get().signum() < 0) {
      revision.agregar(
          new Incidencia(
              "PRECIO_INVALIDO",
              Severidad.ADVERTENCIA,
              "\"" + texto + "\" no es un precio: no se carga",
              true,
              null,
              Set.of(),
              null));
      return;
    }
    String clavePrototipo = fila.valor("_prototipo");
    Optional<Contexto.Prototipo> prototipo =
        contexto.prototipos().stream().filter(p -> p.clave().equals(clavePrototipo)).findFirst();
    if (prototipo.isEmpty()) {
      revision.agregar(
          new Incidencia(
              "PROTOTIPO_INEXISTENTE",
              Severidad.ADVERTENCIA,
              "No existe el prototipo "
                  + clavePrototipo
                  + ": carga primero los lotes. El precio no se guarda",
              false,
              null,
              Set.of(),
              null));
      return;
    }
    long prototipoId = prototipo.get().id();
    TreeSet<Long> contratos = new TreeSet<>();
    for (Contexto.Unidad unidad : contexto.unidades()) {
      if (Objects.equals(unidad.frenteId(), frente.id())
          && Objects.equals(unidad.prototipoId(), prototipoId)) {
        contratos.add(unidad.contratoId());
      }
    }
    if (concepto.id != null) {
      long paquete = concepto.id;
      contexto.precios().stream()
          .filter(p -> p.paqueteId() == paquete && Objects.equals(p.prototipoId(), prototipoId))
          .forEach(p -> contratos.remove(p.contratoId()));
    }
    revision.dato("precio", precio.get().toPlainString()).dato("prototipoId", prototipoId);
    if (contratos.isEmpty()) {
      revision.agregar(
          new Incidencia(
              "PRECIO_SIN_CONTRATO",
              Severidad.ADVERTENCIA,
              "Ningún contrato de %s tiene lotes %s sin este precio: no se guarda"
                  .formatted(frente.nombre(), clavePrototipo),
              false,
              null,
              Set.of(),
              null));
      return;
    }
    revision
        .dato("contratos", String.join(",", contratos.stream().map(String::valueOf).toList()))
        .accion(Accion.CREAR);
  }

  // ---------------------------------------------------------------------------------- aplicar

  @Override
  public void aplicar(List<FilaValidada> filas, Contexto contexto, Destino destino) {
    Map<String, Long> ids = new HashMap<>();
    for (FilaValidada fila : filas) {
      if (fila.id("paqueteId") != null) {
        ids.put(fila.dato("llave"), fila.id("paqueteId"));
      }
    }
    // Primero las secciones, luego los conceptos: un concepto puede venir de otra hoja.
    for (String tipo : List.of("seccion", "concepto")) {
      for (FilaValidada fila : filas) {
        if (fila.accion() == Accion.OMITIR
            || !tipo.equals(fila.dato("tipo"))
            || !"1".equals(fila.dato("crear"))) {
          continue;
        }
        Long padre = fila.dato("padre") == null ? null : ids.get(fila.dato("padre"));
        long id =
            destino.crearPaquete(
                fila.id("clienteId"),
                padre,
                fila.dato("clave"),
                fila.dato("nombre"),
                fila.dato("unidad"),
                Integer.parseInt(fila.dato("orden")));
        ids.put(fila.dato("llave"), id);
      }
    }
    Set<String> equivalencias = new LinkedHashSet<>();
    for (FilaValidada fila : filas) {
      if (fila.accion() == Accion.OMITIR) {
        continue;
      }
      if (fila.dato("equivalente") != null) {
        Long cliente = ids.get(fila.dato("llave"));
        Long propio = ids.get(fila.dato("equivalente"));
        if (cliente != null && propio != null && equivalencias.add(cliente + "|" + propio)) {
          destino.agregarEquivalencia(cliente, propio);
        }
      }
      if (fila.dato("contratos") != null) {
        BigDecimal precio = new BigDecimal(fila.dato("precio"));
        boolean cobro = CLIENTE.equals(fila.dato("lado"));
        for (String contrato : Arrays.asList(fila.dato("contratos").split(","))) {
          destino.fijarPrecio(
              Long.parseLong(contrato),
              ids.get(fila.dato("llave")),
              fila.id("prototipoId"),
              cobro ? precio : null,
              cobro ? null : precio);
        }
      }
    }
  }
}

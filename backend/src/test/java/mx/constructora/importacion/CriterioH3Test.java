package mx.constructora.importacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.constructora.ClienteHttp;
import mx.constructora.ClienteHttp.Respuesta;
import mx.constructora.PruebaIntegracion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Criterio de aceptación de H3, por HTTP real contra PostgreSQL: el catálogo llega desde Excel sin
 * capturarlo a mano, lo dudoso queda en cuarentena hasta que una persona decide, subir de nuevo el
 * mismo archivo no duplica nada y revertir una carga la quita completa. Usa las muestras
 * anonimizadas de {@code tools/validacion-datos/muestras/}.
 */
class CriterioH3Test extends PruebaIntegracion {

  private static final String CONTRASENA = "contrasena-de-prueba";

  private String admin;
  private String almacen;
  private Empresa empresa;

  @BeforeEach
  void preparar() {
    empresa = crearEmpresa("H3");
    admin = unico("admin.h3");
    almacen = unico("almacen.h3");
    crearUsuario(empresa, admin, CONTRASENA, "admin");
    crearUsuario(empresa, almacen, CONTRASENA, "almacenista");
  }

  private ClienteHttp sesion(String usuario) {
    ClienteHttp navegador = cliente();
    assertThat(navegador.iniciarSesion(usuario, CONTRASENA).estado()).isEqualTo(200);
    return navegador;
  }

  private static long numero(Respuesta respuesta, String ruta) {
    return ((Number) respuesta.json(ruta)).longValue();
  }

  private static Respuesta subir(ClienteHttp navegador, String tipo, String muestra, Long cliente)
      throws IOException {
    Map<String, String> campos = new LinkedHashMap<>();
    campos.put("tipo", tipo);
    if (cliente != null) {
      campos.put("clienteId", cliente.toString());
    }
    return navegador.subir(
        "/api/v1/importaciones", campos, muestra, Files.readAllBytes(Muestras.ruta(muestra)));
  }

  /** Decide todas las incidencias pendientes de una regla con la misma opción. */
  private static Respuesta decidirTodas(
      ClienteHttp navegador, long carga, String regla, String opcion, String motivo) {
    Respuesta pendientes =
        navegador.get(
            "/api/v1/importaciones/%d/filas?pendientes=true&regla=%s&tamano=200"
                .formatted(carga, regla));
    assertThat(pendientes.estado()).as(pendientes.cuerpo()).isEqualTo(200);
    List<Integer> numeros = pendientes.json("$.elementos[*].numero");
    assertThat(numeros).as("pendientes de " + regla).isNotEmpty();
    List<String> decisiones = new ArrayList<>();
    for (Integer n : numeros) {
      decisiones.add(
          "{\"numero\":%d,\"regla\":\"%s\",\"opcion\":\"%s\"%s}"
              .formatted(n, regla, opcion, motivo == null ? "" : ",\"motivo\":\"" + motivo + "\""));
    }
    return navegador.post(
        "/api/v1/importaciones/%d/decisiones".formatted(carga),
        "{\"decisiones\":[" + String.join(",", decisiones) + "]}");
  }

  @Test
  void elCatalogoLlegaDesdeExcelSinDuplicarYSeRevierteCompleto() throws IOException {
    ClienteHttp administracion = sesion(admin);
    long ruba = numero(administracion.post("/api/v1/clientes", "{\"nombre\":\"Ruba\"}"), "$.id");

    // Orden de carga: contratos necesita frentes
    Respuesta orden = administracion.get("/api/v1/importaciones/tipos");
    assertThat(orden.<List<String>>json("$[*].tipo")).startsWith("FRENTES", "CONTRATOS");
    assertThat(orden.<Boolean>json("$[1].disponible")).isFalse();
    Respuesta antes = subir(administracion, "CONTRATOS", "muestra-sua.xlsx", null);
    assertThat(antes.estado()).isEqualTo(409);
    assertThat(antes.<String>json("$.codigo")).isEqualTo("FALTA_CARGA_PREVIA");

    // Frentes: "Almacén" queda en cuarentena; la carga no se confirma hasta decidir
    Respuesta frentes = subir(administracion, "FRENTES", "muestra-almacen.xlsx", ruba);
    assertThat(frentes.estado()).as(frentes.cuerpo()).isEqualTo(201);
    long cargaFrentes = numero(frentes, "$.id");
    assertThat(numero(frentes, "$.renglones")).isEqualTo(5);
    assertThat(numero(frentes, "$.enCuarentena")).isEqualTo(1);

    Respuesta otraVez = subir(administracion, "FRENTES", "muestra-almacen.xlsx", ruba);
    assertThat(otraVez.estado()).as("el mismo archivo no crea otra carga").isEqualTo(200);
    assertThat(otraVez.<Boolean>json("$.repetida")).isTrue();
    assertThat(numero(otraVez, "$.id")).isEqualTo(cargaFrentes);

    Respuesta pendiente =
        administracion.post("/api/v1/importaciones/%d/confirmar".formatted(cargaFrentes), null);
    assertThat(pendiente.estado()).isEqualTo(409);
    assertThat(pendiente.<String>json("$.codigo")).isEqualTo("CARGA_CON_PENDIENTES");

    Respuesta sinMotivo =
        decidirTodas(administracion, cargaFrentes, "NO_ES_FRENTE", "DESCARTAR", null);
    assertThat(sinMotivo.estado()).isEqualTo(400);
    assertThat(sinMotivo.<String>json("$.codigo")).isEqualTo("FALTA_MOTIVO");
    Respuesta decidida =
        decidirTodas(administracion, cargaFrentes, "NO_ES_FRENTE", "DESCARTAR", "Es el almacén");
    assertThat(decidida.estado()).as(decidida.cuerpo()).isEqualTo(200);
    assertThat(numero(decidida, "$.resumen.enCuarentena")).isZero();
    assertThat(numero(decidida, "$.conciliacion[0].diferencia")).isZero();

    Respuesta confirmada =
        administracion.post("/api/v1/importaciones/%d/confirmar".formatted(cargaFrentes), null);
    assertThat(confirmada.estado()).as(confirmada.cuerpo()).isEqualTo(200);
    assertThat(confirmada.<String>json("$.resumen.estado")).isEqualTo("confirmada");
    assertThat(numero(confirmada, "$.creados.frente")).isEqualTo(3);
    assertThat(administracion.get("/api/v1/frentes").<List<String>>json("$[*].clave"))
        .containsExactlyInAnyOrder("CORCELES", "GRANADA", "PALERMO");

    // Contratos: el frente del SUA ("CORCELES FRACCIONAMIENTO") se confirma una vez y queda alias
    Respuesta contratos = subir(administracion, "CONTRATOS", "muestra-sua.xlsx", null);
    assertThat(contratos.estado()).as(contratos.cuerpo()).isEqualTo(201);
    long cargaContratos = numero(contratos, "$.id");
    assertThat(numero(contratos, "$.renglones")).isEqualTo(4);
    Respuesta aceptadas =
        decidirTodas(administracion, cargaContratos, "FRENTE_POR_CONFIRMAR", "ACEPTAR", null);
    assertThat(aceptadas.estado()).as(aceptadas.cuerpo()).isEqualTo(200);
    decidirTodas(
        administracion, cargaContratos, "FECHAS_INVERTIDAS", "DESCARTAR", "Fechas al revés");
    Respuesta contratosConfirmados =
        administracion.post("/api/v1/importaciones/%d/confirmar".formatted(cargaContratos), null);
    assertThat(contratosConfirmados.estado()).as(contratosConfirmados.cuerpo()).isEqualTo(200);
    assertThat(administracion.get("/api/v1/contratos").<List<String>>json("$[*].codigo"))
        .contains("60-AAA-MF6-C002-2026", "60-BBB-MEK-C001-2026", "60-CCC-MEO-C001-2026");
    assertThat(administracion.get("/api/v1/alias?entidad=frente").<List<String>>json("$[*].texto"))
        .contains("CORCELES FRACCIONAMIENTO", "QUINTA GRANADA", "PALERMO RESIDENCIAL");

    // Los frentes ya los usan los contratos: revertirlos falla y no borra nada
    Respuesta enUso =
        administracion.post("/api/v1/importaciones/%d/revertir".formatted(cargaFrentes), null);
    assertThat(enUso.estado()).isEqualTo(409);
    assertThat(enUso.<String>json("$.codigo")).isEqualTo("CARGA_EN_USO");
    assertThat(administracion.get("/api/v1/frentes").<List<String>>json("$[*].clave")).hasSize(3);

    // Revertir contratos: desaparecen completos, con sus alias, y el archivo se puede volver a
    // subir
    Respuesta revertida =
        administracion.post("/api/v1/importaciones/%d/revertir".formatted(cargaContratos), null);
    assertThat(revertida.estado()).as(revertida.cuerpo()).isEqualTo(200);
    assertThat(revertida.<String>json("$.resumen.estado")).isEqualTo("revertida");
    assertThat(administracion.get("/api/v1/contratos").<List<String>>json("$[*].codigo")).isEmpty();
    assertThat(administracion.get("/api/v1/alias?entidad=frente").<List<String>>json("$[*].texto"))
        .isEmpty();
    Respuesta denuevo = subir(administracion, "CONTRATOS", "muestra-sua.xlsx", null);
    assertThat(denuevo.estado()).isEqualTo(201);
    assertThat(numero(denuevo, "$.id")).isNotEqualTo(cargaContratos);

    // La bitácora de cargas muestra quién subió qué
    Respuesta bitacora = administracion.get("/api/v1/importaciones");
    assertThat(bitacora.<List<String>>json("$[*].estado"))
        .contains("confirmada", "revertida", "validada");
  }

  @Test
  void materialesConUnidadDeControlYPrecioPonderado() throws IOException {
    ClienteHttp administracion = sesion(admin);
    Respuesta carga = subir(administracion, "MATERIALES", "muestra-almacen.xlsx", null);
    assertThat(carga.estado()).as(carga.cuerpo()).isEqualTo(201);
    long id = numero(carga, "$.id");

    Respuesta abrazadera =
        administracion.get("/api/v1/importaciones/%d/filas?q=Abrazadera".formatted(id));
    assertThat(abrazadera.<String>json("$.elementos[0].normal.precio")).isEqualTo("1.700000");
    assertThat(abrazadera.<List<String>>json("$.elementos[0].incidencias[*].regla"))
        .contains("UNIDAD_NORMALIZADA", "PRECIO_PONDERADO");

    // "Juego" no es una unidad conocida: se elige cuál es y queda como alias
    Respuesta juego =
        administracion.get("/api/v1/importaciones/%d/filas?regla=UNIDAD_DESCONOCIDA".formatted(id));
    int renglon = juego.json("$.elementos[0].numero");
    long pieza =
        numero(
            administracion.get("/api/v1/importaciones/destinos?entidad=unidad_medida&q=pza"),
            "$[0].id");
    Respuesta elegida =
        administracion.post(
            "/api/v1/importaciones/%d/decisiones".formatted(id),
            "{\"decisiones\":[{\"numero\":%d,\"regla\":\"UNIDAD_DESCONOCIDA\",\"opcion\":\"ELEGIR\",\"destinoId\":%d}]}"
                .formatted(renglon, pieza));
    assertThat(elegida.estado()).as(elegida.cuerpo()).isEqualTo(200);

    // Los materiales que solo aparecen en movimientos se dan de alta con su unidad
    Respuesta sueltos =
        administracion.get(
            "/api/v1/importaciones/%d/filas?regla=MATERIAL_SOLO_EN_MOVIMIENTOS&tamano=200"
                .formatted(id));
    List<String> decisiones = new ArrayList<>();
    for (Integer n : sueltos.<List<Integer>>json("$.elementos[*].numero")) {
      decisiones.add(
          "{\"numero\":%d,\"regla\":\"MATERIAL_SOLO_EN_MOVIMIENTOS\",\"opcion\":\"VALOR\",\"valor\":\"PZA\"}"
              .formatted(n));
    }
    assertThat(
            administracion
                .post(
                    "/api/v1/importaciones/%d/decisiones".formatted(id),
                    "{\"decisiones\":[" + String.join(",", decisiones) + "]}")
                .estado())
        .isEqualTo(200);
    Respuesta confirmada =
        administracion.post("/api/v1/importaciones/%d/confirmar".formatted(id), null);
    assertThat(confirmada.estado()).as(confirmada.cuerpo()).isEqualTo(200);

    Respuesta materiales = administracion.get("/api/v1/materiales?tamano=200");
    assertThat(numero(materiales, "$.total")).isEqualTo(12);
    assertThat(materiales.<List<String>>json("$.elementos[*].unidadControl")).doesNotContainNull();
    assertThat(
            administracion
                .get("/api/v1/materiales?q=abrazadera")
                .<Double>json("$.elementos[0].precioReferencia"))
        .isEqualTo(1.7);
  }

  @Test
  void personasDelSuaLigadasOMarcadasParaRevision() throws IOException {
    ClienteHttp administracion = sesion(admin);
    Respuesta carga = subir(administracion, "PERSONAS_SUA", "muestra-sua.xlsx", null);
    assertThat(carga.estado()).as(carga.cuerpo()).isEqualTo(201);
    long id = numero(carga, "$.id");
    assertThat(numero(carga, "$.enCuarentena")).isEqualTo(2);
    decidirTodas(administracion, id, "POSIBLE_DUPLICADO", "ACEPTAR", null);
    Respuesta confirmada =
        administracion.post("/api/v1/importaciones/%d/confirmar".formatted(id), null);
    assertThat(confirmada.estado()).as(confirmada.cuerpo()).isEqualTo(200);
    assertThat(numero(confirmada, "$.creados.persona")).isEqualTo(6);

    Respuesta personas = administracion.get("/api/v1/personas?q=garza");
    assertThat(personas.<List<String>>json("$[*].nombre")).containsExactly("GARZA LEAL EDUARDO");
    long persona = numero(personas, "$[0].id");
    int version = personas.json("$[0].version");
    Respuesta nss =
        administracion.patch(
            "/api/v1/personas/" + persona,
            "{\"nombre\":\"GARZA LEAL EDUARDO\",\"nss\":\"12345678901\"}",
            version);
    assertThat(nss.estado()).as(nss.cuerpo()).isEqualTo(200);
    assertThat(nss.<String>json("$.nss")).isEqualTo("12345678901");

    // Destajo: ningún apodo se da de alta solo
    Respuesta destajo = subir(administracion, "PERSONAS_DESTAJO", "muestra-destajo.xlsx", null);
    assertThat(destajo.estado()).as(destajo.cuerpo()).isEqualTo(201);
    assertThat(numero(destajo, "$.porCrear")).isZero();
    assertThat(numero(destajo, "$.enCuarentena")).isPositive();
  }

  @Test
  void soloAdministracionYSoloSuEmpresa() throws IOException {
    ClienteHttp administracion = sesion(admin);
    long id = numero(subir(administracion, "PROVEEDORES", "muestra-almacen.xlsx", null), "$.id");

    ClienteHttp almacenista = sesion(almacen);
    assertThat(almacenista.get("/api/v1/importaciones").estado()).isEqualTo(403);
    assertThat(almacenista.get("/api/v1/personas").estado()).isEqualTo(403);

    Empresa otra = crearEmpresa("H3-otra");
    String ajeno = unico("admin.otra");
    crearUsuario(otra, ajeno, CONTRASENA, "admin");
    ClienteHttp otraEmpresa = sesion(ajeno);
    assertThat(otraEmpresa.get("/api/v1/importaciones/" + id).estado()).isEqualTo(404);
    assertThat(otraEmpresa.get("/api/v1/importaciones").<List<Object>>json("$")).isEmpty();

    Respuesta noEsExcel =
        administracion.subir(
            "/api/v1/importaciones",
            Map.of("tipo", "PROVEEDORES"),
            "notas.xlsx",
            "no soy un excel".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(noEsExcel.estado()).isEqualTo(400);
    assertThat(noEsExcel.<String>json("$.codigo")).isEqualTo("ARCHIVO_INVALIDO");
  }
}

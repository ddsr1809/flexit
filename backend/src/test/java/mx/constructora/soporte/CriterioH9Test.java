package mx.constructora.soporte;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import mx.constructora.ClienteHttp;
import mx.constructora.ClienteHttp.Respuesta;
import mx.constructora.PruebaIntegracion;
import mx.constructora.compartido.Folios;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Criterio de aceptación de H9 por HTTP real, contra PostgreSQL:
 *
 * <ul>
 *   <li>Cualquiera reporta un incidente; administración lo clasifica, lo resuelve o lo descarta, y
 *       quien lo reportó lo reabre. Nadie más lo ve.
 *   <li>El reporte de estabilización cuenta las capturas tardías (más de un día) y dice si se
 *       cumple el criterio: dos semanas a tiempo, cero críticos abiertos y el primer cierre.
 *   <li>El cierre de mes no deja un conteo abierto que después ya no se podría cerrar; cerrado, se
 *       descarga el acta.
 * </ul>
 */
class CriterioH9Test extends PruebaIntegracion {

  private static final String CONTRASENA = "contrasena-de-prueba";

  private Empresa empresa;
  private String admin;
  private String almacenista;
  private String otroAlmacenista;
  private String coordinacion;
  private long almacenistaId;
  private long almacen;
  private LocalDate hoy;

  @BeforeEach
  void preparar() {
    empresa = crearEmpresa("H9");
    admin = unico("admin.h9");
    almacenista = unico("almacen.h9");
    otroAlmacenista = unico("almacen2.h9");
    coordinacion = unico("coord.h9");
    crearUsuario(empresa, admin, CONTRASENA, "admin");
    almacenistaId = crearUsuario(empresa, almacenista, CONTRASENA, "almacenista");
    crearUsuario(empresa, otroAlmacenista, CONTRASENA, "almacenista");
    crearUsuario(empresa, coordinacion, CONTRASENA, "coordinacion");
    hoy = LocalDate.now(Folios.ZONA);

    JdbcClient jdbc = comoMigrador();
    long cliente =
        insertar(
            jdbc,
            "INSERT INTO estructura.cliente (empresa_id, nombre) VALUES ("
                + empresa.id()
                + ", 'Ruba')");
    long frente =
        insertar(
            jdbc,
            "INSERT INTO estructura.frente (empresa_id, cliente_id, clave, nombre) VALUES ("
                + empresa.id()
                + ", "
                + cliente
                + ", 'PILOTO', 'Frente piloto')");
    almacen =
        id(
            sesion(admin)
                .post(
                    "/api/v1/almacenes",
                    "{\"frenteId\":%d,\"clave\":\"PIL\",\"nombre\":\"Almacén piloto\"}"
                        .formatted(frente)));
  }

  @Test
  void incidentesDelPiloto() {
    ClienteHttp almacen = sesion(almacenista);
    ClienteHttp adminUno = sesion(admin);

    // 1. El almacén reporta un crítico con la pantalla y la petición que falló.
    Respuesta reportado =
        almacen.post(
            "/api/v1/incidentes",
            """
            {"severidad":"critica","titulo":"No deja aplicar el vale",
             "descripcion":"Sale un error al aplicar y no se puede entregar","almacenId":%d,
             "pantalla":"/inventario/movimientos/7",
             "peticion":"a1b2c3d4-0000-4000-8000-000000000001",
             "documento":"sal-2027-000007"}
            """
                .formatted(this.almacen));
    assertThat(reportado.estado()).as(reportado.cuerpo()).isEqualTo(201);
    long id = numero(reportado, "$.id");
    assertThat(reportado.<String>json("$.folio")).startsWith("INC-");
    assertThat(reportado.<String>json("$.estado")).isEqualTo("abierto");
    assertThat(reportado.<String>json("$.documento")).isEqualTo("SAL-2027-000007");
    assertThat(reportado.<String>json("$.almacen")).isEqualTo("PIL");
    assertThat(reportado.<Boolean>json("$.puedeAtender")).isFalse();
    assertThat(
            codigo(
                almacen.post(
                    "/api/v1/incidentes",
                    "{\"severidad\":\"urgente\",\"titulo\":\"Otro\",\"descripcion\":\"x\"}")))
        .isEqualTo("VALIDACION");

    // 2. Solo lo ven quien lo reportó y quien atiende.
    assertThat(almacen.get("/api/v1/incidentes").<List<Object>>json("$")).hasSize(1);
    ClienteHttp otro = sesion(otroAlmacenista);
    assertThat(otro.get("/api/v1/incidentes").<List<Object>>json("$")).isEmpty();
    assertThat(otro.get("/api/v1/incidentes/" + id).estado()).isEqualTo(404);
    assertThat(adminUno.get("/api/v1/incidentes?severidad=critica").<List<Object>>json("$"))
        .hasSize(1);

    // 3. El almacén no lo atiende; administración lo clasifica con If-Match.
    assertThat(almacen.post(resolver(id), texto("Ya quedó")).estado()).isEqualTo(403);
    int version = adminUno.get("/api/v1/incidentes/" + id).<Integer>json("$.version");
    Respuesta clasificado =
        adminUno.patch(
            "/api/v1/incidentes/" + id,
            "{\"severidad\":\"mayor\",\"issue\":57,\"nota\":\"Hay un rodeo: capturar de nuevo\"}",
            version);
    assertThat(clasificado.estado()).as(clasificado.cuerpo()).isEqualTo(200);
    assertThat(clasificado.<String>json("$.severidad")).isEqualTo("mayor");
    assertThat(clasificado.<Integer>json("$.issue")).isEqualTo(57);
    assertThat(clasificado.<List<String>>json("$.notas[*].tipo"))
        .containsExactly("severidad", "issue", "nota");
    assertThat(
            adminUno
                .patch("/api/v1/incidentes/" + id, "{\"severidad\":\"critica\"}", version)
                .estado())
        .as("versión vieja")
        .isEqualTo(412);

    // 4. Se resuelve; sigue pasando y el almacén lo reabre; al final se descarta.
    Respuesta resuelto = adminUno.post(resolver(id), texto("Corregido en la versión 1.9.1"));
    assertThat(resuelto.<String>json("$.estado")).isEqualTo("resuelto");
    assertThat(resuelto.<String>json("$.resolucion")).isEqualTo("Corregido en la versión 1.9.1");
    assertThat(codigo(adminUno.post(resolver(id), texto("Otra vez"))))
        .isEqualTo("INCIDENTE_CERRADO");
    Respuesta reabierto =
        almacen.post("/api/v1/incidentes/" + id + "/reabrir", texto("Volvió a pasar hoy"));
    assertThat(reabierto.<String>json("$.estado")).isEqualTo("abierto");
    assertThat(reabierto.<Integer>json("$.reaperturas")).isEqualTo(1);
    assertThat(reabierto.<String>json("$.resolucion")).isNull();
    Respuesta nota = almacen.post("/api/v1/incidentes/" + id + "/notas", texto("Fue el vale 8"));
    assertThat(nota.estado()).isEqualTo(200);
    Respuesta descartado =
        adminUno.post("/api/v1/incidentes/" + id + "/descartar", texto("Repetido de INC-2"));
    assertThat(descartado.<String>json("$.estado")).isEqualTo("descartado");
    assertThat(descartado.<List<String>>json("$.notas[*].tipo"))
        .containsExactly(
            "severidad", "issue", "nota", "resuelto", "reabierto", "nota", "descartado");
  }

  @Test
  void reporteDeEstabilizacionYCriterio() {
    // Un vale capturado hoy con fecha de hace 5 días (tardío) y uno de ayer (a tiempo).
    capturarSinAplicar("SAL-H9-000001", hoy.minusDays(5));
    capturarSinAplicar("SAL-H9-000002", hoy.minusDays(1));
    sesion(almacenista)
        .post(
            "/api/v1/incidentes",
            "{\"severidad\":\"critica\",\"titulo\":\"No imprime el vale\",\"descripcion\":\"x\"}");

    String ruta =
        "/api/v1/reportes/estabilizacion?almacenId=%d&desde=%s"
            .formatted(almacen, hoy.minusDays(6));
    assertThat(sesion(almacenista).get(ruta).estado()).isEqualTo(403);
    Respuesta r = sesion(coordinacion).get(ruta);
    assertThat(r.estado()).as(r.cuerpo()).isEqualTo(200);
    assertThat(r.<List<Object>>json("$.dias")).hasSize(7);
    assertThat(r.<Integer>json("$.indicadores.documentos")).isEqualTo(2);
    assertThat(r.<Integer>json("$.indicadores.tardios")).isEqualTo(1);
    assertThat(r.<Integer>json("$.indicadores.pendientes")).isEqualTo(2);
    assertThat(r.<Integer>json("$.indicadores.pendientesViejos")).isEqualTo(1);
    assertThat(r.<String>json("$.tardios[0].folio")).isEqualTo("SAL-H9-000001");
    assertThat(r.<Integer>json("$.tardios[0].dias")).isEqualTo(5);
    assertThat(r.<Integer>json("$.criterio.racha"))
        .as("del día siguiente al tardío a hoy")
        .isEqualTo(5);
    assertThat(r.<Boolean>json("$.criterio.dosSemanasATiempo")).isFalse();
    assertThat(r.<Integer>json("$.criterio.criticosAbiertos")).isEqualTo(1);
    assertThat(r.<Boolean>json("$.criterio.primerCierre")).as("no ha abierto").isFalse();
    assertThat(r.<Boolean>json("$.criterio.cumple")).isFalse();
    assertThat(sesion(admin).get(ruta + "&formato=xlsx").estado()).isEqualTo(200);
  }

  @Test
  void elCierreNoDejaUnConteoAbierto() {
    YearMonth anterior = YearMonth.from(hoy).minusMonths(1);
    ClienteHttp adminUno = sesion(admin);
    Respuesta conteo =
        sesion(almacenista)
            .post(
                "/api/v1/conteos",
                "{\"almacenId\":%d,\"fecha\":\"%s\"}".formatted(almacen, anterior.atDay(20)));
    assertThat(conteo.estado()).as(conteo.cuerpo()).isEqualTo(201);
    long conteoId = numero(conteo, "$.conteo.id");

    String mes = "/api/v1/periodos/%d/%d".formatted(anterior.getYear(), anterior.getMonthValue());
    Respuesta revision = adminUno.get(mes + "/revision");
    assertThat(revision.estado()).as(revision.cuerpo()).isEqualTo(200);
    assertThat(revision.<Boolean>json("$.sePuedeCerrar")).isFalse();
    assertThat(revision.<List<String>>json("$.bloqueos[*].codigo"))
        .containsExactly("MES_CON_CONTEO_ABIERTO");
    assertThat(codigo(adminUno.post(mes + "/cerrar", ""))).isEqualTo("MES_CON_CONTEO_ABIERTO");
    assertThat(codigo(adminUno.get(mes + "/acta.xlsx"))).isEqualTo("MES_ABIERTO");

    assertThat(sesion(almacenista).post("/api/v1/conteos/" + conteoId + "/cancelar", "").estado())
        .isEqualTo(200);
    assertThat(adminUno.get(mes + "/revision").<Boolean>json("$.sePuedeCerrar")).isTrue();
    Respuesta cerrado = adminUno.post(mes + "/cerrar", "");
    assertThat(cerrado.estado()).as(cerrado.cuerpo()).isEqualTo(200);
    assertThat(adminUno.get(mes + "/revision").<Boolean>json("$.cerrado")).isTrue();
    assertThat(adminUno.get(mes + "/acta.xlsx").estado()).isEqualTo(200);
  }

  // ------------------------------------------------------------------------------ auxiliares

  /** Un vale en borrador con fecha {@code fecha}, capturado ahora (lo que hace la captura). */
  private void capturarSinAplicar(String folio, LocalDate fecha) {
    comoMigrador()
        .sql(
            "INSERT INTO inventario.movimiento (empresa_id, folio, tipo, fecha, almacen_id,"
                + " usuario_captura) VALUES (:e, :folio, 'salida', :fecha, :a, :u)")
        .param("e", empresa.id())
        .param("folio", folio)
        .param("fecha", fecha)
        .param("a", almacen)
        .param("u", almacenistaId)
        .update();
  }

  private static String resolver(long id) {
    return "/api/v1/incidentes/" + id + "/resolver";
  }

  private static String texto(String texto) {
    return "{\"texto\":\"" + texto + "\"}";
  }

  private static String codigo(Respuesta r) {
    assertThat(r.estado()).as(r.cuerpo()).isGreaterThanOrEqualTo(400);
    return r.json("$.codigo");
  }

  private static long numero(Respuesta r, String ruta) {
    return ((Number) r.json(ruta)).longValue();
  }

  private ClienteHttp sesion(String usuario) {
    ClienteHttp navegador = cliente();
    assertThat(navegador.iniciarSesion(usuario, CONTRASENA).estado()).isEqualTo(200);
    return navegador;
  }

  private static long id(Respuesta respuesta) {
    assertThat(respuesta.estado()).as(respuesta.cuerpo()).isIn(200, 201);
    return ((Number) respuesta.json("$.id")).longValue();
  }

  private static long insertar(JdbcClient jdbc, String sql) {
    return jdbc.sql(sql + " RETURNING id").query(Long.class).single();
  }
}

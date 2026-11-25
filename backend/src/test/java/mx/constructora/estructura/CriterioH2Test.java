package mx.constructora.estructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import mx.constructora.ClienteHttp;
import mx.constructora.ClienteHttp.Respuesta;
import mx.constructora.PruebaIntegracion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Criterio de aceptación de H2, por HTTP real: un usuario captura a mano un contrato con 3 lotes y
 * 5 materiales, y el residente solo ve los lotes de su contrato. Más las reglas que lo protegen:
 * versión en cada cambio, separación entre empresas y precios solo para quien los ve.
 */
class CriterioH2Test extends PruebaIntegracion {

  private static final String CONTRASENA = "contrasena-de-prueba";

  private Empresa empresa;
  private long residenteId;
  private String admin;
  private String residente;
  private String almacen;
  private String compras;

  @BeforeEach
  void preparar() {
    empresa = crearEmpresa("H2");
    admin = unico("admin.h2");
    residente = unico("residente.h2");
    almacen = unico("almacen.h2");
    compras = unico("compras.h2");
    crearUsuario(empresa, admin, CONTRASENA, "admin");
    residenteId = crearUsuario(empresa, residente, CONTRASENA, "residente");
    crearUsuario(empresa, almacen, CONTRASENA, "almacenista");
    crearUsuario(empresa, compras, CONTRASENA, "compras");
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

  /** Cliente, frente, prototipo y el contrato del demo; devuelve el id del contrato. */
  private long contratoDelDemo(ClienteHttp administracion) {
    long cliente =
        id(
            administracion.post(
                "/api/v1/clientes", "{\"nombre\":\"Ruba\",\"rfc\":\"rde010101ab1\"}"));
    long frente =
        id(
            administracion.post(
                "/api/v1/frentes",
                "{\"clienteId\":%d,\"clave\":\"corceles\",\"nombre\":\"Corceles\"}"
                    .formatted(cliente)));
    long contrato =
        id(
            administracion.post(
                "/api/v1/contratos",
                """
                {"clienteId":%d,"frenteId":%d,"codigo":"60-kz7-mf6/C002/2026",
                 "numeroObra":"C1269209","inicio":"2026-07-12","fin":"2027-03-28",
                 "monto":4882033.11}
                """
                    .formatted(cliente, frente)));
    return contrato;
  }

  @Test
  void unContratoConTresLotesYCincoMaterialesSeCapturaAMano() {
    ClienteHttp administracion = sesion(admin);
    long prototipo =
        id(
            administracion.post(
                "/api/v1/prototipos", "{\"clave\":\"p-69\",\"nombre\":\"Prototipo 69\"}"));
    long contrato = contratoDelDemo(administracion);

    Respuesta leido = administracion.get("/api/v1/contratos/" + contrato);
    assertThat(leido.<String>json("$.codigo")).isEqualTo("60-KZ7-MF6-C002-2026");
    assertThat(leido.<String>json("$.estado")).isEqualTo("activo");

    Respuesta lotes =
        administracion.post(
            "/api/v1/contratos/%d/unidades".formatted(contrato),
            "{\"tipo\":\"LOTE\",\"manzana\":\"20\",\"lotes\":\"29-31\",\"prototipoId\":%d}"
                .formatted(prototipo));
    assertThat(lotes.estado()).as(lotes.cuerpo()).isEqualTo(201);
    assertThat(lotes.<List<String>>json("$[*].clave"))
        .containsExactly("M20-L29", "M20-L30", "M20-L31");
    assertThat(lotes.<List<String>>json("$[*].prototipo")).containsOnly("P-69");

    Respuesta repetido =
        administracion.post(
            "/api/v1/contratos/%d/unidades".formatted(contrato),
            "{\"tipo\":\"LOTE\",\"manzana\":\"20\",\"lotes\":\"31, 32\"}");
    assertThat(repetido.estado()).isEqualTo(409);
    assertThat(repetido.<String>json("$.codigo")).isEqualTo("UNIDAD_DUPLICADA");

    long pieza = unidadDelSistema(administracion, "PZA");
    for (String nombre :
        List.of(
            "Cemento gris 50 kg",
            "Adeblock plus AP Cemix 40 kg",
            "Abrazadera omega de 3/4\\\"",
            "Cable THW cal. 12",
            "Lavamanos blanco")) {
      Respuesta material =
          administracion.post(
              "/api/v1/materiales",
              "{\"nombre\":\"%s\",\"unidadControlId\":%d,\"precioReferencia\":10}"
                  .formatted(nombre, pieza));
      assertThat(material.estado()).as(material.cuerpo()).isEqualTo(201);
    }

    Respuesta pagina = administracion.get("/api/v1/materiales?tamano=10");
    assertThat(pagina.<Integer>json("$.total")).isEqualTo(5);
    assertThat(pagina.<List<String>>json("$.elementos[*].clave"))
        .containsExactlyInAnyOrder("001", "002", "003", "004", "005");

    Respuesta busqueda = administracion.get("/api/v1/materiales/buscar?q=cement%20GRIS");
    assertThat(busqueda.<List<String>>json("$[*].nombre")).containsExactly("Cemento gris 50 kg");

    Respuesta duplicado =
        administracion.post(
            "/api/v1/materiales",
            "{\"nombre\":\"  CEMENTO   GRÍS 50 KG\",\"unidadControlId\":%d}".formatted(pieza));
    assertThat(duplicado.estado()).isEqualTo(409);
    assertThat(duplicado.<String>json("$.codigo")).isEqualTo("MATERIAL_DUPLICADO");
  }

  @Test
  void elResidenteSoloVeLosLotesDeSuContrato() {
    ClienteHttp administracion = sesion(admin);
    long suyo = contratoDelDemo(administracion);
    long cliente = clienteDe(administracion, suyo);
    long ajeno =
        id(
            administracion.post(
                "/api/v1/contratos",
                "{\"clienteId\":%d,\"codigo\":\"60-KZ7-ME6-C002-2026\"}".formatted(cliente)));
    administracion.post(
        "/api/v1/contratos/%d/unidades".formatted(suyo),
        "{\"tipo\":\"LOTE\",\"manzana\":\"16\",\"lotes\":\"34\"}");
    administracion.post(
        "/api/v1/contratos/%d/unidades".formatted(ajeno),
        "{\"tipo\":\"LOTE\",\"manzana\":\"21\",\"lotes\":\"1-2\"}");

    assertThat(administracion.get("/api/v1/residentes").<List<String>>json("$[*].usuario"))
        .containsExactly(residente);
    Respuesta asignados =
        administracion.put(
            "/api/v1/contratos/%d/residentes".formatted(suyo),
            "{\"usuarioIds\":[%d]}".formatted(residenteId));
    assertThat(asignados.<List<String>>json("$[*].usuario")).containsExactly(residente);

    ClienteHttp obra = sesion(residente);
    assertThat(obra.get("/api/v1/contratos").<List<Integer>>json("$[*].id"))
        .containsExactly((int) suyo);
    Respuesta susLotes = obra.get("/api/v1/contratos/%d/unidades".formatted(suyo));
    assertThat(susLotes.<List<String>>json("$[*].clave")).containsExactly("M16-L34");
    assertThat(obra.get("/api/v1/contratos/" + ajeno).estado()).isEqualTo(404);
    assertThat(obra.get("/api/v1/contratos/%d/unidades".formatted(ajeno)).estado()).isEqualTo(404);
    assertThat(obra.post("/api/v1/clientes", "{\"nombre\":\"Intruso\"}").estado()).isEqualTo(403);

    // El almacenista ve todos los contratos, pero sin monto.
    ClienteHttp almacenista = sesion(almacen);
    Respuesta todos = almacenista.get("/api/v1/contratos");
    assertThat(todos.<List<Integer>>json("$[*].id")).hasSize(2);
    assertThat(todos.<List<Object>>json("$[*].monto")).containsOnlyNulls();
  }

  @Test
  void unCambioConVersionVencidaNoPisaAlOtro() {
    ClienteHttp administracion = sesion(admin);
    long contrato = contratoDelDemo(administracion);
    Respuesta leido = administracion.get("/api/v1/contratos/" + contrato);
    int version = leido.json("$.version");
    String cuerpo =
        "{\"clienteId\":%d,\"frenteId\":%d,\"codigo\":\"60-KZ7-MF6-C002-2026\",\"monto\":5000000}"
            .formatted(
                ((Number) leido.json("$.clienteId")).longValue(),
                ((Number) leido.json("$.frenteId")).longValue());

    Respuesta primero = administracion.patch("/api/v1/contratos/" + contrato, cuerpo, version);
    assertThat(primero.estado()).as(primero.cuerpo()).isEqualTo(200);
    assertThat(primero.<Integer>json("$.version")).isEqualTo(version + 1);

    Respuesta segundo = administracion.patch("/api/v1/contratos/" + contrato, cuerpo, version);
    assertThat(segundo.estado()).isEqualTo(412);
    assertThat(segundo.<String>json("$.codigo")).isEqualTo("VERSION_DESACTUALIZADA");

    Respuesta sinVersion = administracion.patch("/api/v1/contratos/" + contrato, cuerpo, null);
    assertThat(sinVersion.estado()).isEqualTo(428);
  }

  @Test
  void otraEmpresaNoVeNiTocaLaEstructura() {
    long contrato = contratoDelDemo(sesion(admin));

    Empresa otra = crearEmpresa("H2 otra");
    String adminOtra = unico("admin.otra");
    crearUsuario(otra, adminOtra, CONTRASENA, "admin");
    ClienteHttp ajena = sesion(adminOtra);

    assertThat(ajena.get("/api/v1/contratos").<List<Object>>json("$")).isEmpty();
    assertThat(ajena.get("/api/v1/contratos/" + contrato).estado()).isEqualTo(404);
    assertThat(
            ajena
                .post(
                    "/api/v1/contratos/%d/unidades".formatted(contrato),
                    "{\"tipo\":\"LOTE\",\"manzana\":\"1\",\"lotes\":\"1\"}")
                .estado())
        .isEqualTo(404);
    // Las unidades de medida del sistema sí las ve.
    assertThat(ajena.get("/api/v1/unidades-medida").<List<String>>json("$[*].clave"))
        .contains("PZA", "KG");
  }

  @Test
  void elPrecioDeReferenciaSoloLoVeQuienVePrecios() {
    ClienteHttp catalogo = sesion(compras);
    long pieza = unidadDelSistema(catalogo, "PZA");
    long material =
        id(
            catalogo.post(
                "/api/v1/materiales",
                ("{\"clave\":\"L-01\",\"nombre\":\"Lija 220\","
                        + "\"unidadControlId\":%d,\"precioReferencia\":12.5}")
                    .formatted(pieza)));
    assertThat(catalogo.get("/api/v1/materiales/" + material).<Double>json("$.precioReferencia"))
        .isEqualTo(12.5);

    ClienteHttp almacenista = sesion(almacen);
    Respuesta visto = almacenista.get("/api/v1/materiales/" + material);
    assertThat(visto.estado()).isEqualTo(200);
    assertThat(visto.<Object>json("$.precioReferencia")).isNull();
    assertThat(
            almacenista
                .post(
                    "/api/v1/materiales",
                    "{\"nombre\":\"x\",\"unidadControlId\":%d}".formatted(pieza))
                .estado())
        .isEqualTo(403);
  }

  @Test
  void laEquivalenciaVaDelPaqueteDelClienteAlPropioYElPrecioSeActualiza() {
    ClienteHttp administracion = sesion(admin);
    long contrato = contratoDelDemo(administracion);
    long cliente = clienteDe(administracion, contrato);

    long ruba =
        id(
            administracion.post(
                "/api/v1/paquetes",
                "{\"clienteId\":%d,\"clave\":\"1\",\"nombre\":\"Cimentación\"}"
                    .formatted(cliente)));
    long seccion =
        id(administracion.post("/api/v1/paquetes", "{\"clave\":\"1\",\"nombre\":\"Cimentación\"}"));
    long concepto =
        id(
            administracion.post(
                "/api/v1/paquetes",
                ("{\"padreId\":%d,\"clave\":\"1.1\","
                        + "\"nombre\":\"Trazo y excavación\",\"unidad\":\"Lote\"}")
                    .formatted(seccion)));

    assertThat(administracion.get("/api/v1/paquetes?propios=true").<List<String>>json("$[*].clave"))
        .containsExactly("1", "1.1");

    Respuesta equivalencia =
        administracion.put(
            "/api/v1/paquetes/%d/equivalencias".formatted(ruba),
            "{\"paquetePropioIds\":[%d,%d]}".formatted(seccion, concepto));
    assertThat(equivalencia.estado()).as(equivalencia.cuerpo()).isEqualTo(200);
    Respuesta alReves =
        administracion.put(
            "/api/v1/paquetes/%d/equivalencias".formatted(seccion),
            "{\"paquetePropioIds\":[%d]}".formatted(concepto));
    assertThat(alReves.estado()).isEqualTo(400);
    assertThat(alReves.<String>json("$.codigo")).isEqualTo("PAQUETE_NO_ES_DE_CLIENTE");

    String precio = "{\"paqueteId\":%d,\"precioCobro\":%s}";
    String precios = "/api/v1/contratos/%d/precios".formatted(contrato);
    administracion.put(precios, precio.formatted(ruba, "19674.64"));
    Respuesta cambio = administracion.put(precios, precio.formatted(ruba, "20000"));
    assertThat(cambio.<Integer>json("$.version")).isEqualTo(1);
    assertThat(administracion.get(precios).<List<Object>>json("$")).hasSize(1);
  }

  private static long clienteDe(ClienteHttp navegador, long contrato) {
    Number cliente = navegador.get("/api/v1/contratos/" + contrato).json("$.clienteId");
    return cliente.longValue();
  }

  private static long unidadDelSistema(ClienteHttp navegador, String clave) {
    List<Integer> ids =
        navegador.get("/api/v1/unidades-medida").json("$[?(@.clave == '" + clave + "')].id");
    return ids.getFirst();
  }
}

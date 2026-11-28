package mx.constructora.inventario;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import mx.constructora.ClienteHttp;
import mx.constructora.ClienteHttp.Respuesta;
import mx.constructora.PruebaIntegracion;
import mx.constructora.compartido.Folios;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Criterio de aceptación de H8 por HTTP real, contra PostgreSQL:
 *
 * <ul>
 *   <li>El saldo de apertura sale del conteo físico, valuado sin IVA (ponderado del Excel hasta el
 *       corte, precio de referencia o capturado) y lo firman coordinación y administración, dos
 *       personas que no contaron. Con la primera firma se congela; con la segunda se aplica.
 *   <li>Los préstamos abiertos del Excel aparecen como préstamos vivos con su responsable; la
 *       herramienta que no era material se da de alta.
 *   <li>El almacén no abre dos veces ni mueve nada antes de su apertura.
 * </ul>
 */
class CriterioH8Test extends PruebaIntegracion {

  private static final String CONTRASENA = "contrasena-de-prueba";

  private Empresa empresa;
  private String admin;
  private String admin2;
  private String almacenista;
  private String coordinacion;
  private long almacen;
  private long pintura;
  private long cemento;
  private long daniel;
  private long piezas;
  private LocalDate corte;

  @BeforeEach
  void preparar() {
    empresa = crearEmpresa("H8");
    admin = unico("admin.h8");
    admin2 = unico("admin2.h8");
    almacenista = unico("almacen.h8");
    coordinacion = unico("coord.h8");
    long adminId = crearUsuario(empresa, admin, CONTRASENA, "admin");
    crearUsuario(empresa, admin2, CONTRASENA, "admin");
    crearUsuario(empresa, almacenista, CONTRASENA, "almacenista");
    crearUsuario(empresa, coordinacion, CONTRASENA, "coordinacion");
    corte = LocalDate.now(Folios.ZONA).minusDays(1);

    JdbcClient jdbc = comoMigrador();
    long e = empresa.id();
    long cliente =
        insertar(
            jdbc, "INSERT INTO estructura.cliente (empresa_id, nombre) VALUES (" + e + ", 'Ruba')");
    long frente =
        insertar(
            jdbc,
            "INSERT INTO estructura.frente (empresa_id, cliente_id, clave, nombre) VALUES ("
                + e
                + ", "
                + cliente
                + ", 'PALERMO', 'Palermo')");
    pintura = material(jdbc, "001", "Pintura vinílica blanca", "L", null);
    cemento = material(jdbc, "002", "Cemento gris 50 kg", "SAC", "180");
    piezas =
        jdbc.sql("SELECT id FROM catalogo.unidad_medida WHERE clave = 'PZA' AND empresa_id IS NULL")
            .query(Long.class)
            .single();
    daniel =
        insertar(
            jdbc,
            "INSERT INTO organizacion.persona (empresa_id, nombre) VALUES ("
                + e
                + ", 'Daniel Responsable')");

    // Lo que dejó la migración del Excel: entradas de pintura (ponderado $55 hasta el corte; la de
    // hoy no cuenta), un préstamo abierto del frente y la foto de Existencias.
    String sha = "b".repeat(64);
    jdbc.sql(
            "INSERT INTO migracion.archivo (empresa_id, sha256, nombre, tamano, contenido,"
                + " usuario_id) VALUES (:e, :sha, 'almacen.xlsx', 3, '\\x010203', :u)")
        .param("e", e)
        .param("sha", sha)
        .param("u", adminId)
        .update();
    long carga =
        insertar(
            jdbc,
            "INSERT INTO migracion.carga (empresa_id, tipo, archivo, sha256, hojas,"
                + " version_reglas, usuario_id) VALUES ("
                + e
                + ", 'HIST_ENTRADAS', 'almacen.xlsx', '"
                + sha
                + "', 'Entradas', 'h8', "
                + adminId
                + ")");
    int numero = 1;
    for (Object[] entrada :
        List.of(
            new Object[] {corte.minusDays(20), "10", "50"},
            new Object[] {corte.minusDays(10), "10", "60"},
            new Object[] {corte.plusDays(1), "1", "100"})) {
      jdbc.sql(
              "INSERT INTO historico.entrada (empresa_id, fecha, material_id, cantidad,"
                  + " precio_unitario, importe, tasa_iva, calidad, carga_id, numero, hoja)"
                  + " VALUES (:e, :f, :m, :q, :p, :q * :p, 0.16, 'valida', :c, :n, 'Entradas')")
          .param("e", e)
          .param("f", entrada[0])
          .param("m", pintura)
          .param("q", new BigDecimal((String) entrada[1]))
          .param("p", new BigDecimal((String) entrada[2]))
          .param("c", carga)
          .param("n", numero++)
          .update();
    }
    jdbc.sql(
            "INSERT INTO historico.prestamo (empresa_id, fecha, articulo, cantidad, frente_id,"
                + " solicitante_texto, entregar_a_id, entregar_a_texto, devuelto, calidad,"
                + " carga_id, numero, hoja, fila_excel) VALUES (:e, :f, 'Compresor grande naranja',"
                + " 1, :fr, 'Daniel', :d, 'Daniel', false, 'valida', :c, 10, 'Prestamo', 4)")
        .param("e", e)
        .param("f", corte.minusDays(40))
        .param("fr", frente)
        .param("d", daniel)
        .param("c", carga)
        .update();
    jdbc.sql(
            "INSERT INTO historico.saldo_excel (empresa_id, material_id, inventario_inicial,"
                + " calidad, carga_id, numero, hoja) VALUES (:e, :m, 5, 'valida', :c, 20,"
                + " 'Existencias')")
        .param("e", e)
        .param("m", pintura)
        .param("c", carga)
        .update();

    almacen =
        id(
            sesion(admin)
                .post(
                    "/api/v1/almacenes",
                    "{\"frenteId\":%d,\"clave\":\"PAL\",\"nombre\":\"Almacén Palermo\"}"
                        .formatted(frente)));
  }

  @Test
  void saldoDeAperturaFirmadoConPrestamosVivos() {
    ClienteHttp almacen = sesion(almacenista);
    ClienteHttp adminUno = sesion(admin);

    // 1. Abrir: conteo a ciegas con todos los materiales y el préstamo abierto del Excel.
    Respuesta abierta = almacen.post("/api/v1/aperturas", abrir());
    assertThat(abierta.estado()).as(abierta.cuerpo()).isEqualTo(201);
    long apertura = numero(abierta, "$.apertura.id");
    long conteo = numero(abierta, "$.apertura.conteoId");
    assertThat(abierta.<String>json("$.apertura.estado")).isEqualTo("abierta");
    assertThat(abierta.<List<Object>>json("$.lineas")).hasSize(2);
    assertThat(abierta.<List<Object>>json("$.lineas[*].costoUnitario"))
        .as("a ciegas: el almacén no ve costos")
        .containsOnlyNulls();
    assertThat(abierta.<String>json("$.prestamos[0].articulo"))
        .isEqualTo("Compresor grande naranja");
    assertThat(numero(abierta, "$.prestamos[0].responsableId")).isEqualTo(daniel);
    assertThat(codigo(almacen.post("/api/v1/aperturas", abrir()))).isEqualTo("APERTURA_EXISTENTE");

    // 2. Contar: 12 L de pintura; lo demás no se encontró.
    assertThat(
            almacen
                .put(
                    "/api/v1/conteos/" + conteo + "/lineas",
                    "{\"lineas\":[{\"materialId\":%d,\"contado\":12}]}".formatted(pintura))
                .estado())
        .isEqualTo(200);
    assertThat(codigo(adminUno.post("/api/v1/conteos/" + conteo + "/cerrar", "")))
        .isEqualTo("CONTEO_DE_APERTURA");

    // 3. Préstamo: la herramienta se da de alta y queda lista con su responsable.
    long prestamo = numero(abierta, "$.prestamos[0].id");
    Respuesta herramienta =
        adminUno.post(
            "/api/v1/aperturas/%d/prestamos/%d/herramienta".formatted(apertura, prestamo),
            "{\"unidadId\":%d}".formatted(piezas));
    assertThat(herramienta.estado()).as(herramienta.cuerpo()).isEqualTo(200);
    long compresor = numero(herramienta, "$.prestamos[0].materialId");
    int version = herramienta.<Integer>json("$.prestamos[0].version");
    Respuesta listo =
        adminUno.patch(
            "/api/v1/aperturas/%d/prestamos/%d".formatted(apertura, prestamo),
            "{\"materialId\":%d,\"responsableId\":%d,\"cantidad\":1,\"estado\":\"listo\"}"
                .formatted(compresor, daniel),
            version);
    assertThat(listo.estado()).as(listo.cuerpo()).isEqualTo(200);
    assertThat(almacen.post("/api/v1/aperturas/" + apertura + "/sin-contar-en-cero", "").estado())
        .isEqualTo(200);

    // 4. Costos: pintura al ponderado del Excel, cemento sin existencia, el compresor se captura.
    Respuesta revision = adminUno.get("/api/v1/aperturas/" + apertura);
    assertThat(linea(revision, pintura, "costoUnitario")).isEqualByComparingTo("55");
    assertThat(revision.<List<String>>json(ruta(pintura, "origenCosto")))
        .containsExactly("historico");
    assertThat(linea(revision, pintura, "existenciaExcel"))
        .as("5 inicial + 21 entradas del Excel")
        .isEqualByComparingTo("26");
    assertThat(revision.<List<String>>json("$.faltaParaFirmar")).hasSize(1);
    assertThat(
            adminUno
                .put(
                    "/api/v1/aperturas/" + apertura + "/costos",
                    "{\"costos\":[{\"materialId\":%d,\"costoUnitario\":8500}]}"
                        .formatted(compresor))
                .estado())
        .isEqualTo(200);
    revision = adminUno.get("/api/v1/aperturas/" + apertura);
    assertThat(revision.<List<String>>json("$.faltaParaFirmar")).isEmpty();
    assertThat(new BigDecimal(revision.json("$.resumen.importe").toString()))
        .isEqualByComparingTo("9160");
    String huella = revision.json("$.huella");

    // 5. Firmas: el almacenista no firma; coordinación congela; administración aplica.
    assertThat(almacen.post(firmar(apertura), firma(huella)).estado()).isEqualTo(403);
    Respuesta primera = sesion(coordinacion).post(firmar(apertura), firma(huella));
    assertThat(primera.estado()).as(primera.cuerpo()).isEqualTo(200);
    assertThat(primera.<String>json("$.apertura.estado")).isEqualTo("en_firma");
    assertThat(
            codigo(
                almacen.put(
                    "/api/v1/conteos/" + conteo + "/lineas",
                    "{\"lineas\":[{\"materialId\":%d,\"contado\":13}]}".formatted(pintura))))
        .isEqualTo("APERTURA_EN_FIRMA");
    assertThat(saldo(pintura, "existencia")).isEqualByComparingTo("0");
    assertThat(codigo(adminUno.post(firmar(apertura), firma("0".repeat(64)))))
        .isEqualTo("APERTURA_CAMBIO");
    Respuesta segunda = sesion(admin2).post(firmar(apertura), firma(huella));
    assertThat(segunda.estado()).as(segunda.cuerpo()).isEqualTo(200);
    assertThat(segunda.<String>json("$.apertura.estado")).isEqualTo("aplicada");

    // 6. Lo que quedó.
    assertThat(saldo(pintura, "existencia")).isEqualByComparingTo("12");
    assertThat(saldo(pintura, "valor")).isEqualByComparingTo("660");
    assertThat(saldo(compresor, "existencia")).isEqualByComparingTo("1");
    assertThat(saldo(compresor, "prestado")).isEqualByComparingTo("1");
    assertThat(saldo(cemento, "existencia")).isEqualByComparingTo("0");
    Respuesta vivos =
        almacen.get("/api/v1/prestamos?almacenId=" + this.almacen + "&estado=abierto");
    assertThat(vivos.<List<Object>>json("$")).hasSize(1);
    assertThat(numero(vivos, "$[0].responsableId")).isEqualTo(daniel);
    assertThat(
            comoMigrador()
                .sql(
                    "SELECT tipo || '/' || estado FROM inventario.movimiento WHERE empresa_id = :e"
                        + " AND conteo_id = :c")
                .param("e", empresa.id())
                .param("c", conteo)
                .query(String.class)
                .single())
        .isEqualTo("apertura/aplicado");
    assertThat(adminUno.get("/api/v1/aperturas/" + apertura + "/acta.xlsx").estado())
        .isEqualTo(200);
    assertThat(
            adminUno
                .get(
                    "/api/v1/almacenes/%d/salidas.xlsx?desde=%s&hasta=%s"
                        .formatted(this.almacen, corte, corte.plusDays(1)))
                .estado())
        .isEqualTo(200);
    assertThat(codigo(almacen.post("/api/v1/aperturas", abrir()))).isEqualTo("APERTURA_EXISTENTE");
  }

  @Test
  void quienContoNoFirmaYLasFirmasSeRetiran() {
    ClienteHttp almacen = sesion(almacenista);
    Respuesta abierta = almacen.post("/api/v1/aperturas", abrir());
    long apertura = numero(abierta, "$.apertura.id");
    long conteo = numero(abierta, "$.apertura.conteoId");
    long prestamo = numero(abierta, "$.prestamos[0].id");
    ClienteHttp adminUno = sesion(admin);
    adminUno.patch(
        "/api/v1/aperturas/%d/prestamos/%d".formatted(apertura, prestamo),
        "{\"cantidad\":1,\"estado\":\"descartado\",\"motivo\":\"Ya regresó\"}",
        abierta.<Integer>json("$.prestamos[0].version"));
    // Administración cuenta un material: ya no puede firmar.
    adminUno.put(
        "/api/v1/conteos/" + conteo + "/lineas",
        "{\"lineas\":[{\"materialId\":%d,\"contado\":2},{\"materialId\":%d,\"contado\":0}]}"
            .formatted(pintura, cemento));
    String huella = adminUno.get("/api/v1/aperturas/" + apertura).json("$.huella");
    assertThat(codigo(adminUno.post(firmar(apertura), firma(huella))))
        .isEqualTo("FIRMA_DE_QUIEN_CONTO");

    Respuesta primera = sesion(coordinacion).post(firmar(apertura), firma(huella));
    assertThat(primera.<String>json("$.apertura.estado")).isEqualTo("en_firma");
    Respuesta retirada =
        sesion(admin2).post("/api/v1/aperturas/" + apertura + "/retirar-firmas", "");
    assertThat(retirada.<String>json("$.apertura.estado")).isEqualTo("abierta");
    assertThat(retirada.<Map<String, Object>>json("$.apertura").get("firmaCoordinacion")).isNull();

    Respuesta cancelada =
        sesion(admin2)
            .post(
                "/api/v1/aperturas/" + apertura + "/cancelar",
                "{\"motivo\":\"Se reprograma el corte\"}");
    assertThat(cancelada.<String>json("$.apertura.estado")).isEqualTo("cancelada");
    assertThat(almacen.get("/api/v1/conteos/" + conteo).<String>json("$.conteo.estado"))
        .isEqualTo("cancelado");
    assertThat(almacen.post("/api/v1/aperturas", abrir()).estado())
        .as("cancelada, el almacén se vuelve a abrir")
        .isEqualTo(201);
  }

  // ------------------------------------------------------------------------------ auxiliares

  private String abrir() {
    return "{\"almacenId\":%d,\"fecha\":\"%s\"}".formatted(almacen, corte);
  }

  private static String firmar(long apertura) {
    return "/api/v1/aperturas/" + apertura + "/firmar";
  }

  private static String firma(String huella) {
    return "{\"huella\":\"" + huella + "\"}";
  }

  private static String ruta(long material, String campo) {
    return "$.lineas[?(@.materialId == " + material + ")]." + campo;
  }

  private static BigDecimal linea(Respuesta r, long material, String campo) {
    List<Object> valores = r.json(ruta(material, campo));
    assertThat(valores).as(r.cuerpo()).hasSize(1);
    return new BigDecimal(valores.getFirst().toString());
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

  private long material(
      JdbcClient jdbc, String clave, String nombre, String unidad, String precio) {
    return jdbc.sql(
            "INSERT INTO catalogo.material (empresa_id, clave, nombre, unidad_control_id,"
                + " precio_referencia) SELECT :e, :clave, :nombre, id, CAST(:precio AS numeric)"
                + " FROM catalogo.unidad_medida WHERE clave = :unidad AND empresa_id IS NULL"
                + " RETURNING id")
        .param("e", empresa.id())
        .param("clave", clave)
        .param("nombre", nombre)
        .param("unidad", unidad)
        .param("precio", precio)
        .query(Long.class)
        .single();
  }

  private BigDecimal saldo(long material, String columna) {
    return comoMigrador()
        .sql(
            "SELECT coalesce((SELECT "
                + columna
                + " FROM inventario.saldo WHERE empresa_id = :e"
                + " AND almacen_id = :a AND material_id = :m), 0)")
        .param("e", empresa.id())
        .param("a", almacen)
        .param("m", material)
        .query(BigDecimal.class)
        .single();
  }
}

package mx.constructora.inventario;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import mx.constructora.ClienteHttp;
import mx.constructora.ClienteHttp.Respuesta;
import mx.constructora.PruebaIntegracion;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Folios;
import mx.constructora.inventario.aplicacion.ServicioAplicacion;
import mx.constructora.organizacion.Rol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Criterio de aceptación de H4, contra PostgreSQL real:
 *
 * <ul>
 *   <li>50 salidas simultáneas de un material con 50 piezas: aplican exactamente 50 de 60 vales, la
 *       existencia queda en cero y el kardex suma cero.
 *   <li>El sistema rechaza una salida sin contrato, una que deja existencia negativa y una
 *       autorizada por quien la capturó.
 *   <li>Un bote repartido entre 18 lotes suma exacto, en cantidad y en importe.
 * </ul>
 */
class CriterioH4Test extends PruebaIntegracion {

  private static final String CONTRASENA = "contrasena-de-prueba";

  @Autowired private ServicioAplicacion aplicacion;

  private Empresa empresa;
  private String admin;
  private String almacenista;
  private String residente;
  private String coordinacion;
  private String doble;
  private long almacenistaId;
  private long contrato;
  private long otroContrato;
  private long paquete;
  private long pintura;
  private long cemento;
  private long cubeta;
  private long proveedor;
  private final List<Long> lotes = new ArrayList<>();
  private long loteOtroContrato;
  private long almacen;

  @BeforeEach
  void preparar() {
    empresa = crearEmpresa("H4");
    admin = unico("admin.h4");
    almacenista = unico("almacen.h4");
    residente = unico("residente.h4");
    coordinacion = unico("coord.h4");
    doble = unico("doble.h4");
    crearUsuario(empresa, admin, CONTRASENA, "admin");
    almacenistaId = crearUsuario(empresa, almacenista, CONTRASENA, "almacenista");
    long residenteId = crearUsuario(empresa, residente, CONTRASENA, "residente");
    crearUsuario(empresa, coordinacion, CONTRASENA, "coordinacion");
    crearUsuario(empresa, doble, CONTRASENA, "almacenista", "coordinacion");

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
                + ", 'CORCELES', 'Corceles')");
    contrato =
        insertar(
            jdbc,
            "INSERT INTO estructura.contrato (empresa_id, cliente_id, frente_id, codigo) VALUES ("
                + e
                + ", "
                + cliente
                + ", "
                + frente
                + ", '60-H4-UNO')");
    otroContrato =
        insertar(
            jdbc,
            "INSERT INTO estructura.contrato (empresa_id, cliente_id, frente_id, codigo) VALUES ("
                + e
                + ", "
                + cliente
                + ", "
                + frente
                + ", '60-H4-DOS')");
    for (int lote = 1; lote <= 20; lote++) {
      lotes.add(
          insertar(
              jdbc,
              "INSERT INTO estructura.unidad_obra (empresa_id, contrato_id, frente_id, tipo_id,"
                  + " clave, manzana, lote) VALUES ("
                  + e
                  + ", "
                  + contrato
                  + ", "
                  + frente
                  + ", 1, 'M16-L"
                  + lote
                  + "', '16', '"
                  + lote
                  + "')"));
    }
    loteOtroContrato =
        insertar(
            jdbc,
            "INSERT INTO estructura.unidad_obra (empresa_id, contrato_id, frente_id, tipo_id,"
                + " clave, manzana, lote) VALUES ("
                + e
                + ", "
                + otroContrato
                + ", "
                + frente
                + ", 1, 'M17-L1', '17', '1')");
    jdbc.sql(
            "INSERT INTO estructura.contrato_usuario (empresa_id, contrato_id, usuario_id)"
                + " VALUES (:e, :c, :u)")
        .param("e", e)
        .param("c", contrato)
        .param("u", residenteId)
        .update();
    paquete =
        insertar(
            jdbc,
            "INSERT INTO estructura.paquete (empresa_id, clave, nombre) VALUES ("
                + e
                + ", '5', 'Pintura')");
    pintura = material(jdbc, "PINT", "Pintura vinílica blanca", "L");
    cemento = material(jdbc, "CEM", "Cemento gris 50 kg", "SAC");
    cubeta =
        jdbc.sql("SELECT id FROM catalogo.unidad_medida WHERE clave = 'CUB' AND empresa_id IS NULL")
            .query(Long.class)
            .single();
    jdbc.sql(
            "INSERT INTO catalogo.conversion_unidad (empresa_id, material_id, origen_id,"
                + " destino_id, factor) SELECT :e, id, :cub, unidad_control_id, 19 FROM"
                + " catalogo.material WHERE id = :m")
        .param("e", e)
        .param("cub", cubeta)
        .param("m", pintura)
        .update();
    proveedor =
        insertar(
            jdbc,
            "INSERT INTO catalogo.proveedor (empresa_id, nombre, tasa_iva) VALUES ("
                + e
                + ", 'Comex', 0.16)");

    Respuesta alta =
        sesion(admin)
            .post(
                "/api/v1/almacenes",
                "{\"frenteId\":%d,\"clave\":\"COR\",\"nombre\":\"Almacén Corceles\"}"
                    .formatted(frente));
    almacen = id(alta);
  }

  // ------------------------------------------------------------------------------ Criterios

  @Test
  void cincuentaSalidasSimultaneasNoDejanNegativo() throws Exception {
    recibir(cemento, "50", "200");
    ClienteHttp almacen = sesion(almacenista);
    ClienteHttp coord = sesion(coordinacion);
    List<Long> vales = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      long lote = lotes.get(i % lotes.size());
      vales.add(
          id(
              almacen.post(
                  "/api/v1/movimientos", vale(cemento, "1", null, contrato, List.of(lote)))));
    }
    Respuesta autorizados = coord.post("/api/v1/movimientos/autorizar", "{\"ids\":" + vales + "}");
    assertThat(autorizados.<List<Boolean>>json("$[*].ok")).hasSize(60).containsOnly(true);

    var exitos = new AtomicInteger();
    var sinExistencia = new AtomicInteger();
    var inicio = new CountDownLatch(1);
    String[] permisos = Rol.ALMACENISTA.permisos().toArray(String[]::new);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (long vale : vales) {
        pool.submit(
            () -> {
              inicio.await();
              SecurityContextHolder.getContext()
                  .setAuthentication(new TestingAuthenticationToken(almacenista, null, permisos));
              try {
                ContextoEmpresa.ejecutarComo(
                    empresa.id(), almacenistaId, () -> aplicacion.aplicar(vale));
                exitos.incrementAndGet();
              } catch (ErrorNegocio e) {
                if (e.codigo().equals("EXISTENCIA_INSUFICIENTE")) {
                  sinExistencia.incrementAndGet();
                } else {
                  throw e;
                }
              } finally {
                SecurityContextHolder.clearContext();
              }
              return null;
            });
      }
      inicio.countDown();
    }

    assertThat(exitos).hasValue(50);
    assertThat(sinExistencia).hasValue(10);
    assertThat(saldo(cemento, "existencia")).isEqualByComparingTo("0");
    assertThat(saldo(cemento, "valor")).isEqualByComparingTo("0");
    assertThat(sumaKardex(cemento)).isEqualByComparingTo("0");
    BigDecimal minimo =
        comoMigrador()
            .sql(
                "SELECT min(existencia_despues) FROM inventario.kardex WHERE empresa_id = :e AND"
                    + " material_id = :m")
            .param("e", empresa.id())
            .param("m", cemento)
            .query(BigDecimal.class)
            .single();
    assertThat(minimo).isEqualByComparingTo("0");
  }

  @Test
  void rechazaUnaSalidaSinContrato() {
    recibir(cemento, "10", "200");
    Respuesta sinContrato =
        sesion(almacenista)
            .post(
                "/api/v1/movimientos",
                """
                {"tipo":"salida","fecha":"%s","almacenId":%d,"enviar":true,
                 "lineas":[{"materialId":%d,"cantidad":1,"unidadObraIds":[%d],"paqueteId":%d,"clasificacionId":1}]}
                """
                    .formatted(hoy(), almacen, cemento, lotes.getFirst(), paquete));
    assertThat(sinContrato.estado()).as(sinContrato.cuerpo()).isEqualTo(400);
    assertThat(sinContrato.<String>json("$.codigo")).isEqualTo("FALTA_CONTRATO");

    Respuesta loteAjeno =
        sesion(almacenista)
            .post(
                "/api/v1/movimientos",
                vale(cemento, "1", null, contrato, List.of(loteOtroContrato)));
    assertThat(loteAjeno.estado()).isEqualTo(400);
    assertThat(loteAjeno.<String>json("$.codigo")).isEqualTo("LOTE_DE_OTRO_CONTRATO");

    // La base también lo rechaza aunque el código fallara (regla 4).
    long sal =
        id(
            sesion(almacenista)
                .post(
                    "/api/v1/movimientos",
                    vale(cemento, "1", null, contrato, List.of(lotes.getFirst()))));
    assertThat(
            comoMigrador()
                .sql(
                    "SELECT count(*) FROM inventario.movimiento_linea WHERE movimiento_id = :m AND"
                        + " contrato_id IS NULL")
                .param("m", sal)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void rechazaUnaSalidaQueDejaExistenciaNegativa() {
    recibir(cemento, "8", "200");
    long vale =
        id(
            sesion(almacenista)
                .post(
                    "/api/v1/movimientos",
                    vale(cemento, "15", null, contrato, List.of(lotes.getFirst()))));
    assertThat(sesion(residente).post("/api/v1/movimientos/" + vale + "/autorizar", "").estado())
        .isEqualTo(200);

    Respuesta aplicar = sesion(almacenista).post("/api/v1/movimientos/" + vale + "/aplicar", "");

    assertThat(aplicar.estado()).as(aplicar.cuerpo()).isEqualTo(409);
    assertThat(aplicar.<String>json("$.codigo")).isEqualTo("EXISTENCIA_INSUFICIENTE");
    assertThat(aplicar.<String>json("$.detail")).contains("Cemento gris 50 kg").contains("(8 SAC)");
    assertThat(saldo(cemento, "existencia")).isEqualByComparingTo("8");
    assertThat(sesion(almacenista).get("/api/v1/movimientos/" + vale).<String>json("$.estado"))
        .isEqualTo("autorizado");
  }

  @Test
  void rechazaUnaSalidaAutorizadaPorQuienLaCapturo() {
    recibir(cemento, "10", "200");
    ClienteHttp ambos = sesion(doble);
    long vale =
        id(
            ambos.post(
                "/api/v1/movimientos",
                vale(cemento, "1", null, contrato, List.of(lotes.getFirst()))));

    Respuesta propia = ambos.post("/api/v1/movimientos/" + vale + "/autorizar", "");

    assertThat(propia.estado()).isEqualTo(403);
    assertThat(propia.<String>json("$.codigo")).isEqualTo("AUTORIZA_QUIEN_CAPTURO");
    assertThat(ambos.get("/api/v1/movimientos/por-autorizar").<List<Integer>>json("$[*].id"))
        .doesNotContain((int) vale);
    assertThat(sesion(coordinacion).post("/api/v1/movimientos/" + vale + "/autorizar", "").estado())
        .isEqualTo(200);
  }

  @Test
  void unBoteRepartidoEntre18LotesSumaExacto() {
    recibir(pintura, "38", "39.95");
    List<Long> dieciocho = lotes.subList(0, 18);
    Respuesta creado =
        sesion(almacenista)
            .post("/api/v1/movimientos", vale(pintura, "1", cubeta, contrato, dieciocho));
    long vale = id(creado);
    assertThat(creado.<List<Object>>json("$.lineas")).hasSize(18);
    assertThat(creado.<String>json("$.estado")).isEqualTo("pendiente");
    assertThat(sumar(creado.<List<Object>>json("$.lineas[*].cantidad"))).isEqualByComparingTo("19");

    assertThat(sesion(residente).post("/api/v1/movimientos/" + vale + "/autorizar", "").estado())
        .isEqualTo(200);
    BigDecimal valorAntes = saldo(pintura, "valor");
    Respuesta aplicado = sesion(almacenista).post("/api/v1/movimientos/" + vale + "/aplicar", "");
    assertThat(aplicado.estado()).as(aplicado.cuerpo()).isEqualTo(200);
    // El almacenista no ve costos; coordinación sí.
    assertThat(aplicado.<Object>json("$.lineas[0].importe")).isNull();
    Respuesta visto = sesion(coordinacion).get("/api/v1/movimientos/" + vale);
    BigDecimal importes = sumar(visto.<List<Object>>json("$.lineas[*].importe"));

    assertThat(importes).isEqualByComparingTo(valorAntes.subtract(saldo(pintura, "valor")));
    assertThat(saldo(pintura, "existencia")).isEqualByComparingTo("19");
    var cargos =
        comoMigrador()
            .sql(
                "SELECT sum(cantidad) AS cantidad, sum(importe) AS importe, count(DISTINCT"
                    + " unidad_obra_id) AS lotes FROM inventario.cargo WHERE movimiento_id = :m")
            .param("m", vale)
            .query(
                (rs, n) -> List.of(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)))
            .single();
    assertThat(cargos.get(0)).isEqualByComparingTo("19");
    assertThat(cargos.get(1)).isEqualByComparingTo(importes);
    assertThat(cargos.get(2)).isEqualByComparingTo("18");
  }

  // ------------------------------------------------------------------------------ Reglas que lo
  // protegen

  @Test
  void soloElAlmacenCapturaYOtraEmpresaNoVeNada() {
    recibir(cemento, "10", "200");
    Respuesta delResidente =
        sesion(residente)
            .post(
                "/api/v1/movimientos",
                vale(cemento, "1", null, contrato, List.of(lotes.getFirst())));
    assertThat(delResidente.estado()).isEqualTo(403);

    long vale =
        id(
            sesion(almacenista)
                .post(
                    "/api/v1/movimientos",
                    vale(cemento, "1", null, contrato, List.of(lotes.getFirst()))));
    Empresa otra = crearEmpresa("H4-otra");
    String ajeno = unico("ajeno.h4");
    crearUsuario(otra, ajeno, CONTRASENA, "almacenista");
    ClienteHttp deOtra = sesion(ajeno);
    assertThat(deOtra.get("/api/v1/movimientos/" + vale).estado()).isEqualTo(404);
    assertThat(deOtra.get("/api/v1/almacenes").<List<Object>>json("$")).isEmpty();
    assertThat(deOtra.post("/api/v1/movimientos/" + vale + "/aplicar", "").estado()).isEqualTo(404);
  }

  @Test
  void devolverYCancelarRegresanExistenciaYCosto() {
    recibir(cemento, "10", "200");
    long vale =
        id(
            sesion(almacenista)
                .post(
                    "/api/v1/movimientos",
                    vale(cemento, "4", null, contrato, List.of(lotes.getFirst()))));
    sesion(residente).post("/api/v1/movimientos/" + vale + "/autorizar", "");
    Respuesta aplicado = sesion(almacenista).post("/api/v1/movimientos/" + vale + "/aplicar", "");
    long linea = ((Number) aplicado.json("$.lineas[0].id")).longValue();

    long devolucion =
        id(
            sesion(almacenista)
                .post(
                    "/api/v1/movimientos",
                    """
                    {"tipo":"devolucion","fecha":"%s","almacenId":%d,"lineas":[{"cantidad":1,"lineaOrigenId":%d}]}
                    """
                        .formatted(hoy(), almacen, linea)));
    assertThat(
            sesion(almacenista).post("/api/v1/movimientos/" + devolucion + "/aplicar", "").estado())
        .isEqualTo(200);
    assertThat(saldo(cemento, "existencia")).isEqualByComparingTo("7");

    Respuesta cancelado =
        sesion(almacenista)
            .post(
                "/api/v1/movimientos/" + vale + "/cancelar",
                "{\"motivo\":\"Se capturó dos veces\"}");
    assertThat(cancelado.<String>json("$.estado")).isEqualTo("cancelado");
    assertThat(saldo(cemento, "existencia")).isEqualByComparingTo("10");
    assertThat(saldo(cemento, "valor")).isEqualByComparingTo("2000");
    assertThat(sumaKardex(cemento)).isEqualByComparingTo("10");
  }

  // ------------------------------------------------------------------------------ Apoyo

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

  private long material(JdbcClient jdbc, String clave, String nombre, String unidad) {
    return jdbc.sql(
            "INSERT INTO catalogo.material (empresa_id, clave, nombre, unidad_control_id)"
                + " SELECT :e, :clave, :nombre, id FROM catalogo.unidad_medida"
                + " WHERE clave = :unidad AND empresa_id IS NULL RETURNING id")
        .param("e", empresa.id())
        .param("clave", clave)
        .param("nombre", nombre)
        .param("unidad", unidad)
        .query(Long.class)
        .single();
  }

  private static String hoy() {
    return LocalDate.now(Folios.ZONA).toString();
  }

  /** Recepción aplicada de {@code cantidad} a {@code precio} sin IVA. */
  private void recibir(long material, String cantidad, String precio) {
    ClienteHttp almacen = sesion(almacenista);
    long entrada =
        id(
            almacen.post(
                "/api/v1/movimientos",
                """
                {"tipo":"entrada","fecha":"%s","almacenId":%d,"proveedorId":%d,
                 "lineas":[{"materialId":%d,"cantidad":%s,"precio":%s}]}
                """
                    .formatted(hoy(), this.almacen, proveedor, material, cantidad, precio)));
    Respuesta aplicada = almacen.post("/api/v1/movimientos/" + entrada + "/aplicar", "");
    assertThat(aplicada.estado()).as(aplicada.cuerpo()).isEqualTo(200);
  }

  private String vale(
      long material, String cantidad, Long unidad, long contrato, List<Long> lotes) {
    return """
    {"tipo":"salida","fecha":"%s","almacenId":%d,"enviar":true,
     "lineas":[{"materialId":%d,"cantidad":%s,"unidadId":%s,"contratoId":%d,
                "unidadObraIds":%s,"paqueteId":%d,"clasificacionId":1}]}
    """
        .formatted(
            hoy(),
            almacen,
            material,
            cantidad,
            unidad == null ? "null" : unidad.toString(),
            contrato,
            lotes.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]")),
            paquete);
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

  private BigDecimal sumaKardex(long material) {
    return comoMigrador()
        .sql(
            "SELECT coalesce(sum(cantidad), 0) FROM inventario.kardex WHERE empresa_id = :e"
                + " AND almacen_id = :a AND material_id = :m")
        .param("e", empresa.id())
        .param("a", almacen)
        .param("m", material)
        .query(BigDecimal.class)
        .single();
  }

  private static BigDecimal sumar(List<Object> valores) {
    return valores.stream()
        .map(v -> new BigDecimal(v.toString()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}

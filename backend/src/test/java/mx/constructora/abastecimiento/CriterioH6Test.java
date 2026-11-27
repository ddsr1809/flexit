package mx.constructora.abastecimiento;

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
 * Criterio de aceptación de H6, por HTTP contra PostgreSQL real:
 *
 * <ul>
 *   <li>Un ciclo completo requisición → pedido → recepción → vale sin tocar Excel: lo que hay se
 *       aparta al autorizar, lo que falta se pide, lo recibido se aparta para la requisición y el
 *       vale lo consume.
 *   <li>Lo apartado no lo toma otro vale; quien captura no autoriza; el residente solo ve lo suyo.
 *   <li>Presupuesto por lote con aviso al 80% y al 100%, y los reportes en Excel.
 * </ul>
 */
class CriterioH6Test extends PruebaIntegracion {

  private static final String CONTRASENA = "contrasena-de-prueba";

  private Empresa empresa;
  private String admin;
  private String almacenista;
  private String residente;
  private String coordinacion;
  private String compras;
  private long contrato;
  private long otroContrato;
  private long lote1;
  private long lote2;
  private long loteAjeno;
  private long paquete;
  private long cemento;
  private long pintura;
  private long cementera;
  private long almacen;

  @BeforeEach
  void preparar() {
    empresa = crearEmpresa("H6");
    admin = unico("admin.h6");
    almacenista = unico("almacen.h6");
    residente = unico("residente.h6");
    coordinacion = unico("coord.h6");
    compras = unico("compras.h6");
    crearUsuario(empresa, admin, CONTRASENA, "admin");
    crearUsuario(empresa, almacenista, CONTRASENA, "almacenista");
    long residenteId = crearUsuario(empresa, residente, CONTRASENA, "residente");
    crearUsuario(empresa, coordinacion, CONTRASENA, "coordinacion");
    crearUsuario(empresa, compras, CONTRASENA, "compras");

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
                + ", '60-H6-UNO')");
    otroContrato =
        insertar(
            jdbc,
            "INSERT INTO estructura.contrato (empresa_id, cliente_id, frente_id, codigo) VALUES ("
                + e
                + ", "
                + cliente
                + ", "
                + frente
                + ", '60-H6-DOS')");
    lote1 = lote(jdbc, frente, contrato, "20", "29");
    lote2 = lote(jdbc, frente, contrato, "20", "30");
    loteAjeno = lote(jdbc, frente, otroContrato, "21", "1");
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
                + ", '3', 'Losa')");
    cemento = material(jdbc, "CEM", "Cemento gris 50 kg", "SAC");
    pintura = material(jdbc, "PINT", "Pintura vinílica blanca", "L");
    cementera =
        insertar(
            jdbc,
            "INSERT INTO catalogo.proveedor (empresa_id, nombre, tasa_iva) VALUES ("
                + e
                + ", 'Cementos del Norte', 0.16)");
    almacen =
        id(
            sesion(admin)
                .post(
                    "/api/v1/almacenes",
                    "{\"frenteId\":%d,\"clave\":\"COR\",\"nombre\":\"Almacén Corceles\"}"
                        .formatted(frente)));
  }

  // ------------------------------------------------------------------------------ Criterios

  @Test
  void cicloCompletoRequisicionPedidoRecepcionVale() {
    recibir(cemento, "10", "200");

    // El residente pide 12 sacos para dos lotes y 5 L de pintura.
    Respuesta creada =
        sesion(residente)
            .post(
                "/api/v1/requisiciones",
                """
                {"contratoId":%d,"fechaRequerida":"%s","urgente":true,"enviar":true,
                 "renglones":[
                   {"materialId":%d,"cantidad":12,"unidadObraIds":[%d,%d],"paqueteId":%d,"clasificacionId":1},
                   {"materialId":%d,"cantidad":5,"unidadObraIds":[%d],"paqueteId":%d,"clasificacionId":1}]}
                """
                    .formatted(
                        contrato, manana(), cemento, lote1, lote2, paquete, pintura, lote1,
                        paquete));
    long requisicion = id(creada);
    assertThat(creada.<String>json("$.estado")).isEqualTo("pendiente");
    assertThat(creada.<List<Object>>json("$.renglones")).hasSize(3);

    // Coordinación autoriza: se apartan los 10 sacos que hay; faltan 2 y la pintura.
    Respuesta autorizada =
        sesion(coordinacion).post("/api/v1/requisiciones/" + requisicion + "/autorizar", "");
    assertThat(autorizada.estado()).as(autorizada.cuerpo()).isEqualTo(200);
    assertThat(numero(renglon(autorizada, lote1, cemento), "reservado")).isEqualByComparingTo("6");
    assertThat(numero(renglon(autorizada, lote2, cemento), "reservado")).isEqualByComparingTo("4");
    assertThat(numero(renglon(autorizada, lote2, cemento), "faltante")).isEqualByComparingTo("2");
    assertThat(numero(renglon(autorizada, lote1, pintura), "faltante")).isEqualByComparingTo("5");
    assertThat(saldo(cemento, "reservado")).isEqualByComparingTo("10");

    // Compras ve los faltantes por proveedor y los pide.
    ClienteHttp compra = sesion(compras);
    Respuesta faltantes = compra.get("/api/v1/faltantes");
    assertThat(faltantes.<List<Object>>json("$")).hasSize(2);
    assertThat(((Number) faltantes.json("$[0].proveedorId")).longValue()).isEqualTo(cementera);
    long cementoL2 = ((Number) renglon(autorizada, lote2, cemento).get("id")).longValue();
    long pinturaL1 = ((Number) renglon(autorizada, lote1, pintura).get("id")).longValue();
    Respuesta pedido =
        compra.post(
            "/api/v1/pedidos",
            """
            {"proveedorId":%d,"almacenId":%d,"enviar":true,
             "renglones":[
               {"materialId":%d,"cantidad":5,"precio":210,"requisicionLineaIds":[%d]},
               {"materialId":%d,"cantidad":5,"precio":90,"requisicionLineaIds":[%d]}]}
            """
                .formatted(cementera, almacen, cemento, cementoL2, pintura, pinturaL1));
    long pedidoId = id(pedido);
    assertThat(pedido.<String>json("$.estado")).isEqualTo("enviado");
    assertThat(new BigDecimal(pedido.json("$.renglones[0].requisiciones[0].cantidad").toString()))
        .isEqualByComparingTo("2");
    assertThat(compra.get("/api/v1/faltantes").<List<Object>>json("$")).isEmpty();

    // El almacén recibe 5 sacos y 3 L: lo recibido se aparta para la requisición.
    long renglonCemento = ((Number) pedido.json("$.renglones[0].id")).longValue();
    long renglonPintura = ((Number) pedido.json("$.renglones[1].id")).longValue();
    ClienteHttp almacenero = sesion(almacenista);
    Respuesta recepcion =
        almacenero.post(
            "/api/v1/pedidos/" + pedidoId + "/recibir",
            """
            {"fecha":"%s","folioFactura":"F-77","renglones":[
              {"pedidoLineaId":%d,"cantidad":5},{"pedidoLineaId":%d,"cantidad":3}]}
            """
                .formatted(hoy(), renglonCemento, renglonPintura));
    assertThat(recepcion.estado()).as(recepcion.cuerpo()).isEqualTo(201);
    assertThat(recepcion.<String>json("$.folio")).startsWith("ENT-");
    assertThat(recepcion.<List<Object>>json("$.apartados")).hasSize(2);
    assertThat(recepcion.<String>json("$.pedido.estado")).isEqualTo("enviado");
    assertThat(saldo(cemento, "reservado")).isEqualByComparingTo("12");

    // El almacén surte con un vale ligado; el residente lo autoriza; se aplica.
    Respuesta antes = almacenero.get("/api/v1/requisiciones/" + requisicion);
    Respuesta vale =
        almacenero.post(
            "/api/v1/requisiciones/" + requisicion + "/surtir",
            """
            {"fecha":"%s","renglones":[
              {"requisicionLineaId":%s,"cantidad":6},{"requisicionLineaId":%s,"cantidad":6},
              {"requisicionLineaId":%s,"cantidad":3}]}
            """
                .formatted(
                    hoy(),
                    renglon(antes, lote1, cemento).get("id"),
                    renglon(antes, lote2, cemento).get("id"),
                    renglon(antes, lote1, pintura).get("id")));
    assertThat(vale.estado()).as(vale.cuerpo()).isEqualTo(201);
    long valeId = ((Number) vale.json("$.movimientoId")).longValue();
    Respuesta autorizado =
        sesion(residente).post("/api/v1/movimientos/" + valeId + "/autorizar", "");
    assertThat(autorizado.estado()).as(autorizado.cuerpo()).isEqualTo(200);
    Respuesta aplicado = almacenero.post("/api/v1/movimientos/" + valeId + "/aplicar", "");
    assertThat(aplicado.estado()).as(aplicado.cuerpo()).isEqualTo(200);

    assertThat(saldo(cemento, "reservado")).isEqualByComparingTo("0");
    assertThat(saldo(pintura, "reservado")).isEqualByComparingTo("0");
    assertThat(saldo(cemento, "existencia")).isEqualByComparingTo("3");
    Respuesta despues = sesion(residente).get("/api/v1/requisiciones/" + requisicion);
    assertThat(new BigDecimal(despues.json("$.avance").toString())).isEqualByComparingTo("88.24");
    assertThat(despues.<List<Object>>json("$.vales")).hasSize(1);
    assertThat(despues.<List<Object>>json("$.pedidos")).hasSize(1);

    // El costo del vale ya está en el reporte por lote (sin tocar Excel).
    ClienteHttp coord = sesion(coordinacion);
    Respuesta lotes = coord.get("/api/v1/reportes/contratos/" + contrato + "/lotes");
    assertThat(lotes.estado()).as(lotes.cuerpo()).isEqualTo(200);
    BigDecimal material = new BigDecimal(lotes.json("$.totales.material").toString());
    BigDecimal importeVale =
        new BigDecimal(coord.get("/api/v1/movimientos/" + valeId).json("$.importe").toString());
    assertThat(material).isEqualByComparingTo(importeVale);
    Respuesta excel = coord.get("/api/v1/reportes/contratos/" + contrato + "/lotes?formato=xlsx");
    assertThat(excel.estado()).isEqualTo(200);
    assertThat(excel.cuerpo()).startsWith("PK");

    // Kardex y saldo cuadran.
    assertThat(sumaKardex(cemento)).isEqualByComparingTo(saldo(cemento, "existencia"));
  }

  @Test
  void loApartadoNoLoTomaOtroValeYNadieAutorizaLoSuyo() {
    recibir(cemento, "4", "200");
    ClienteHttp coord = sesion(coordinacion);
    // Coordinación también pide, pero no autoriza lo suyo.
    long propia = id(coord.post("/api/v1/requisiciones", requisicion(cemento, "4", lote1, true)));
    Respuesta sola = coord.post("/api/v1/requisiciones/" + propia + "/autorizar", "");
    assertThat(sola.estado()).isEqualTo(403);
    assertThat(sola.<String>json("$.codigo")).isEqualTo("AUTORIZA_QUIEN_CAPTURO");
    // El residente no autoriza requisiciones.
    long delResidente =
        id(sesion(residente).post("/api/v1/requisiciones", requisicion(cemento, "4", lote1, true)));
    assertThat(
            sesion(residente)
                .post("/api/v1/requisiciones/" + delResidente + "/autorizar", "")
                .estado())
        .isEqualTo(403);
    assertThat(coord.post("/api/v1/requisiciones/" + delResidente + "/autorizar", "").estado())
        .isEqualTo(200);

    // Un vale suelto ya no alcanza: los 4 sacos están apartados.
    ClienteHttp almacenero = sesion(almacenista);
    long suelto =
        id(
            almacenero.post(
                "/api/v1/movimientos",
                """
                {"tipo":"salida","fecha":"%s","almacenId":%d,"enviar":true,
                 "lineas":[{"materialId":%d,"cantidad":1,"contratoId":%d,"unidadObraIds":[%d],
                            "paqueteId":%d,"clasificacionId":1}]}
                """
                    .formatted(hoy(), almacen, cemento, contrato, lote2, paquete)));
    assertThat(coord.post("/api/v1/movimientos/" + suelto + "/autorizar", "").estado())
        .isEqualTo(200);
    Respuesta aplicar = almacenero.post("/api/v1/movimientos/" + suelto + "/aplicar", "");
    assertThat(aplicar.estado()).isEqualTo(409);
    assertThat(aplicar.<String>json("$.codigo")).isEqualTo("EXISTENCIA_INSUFICIENTE");

    // Un lote de otro contrato no se pide.
    Respuesta ajeno =
        sesion(residente)
            .post("/api/v1/requisiciones", requisicion(cemento, "1", loteAjeno, false));
    assertThat(ajeno.estado()).isEqualTo(400);
    assertThat(ajeno.<String>json("$.codigo")).isEqualTo("LOTE_DE_OTRO_CONTRATO");

    // Cerrar libera lo apartado.
    assertThat(
            coord
                .post(
                    "/api/v1/requisiciones/" + delResidente + "/cerrar",
                    "{\"motivo\":\"Ya no se necesita\"}")
                .estado())
        .isEqualTo(200);
    assertThat(saldo(cemento, "reservado")).isEqualByComparingTo("0");

    // El residente no ve requisiciones de otros contratos ni sus costos.
    assertThat(
            sesion(residente).get("/api/v1/reportes/contratos/" + otroContrato + "/lotes").estado())
        .isEqualTo(404);
  }

  @Test
  void presupuestoConAvisoAl80YAl100() {
    recibir(cemento, "20", "100");
    ClienteHttp coord = sesion(coordinacion);
    Respuesta asignado =
        coord.put(
            "/api/v1/contratos/" + contrato + "/presupuesto",
            "{\"unidadIds\":[%d],\"monto\":1000}".formatted(lote1));
    assertThat(asignado.estado()).as(asignado.cuerpo()).isEqualTo(200);
    // Compras no asigna presupuesto.
    assertThat(
            sesion(compras)
                .put(
                    "/api/v1/contratos/" + contrato + "/presupuesto",
                    "{\"unidadIds\":[%d],\"monto\":1}".formatted(lote1))
                .estado())
        .isEqualTo(403);

    valeAplicado(cemento, "9", lote1);
    Respuesta al90 = coord.get("/api/v1/reportes/presupuesto?contratoId=" + contrato);
    Map<String, Object> l1 = lotePresupuesto(al90, lote1);
    assertThat(numero(l1, "porcentaje")).isEqualByComparingTo("90");
    assertThat(l1.get("semaforo")).isEqualTo("amarillo");
    assertThat(coord.get("/api/v1/reportes/presupuesto?avisos=true").<List<Object>>json("$"))
        .hasSize(1);

    valeAplicado(cemento, "2", lote1);
    Map<String, Object> rebasado =
        lotePresupuesto(coord.get("/api/v1/reportes/presupuesto?unidadIds=" + lote1), lote1);
    assertThat(numero(rebasado, "porcentaje")).isEqualByComparingTo("110");
    assertThat(rebasado.get("semaforo")).isEqualTo("rojo");
  }

  @Test
  void reportesEnExcelYConAlcance() {
    recibir(cemento, "5", "100");
    valeAplicado(cemento, "2", lote1);
    ClienteHttp coord = sesion(coordinacion);
    for (String agrupar :
        List.of("contrato", "unidad", "paquete", "clasificacion", "material", "mes")) {
      Respuesta r = coord.get("/api/v1/reportes/costo?agrupar=" + agrupar);
      assertThat(r.estado()).as(agrupar + ": " + r.cuerpo()).isEqualTo(200);
      assertThat(new BigDecimal(r.json("$.total").toString()))
          .as(agrupar)
          .isEqualByComparingTo("200");
      assertThat(coord.get("/api/v1/reportes/costo?formato=xlsx&agrupar=" + agrupar).cuerpo())
          .startsWith("PK");
    }
    assertThat(coord.get("/api/v1/reportes/costo?agrupar=proveedor").estado()).isEqualTo(400);
    assertThat(coord.get("/api/v1/reportes/contratos?formato=pdf").estado()).isEqualTo(400);
    assertThat(coord.get("/api/v1/reportes/valor-inventario").estado()).isEqualTo(200);
    assertThat(sesion(residente).get("/api/v1/reportes/valor-inventario").estado()).isEqualTo(403);
    assertThat(
            sesion(residente)
                .get("/api/v1/reportes/contratos")
                .<List<Object>>json("$[*].contratoId"))
        .containsExactly(Math.toIntExact(contrato));
    assertThat(sesion(almacenista).get("/api/v1/reportes/contratos").estado()).isEqualTo(403);
  }

  // ------------------------------------------------------------------------------ Apoyo

  private String requisicion(long material, String cantidad, long lote, boolean enviar) {
    return """
    {"contratoId":%d,"fechaRequerida":"%s","enviar":%s,
     "renglones":[{"materialId":%d,"cantidad":%s,"unidadObraIds":[%d],"paqueteId":%d,
                   "clasificacionId":1}]}
    """
        .formatted(contrato, manana(), enviar, material, cantidad, lote, paquete);
  }

  private void valeAplicado(long material, String cantidad, long lote) {
    ClienteHttp almacenero = sesion(almacenista);
    long vale =
        id(
            almacenero.post(
                "/api/v1/movimientos",
                """
                {"tipo":"salida","fecha":"%s","almacenId":%d,"enviar":true,
                 "lineas":[{"materialId":%d,"cantidad":%s,"contratoId":%d,"unidadObraIds":[%d],
                            "paqueteId":%d,"clasificacionId":1}]}
                """
                    .formatted(hoy(), almacen, material, cantidad, contrato, lote, paquete)));
    assertThat(sesion(coordinacion).post("/api/v1/movimientos/" + vale + "/autorizar", "").estado())
        .isEqualTo(200);
    Respuesta aplicado = almacenero.post("/api/v1/movimientos/" + vale + "/aplicar", "");
    assertThat(aplicado.estado()).as(aplicado.cuerpo()).isEqualTo(200);
  }

  private void recibir(long material, String cantidad, String precio) {
    ClienteHttp almacenero = sesion(almacenista);
    long entrada =
        id(
            almacenero.post(
                "/api/v1/movimientos",
                """
                {"tipo":"entrada","fecha":"%s","almacenId":%d,"proveedorId":%d,
                 "lineas":[{"materialId":%d,"cantidad":%s,"precio":%s}]}
                """
                    .formatted(hoy(), almacen, cementera, material, cantidad, precio)));
    Respuesta aplicada = almacenero.post("/api/v1/movimientos/" + entrada + "/aplicar", "");
    assertThat(aplicada.estado()).as(aplicada.cuerpo()).isEqualTo(200);
  }

  private static Map<String, Object> renglon(Respuesta requisicion, long lote, long material) {
    List<Map<String, Object>> renglones = requisicion.json("$.renglones");
    return renglones.stream()
        .filter(
            r ->
                ((Number) r.get("unidadObraId")).longValue() == lote
                    && ((Number) r.get("materialId")).longValue() == material)
        .findFirst()
        .orElseThrow();
  }

  private static Map<String, Object> lotePresupuesto(Respuesta reporte, long lote) {
    List<Map<String, Object>> lotes = reporte.json("$");
    return lotes.stream()
        .filter(l -> ((Number) l.get("unidadObraId")).longValue() == lote)
        .findFirst()
        .orElseThrow();
  }

  private static BigDecimal numero(Map<String, Object> mapa, String campo) {
    return new BigDecimal(mapa.get(campo).toString());
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

  private long lote(JdbcClient jdbc, long frente, long deContrato, String manzana, String lote) {
    return jdbc.sql(
            "INSERT INTO estructura.unidad_obra (empresa_id, contrato_id, frente_id, tipo_id,"
                + " clave, manzana, lote) VALUES (:e, :c, :f, 1, :clave, :m, :l) RETURNING id")
        .param("e", empresa.id())
        .param("c", deContrato)
        .param("f", frente)
        .param("clave", "M" + manzana + "-L" + lote)
        .param("m", manzana)
        .param("l", lote)
        .query(Long.class)
        .single();
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

  private static String hoy() {
    return LocalDate.now(Folios.ZONA).toString();
  }

  private static String manana() {
    return LocalDate.now(Folios.ZONA).plusDays(2).toString();
  }
}

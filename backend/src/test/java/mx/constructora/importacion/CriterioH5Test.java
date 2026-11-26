package mx.constructora.importacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import mx.constructora.ClienteHttp;
import mx.constructora.ClienteHttp.Respuesta;
import mx.constructora.PruebaIntegracion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Criterio de aceptación de H5, por HTTP real contra PostgreSQL: el histórico del Excel entra al
 * sistema como válido, corregido o en cuarentena; las cantidades y el destajo cuadran con el Excel;
 * el lote muestra sus vales, sus pagos y quién estuvo dado de alta en su contrato.
 *
 * <p>Los catálogos se preparan como los habría dejado H3 (con sus cargas marcadas como confirmadas)
 * para probar solo el histórico, con las muestras {@code muestra-historico-*.xlsx}.
 */
class CriterioH5Test extends PruebaIntegracion {

  private static final String CONTRASENA = "contrasena-de-prueba";
  private static final String ALMACEN = "muestra-historico-almacen.xlsx";

  private Empresa empresa;
  private String admin;
  private long loteM16;
  private long loteM20L33;
  private long contratoA;

  @BeforeEach
  void catalogosDeH3() {
    empresa = crearEmpresa("H5");
    admin = unico("admin.h5");
    long usuario = crearUsuario(empresa, admin, CONTRASENA, "admin");
    JdbcClient db = comoMigrador();
    long e = empresa.id();
    long cliente =
        id(
            db,
            "INSERT INTO estructura.cliente (empresa_id, nombre) VALUES ("
                + e
                + ", 'Ruba') RETURNING id");
    long corceles = frente(db, cliente, "CORCELES", "Corceles");
    long palermo = frente(db, cliente, "PALERMO", "Palermo");
    contratoA = contrato(db, cliente, corceles, "60-AAA-MF6-C002-2026");
    long contratoB = contrato(db, cliente, palermo, "60-BBB-MEO-C001-2026");
    long p65 =
        id(
            db,
            "INSERT INTO estructura.prototipo (empresa_id, clave, nombre) VALUES ("
                + e
                + ", 'P-65', 'Prototipo 65') RETURNING id");
    for (String lote : List.of("30", "31", "32", "33")) {
      long u = lote(db, contratoA, corceles, "20", lote, p65);
      if (lote.equals("33")) {
        loteM20L33 = u;
      }
    }
    loteM16 = lote(db, contratoB, palermo, "16", "34", null);
    long seccion =
        id(
            db,
            "INSERT INTO estructura.paquete (empresa_id, clave, nombre) VALUES ("
                + e
                + ", 'S01', 'CIMENTACIÓN') RETURNING id");
    db.sql(
            "INSERT INTO estructura.paquete (empresa_id, padre_id, clave, nombre, unidad) VALUES ("
                + e
                + ", "
                + seccion
                + ", 'S01.01', 'TRAZO Y EXCAVACION', 'Lote')")
        .update();
    db.sql(
            "INSERT INTO estructura.paquete (empresa_id, clave, nombre) VALUES ("
                + e
                + ", 'S02', 'Acabados')")
        .update();
    material(db, "002", "Cemento gris 50 kg", "SAC", null);
    material(db, "008", "Pintura vinílica blanca 19 L", "CUB", null);
    material(db, "003", "Aerosol negro", "PZA", null);
    material(db, "007", "Lija 220", "PZA", "9");
    db.sql(
            "INSERT INTO catalogo.proveedor (empresa_id, nombre, tasa_iva) VALUES ("
                + e
                + ", 'Materiales del Valle', 0.16), ("
                + e
                + ", 'Pinturas Sol', NULL)")
        .update();
    long lalo = persona(db, "Lalo Prueba Uno");
    long mari = persona(db, "Mari Ejemplo Dos");
    long pepe = persona(db, "Pepe Prueba Tres");
    long mario = persona(db, "PEREZ LUNA MARIO ALBERTO");
    persona(db, "SALAS RUIZ ANA MARIA");
    persona(db, "RIOS CANO LUIS");
    alias(db, "persona", "LALO", lalo);
    alias(db, "persona", "LALO G", lalo);
    alias(db, "persona", "MARI", mari);
    alias(db, "persona", "PEPE", pepe);
    alias(db, "persona", "MARIO ALBERTO PEREZ LUNA", mario);
    alias(db, "frente", "PAGOS CORCELES P-65", corceles);
    // Las cargas de H3 que el histórico requiere, como confirmadas
    db.sql(
            "INSERT INTO migracion.archivo (empresa_id, sha256, nombre, tamano, contenido,"
                + " usuario_id) VALUES (:e, :sha, 'h3.xlsx', 1, '\\x00', :u)")
        .param("e", e)
        .param("sha", "0".repeat(64))
        .param("u", usuario)
        .update();
    for (String tipo :
        List.of(
            "FRENTES",
            "CONTRATOS",
            "LOTES",
            "PAQUETES",
            "MATERIALES",
            "PROVEEDORES",
            "PERSONAS_SUA",
            "PERSONAS_DESTAJO",
            "PERSONAS_ALMACEN")) {
      db.sql(
              """
              INSERT INTO migracion.carga (empresa_id, tipo, archivo, sha256, hojas, estado,
                version_reglas, usuario_id, confirmada, confirmada_por)
              VALUES (:e, :tipo, 'h3.xlsx', :sha, 'H3', 'confirmada', 'h3.1', :u, now(), :u)
              """)
          .param("e", e)
          .param("tipo", tipo)
          .param("sha", "0".repeat(64))
          .param("u", usuario)
          .update();
    }
  }

  private static long id(JdbcClient db, String sql) {
    return db.sql(sql).query(Long.class).single();
  }

  private long frente(JdbcClient db, long cliente, String clave, String nombre) {
    return db.sql(
            "INSERT INTO estructura.frente (empresa_id, cliente_id, clave, nombre) VALUES (:e, :c,"
                + " :clave, :nombre) RETURNING id")
        .param("e", empresa.id())
        .param("c", cliente)
        .param("clave", clave)
        .param("nombre", nombre)
        .query(Long.class)
        .single();
  }

  private long contrato(JdbcClient db, long cliente, long frente, String codigo) {
    return db.sql(
            "INSERT INTO estructura.contrato (empresa_id, cliente_id, frente_id, codigo) VALUES"
                + " (:e, :c, :f, :codigo) RETURNING id")
        .param("e", empresa.id())
        .param("c", cliente)
        .param("f", frente)
        .param("codigo", codigo)
        .query(Long.class)
        .single();
  }

  private long lote(
      JdbcClient db, long contrato, long frente, String manzana, String lote, Long prototipo) {
    return db.sql(
            """
            INSERT INTO estructura.unidad_obra (empresa_id, contrato_id, frente_id, tipo_id, clave,
              manzana, lote, prototipo_id)
            VALUES (:e, :c, :f, 1, :clave, :m, :l, :p) RETURNING id
            """)
        .param("e", empresa.id())
        .param("c", contrato)
        .param("f", frente)
        .param("clave", "M" + manzana + "-L" + lote)
        .param("m", manzana)
        .param("l", lote)
        .param("p", prototipo)
        .query(Long.class)
        .single();
  }

  private void material(JdbcClient db, String clave, String nombre, String unidad, String precio) {
    db.sql(
            """
            INSERT INTO catalogo.material (empresa_id, clave, nombre, unidad_control_id, precio_referencia)
            SELECT :e, :clave, :nombre, id, CAST(:precio AS numeric) FROM catalogo.unidad_medida
             WHERE clave = :unidad AND empresa_id IS NULL
            """)
        .param("e", empresa.id())
        .param("clave", clave)
        .param("nombre", nombre)
        .param("unidad", unidad)
        .param("precio", precio)
        .update();
  }

  private long persona(JdbcClient db, String nombre) {
    return db.sql(
            "INSERT INTO organizacion.persona (empresa_id, nombre) VALUES (:e, :n) RETURNING id")
        .param("e", empresa.id())
        .param("n", nombre)
        .query(Long.class)
        .single();
  }

  private void alias(JdbcClient db, String entidad, String texto, long destino) {
    db.sql(
            "INSERT INTO migracion.alias (empresa_id, entidad, texto, destino_id, origen) VALUES"
                + " (:e, :ent, :t, :d, 'manual')")
        .param("e", empresa.id())
        .param("ent", entidad)
        .param("t", texto)
        .param("d", destino)
        .update();
  }

  // ------------------------------------------------------------------------------- auxiliares

  private ClienteHttp sesion(String usuario) {
    ClienteHttp navegador = cliente();
    assertThat(navegador.iniciarSesion(usuario, CONTRASENA).estado()).isEqualTo(200);
    return navegador;
  }

  private static long numero(Respuesta respuesta, String ruta) {
    return ((Number) respuesta.json(ruta)).longValue();
  }

  private static Respuesta subir(
      ClienteHttp navegador, String tipo, String muestra, Map<String, String> extra, byte[] bytes)
      throws IOException {
    Map<String, String> campos = new LinkedHashMap<>();
    campos.put("tipo", tipo);
    campos.putAll(extra);
    return navegador.subir(
        "/api/v1/importaciones",
        campos,
        muestra,
        bytes != null ? bytes : Files.readAllBytes(Muestras.ruta(muestra)));
  }

  /** El número de renglón de la carga que salió de esa fila de la hoja. */
  private static int renglon(ClienteHttp navegador, long carga, String regla, int filaExcel) {
    Respuesta filas =
        navegador.get("/api/v1/importaciones/%d/filas?regla=%s&tamano=200".formatted(carga, regla));
    List<Integer> numeros =
        filas.json("$.elementos[?(@.filaExcel == %d)].numero".formatted(filaExcel));
    assertThat(numeros).as(regla + " en la fila " + filaExcel + ": " + filas.cuerpo()).isNotEmpty();
    return numeros.getFirst();
  }

  private static Respuesta decidir(ClienteHttp navegador, long carga, String... decisiones) {
    return navegador.post(
        "/api/v1/importaciones/%d/decisiones".formatted(carga),
        "{\"decisiones\":[" + String.join(",", decisiones) + "]}");
  }

  private static void sinDiferencias(ClienteHttp navegador, long carga) {
    Respuesta conciliacion =
        navegador.get("/api/v1/importaciones/%d/conciliacion".formatted(carga));
    assertThat(conciliacion.estado()).as(conciliacion.cuerpo()).isEqualTo(200);
    List<Integer> conDiferencia = conciliacion.json("$.conceptos[*].conDiferencia");
    assertThat(conDiferencia).isNotEmpty().allMatch(n -> n == 0);
  }

  /** El mismo libro con otro sha256 (un comentario en el zip): "un archivo más nuevo". */
  private static byte[] otraVersion(String muestra) throws IOException {
    ByteArrayOutputStream salida = new ByteArrayOutputStream();
    try (ZipInputStream entrada =
            new ZipInputStream(
                new ByteArrayInputStream(Files.readAllBytes(Muestras.ruta(muestra))));
        ZipOutputStream zip = new ZipOutputStream(salida)) {
      ZipEntry parte;
      while ((parte = entrada.getNextEntry()) != null) {
        zip.putNextEntry(new ZipEntry(parte.getName()));
        entrada.transferTo(zip);
        zip.closeEntry();
      }
      zip.setComment("otra version");
    }
    return salida.toByteArray();
  }

  // ----------------------------------------------------------------------------------- pruebas

  @Test
  void almacenConciliadoYLaHistoriaDelLote() throws IOException {
    ClienteHttp administracion = sesion(admin);

    // Entradas: una sin fecha en cuarentena, las demás válidas o corregidas
    Respuesta entradas = subir(administracion, "HIST_ENTRADAS", ALMACEN, Map.of(), null);
    assertThat(entradas.estado()).as(entradas.cuerpo()).isEqualTo(201);
    long cargaEntradas = numero(entradas, "$.id");
    assertThat(numero(entradas, "$.renglones")).isEqualTo(7);
    assertThat(numero(entradas, "$.enCuarentena")).isEqualTo(1);
    int sinFecha = renglon(administracion, cargaEntradas, "SIN_FECHA", 7);
    assertThat(
            decidir(
                    administracion,
                    cargaEntradas,
                    "{\"numero\":%d,\"regla\":\"SIN_FECHA\",\"opcion\":\"VALOR\",\"valor\":\"05/08/2026\"}"
                        .formatted(sinFecha))
                .estado())
        .isEqualTo(200);
    Respuesta confirmadas =
        administracion.post("/api/v1/importaciones/%d/confirmar".formatted(cargaEntradas), null);
    assertThat(confirmadas.estado()).as(confirmadas.cuerpo()).isEqualTo(200);
    assertThat(numero(confirmadas, "$.creados.entrada")).isEqualTo(7);
    sinDiferencias(administracion, cargaEntradas);

    // Salidas: lotes que no existen, varias manzanas y una fórmula con referencias
    Respuesta salidas = subir(administracion, "HIST_SALIDAS", ALMACEN, Map.of(), null);
    assertThat(salidas.estado()).as(salidas.cuerpo()).isEqualTo(201);
    long cargaSalidas = numero(salidas, "$.id");
    assertThat(numero(salidas, "$.renglones")).isEqualTo(15);
    assertThat(numero(salidas, "$.enCuarentena")).isEqualTo(5);
    Respuesta destino =
        administracion.get("/api/v1/importaciones/destinos?entidad=unidad_obra&q=M20-L33");
    assertThat(numero(destino, "$[0].id")).isEqualTo(loteM20L33);
    Respuesta decididas =
        decidir(
            administracion,
            cargaSalidas,
            "{\"numero\":%d,\"regla\":\"MANZANAS_VARIAS\",\"opcion\":\"VALOR\",\"valor\":\"SIN ASIGNAR\"}"
                .formatted(renglon(administracion, cargaSalidas, "MANZANAS_VARIAS", 9)),
            "{\"numero\":%d,\"regla\":\"LOTE_INEXISTENTE\",\"opcion\":\"ELEGIR\",\"destinoId\":%d}"
                .formatted(
                    renglon(administracion, cargaSalidas, "LOTE_INEXISTENTE", 10), loteM20L33),
            "{\"numero\":%d,\"regla\":\"LOTE_INEXISTENTE\",\"opcion\":\"ACEPTAR\"}"
                .formatted(renglon(administracion, cargaSalidas, "LOTE_INEXISTENTE", 12)),
            "{\"numero\":%d,\"regla\":\"FORMULA_CON_REFERENCIAS\",\"opcion\":\"VALOR\",\"valor\":\"2\"}"
                .formatted(renglon(administracion, cargaSalidas, "FORMULA_CON_REFERENCIAS", 17)));
    assertThat(decididas.estado()).as(decididas.cuerpo()).isEqualTo(200);
    assertThat(numero(decididas, "$.resumen.enCuarentena"))
        .as("una decisión de lote resuelve los dos renglones de \"Mza 20 / Lote 99\"")
        .isZero();
    Respuesta confirmada =
        administracion.post("/api/v1/importaciones/%d/confirmar".formatted(cargaSalidas), null);
    assertThat(confirmada.estado()).as(confirmada.cuerpo()).isEqualTo(200);
    assertThat(numero(confirmada, "$.creados.salida"))
        .as("15 renglones, uno repartido entre 3 lotes")
        .isEqualTo(17);
    sinDiferencias(administracion, cargaSalidas);

    // La historia del lote (demo de H5)
    Respuesta historia = administracion.get("/api/v1/historico/unidades/" + loteM16);
    assertThat(historia.estado()).as(historia.cuerpo()).isEqualTo(200);
    assertThat(historia.<List<String>>json("$.salidas[*].clasificacion"))
        .containsExactly("Garantía Ruba");
    assertThat(historia.<Boolean>json("$.veCostos")).isTrue();

    // Quien no ve costos no entra; el residente solo ve sus contratos
    String almacenista = unico("almacen.h5");
    crearUsuario(empresa, almacenista, CONTRASENA, "almacenista");
    assertThat(sesion(almacenista).get("/api/v1/historico/unidades/" + loteM16).estado())
        .isEqualTo(403);
    String residente = unico("residente.h5");
    crearUsuario(empresa, residente, CONTRASENA, "residente");
    assertThat(sesion(residente).get("/api/v1/historico/unidades/" + loteM16).estado())
        .isEqualTo(404);

    // Las entradas no se revierten mientras las salidas valuadas con ellas sigan cargadas
    Respuesta enUso =
        administracion.post("/api/v1/importaciones/%d/revertir".formatted(cargaEntradas), null);
    assertThat(enUso.estado()).isEqualTo(409);
    assertThat(enUso.<String>json("$.codigo")).isEqualTo("CARGA_EN_USO");
    Respuesta revertida =
        administracion.post("/api/v1/importaciones/%d/revertir".formatted(cargaSalidas), null);
    assertThat(revertida.estado()).as(revertida.cuerpo()).isEqualTo(200);
    assertThat(
            administracion
                .get("/api/v1/historico/unidades/" + loteM16)
                .<List<Object>>json("$.salidas"))
        .isEmpty();
    long quedan =
        comoMigrador()
            .sql("SELECT count(*) FROM historico.salida WHERE empresa_id = :e")
            .param("e", empresa.id())
            .query(Long.class)
            .single();
    assertThat(quedan).isZero();
  }

  @Test
  void suaDelMesYDestajoAlPeso() throws IOException {
    ClienteHttp administracion = sesion(admin);

    Respuesta sinMes =
        subir(administracion, "HIST_SUA", "muestra-historico-sua.xlsx", Map.of(), null);
    assertThat(sinMes.estado()).isEqualTo(400);
    assertThat(sinMes.<String>json("$.codigo")).isEqualTo("FALTA_MES");
    Respuesta sua =
        subir(
            administracion,
            "HIST_SUA",
            "muestra-historico-sua.xlsx",
            Map.of("mes", "2026-10"),
            null);
    assertThat(sua.estado()).as(sua.cuerpo()).isEqualTo(201);
    long cargaSua = numero(sua, "$.id");
    int desconocido = renglon(administracion, cargaSua, "CONTRATO_DESCONOCIDO", 4);
    assertThat(
            decidir(
                    administracion,
                    cargaSua,
                    "{\"numero\":%d,\"regla\":\"CONTRATO_DESCONOCIDO\",\"opcion\":\"ELEGIR\",\"destinoId\":%d}"
                        .formatted(desconocido, contratoA))
                .estado())
        .isEqualTo(200);
    Respuesta suaConfirmado =
        administracion.post("/api/v1/importaciones/%d/confirmar".formatted(cargaSua), null);
    assertThat(suaConfirmado.estado()).as(suaConfirmado.cuerpo()).isEqualTo(200);
    assertThat(numero(suaConfirmado, "$.creados.alta_imss")).isEqualTo(4);
    assertThat(numero(suaConfirmado, "$.creados.asistencia_dia")).isEqualTo(7);
    sinDiferencias(administracion, cargaSua);

    // Destajo: el año de las semanas es obligatorio
    Respuesta sinAnio =
        subir(administracion, "HIST_DESTAJO", "muestra-historico-destajo.xlsx", Map.of(), null);
    assertThat(sinAnio.<String>json("$.codigo")).isEqualTo("FALTA_ANIO");
    Respuesta destajo =
        subir(
            administracion,
            "HIST_DESTAJO",
            "muestra-historico-destajo.xlsx",
            Map.of("anio", "2026"),
            null);
    assertThat(destajo.estado()).as(destajo.cuerpo()).isEqualTo(201);
    long cargaDestajo = numero(destajo, "$.id");
    assertThat(numero(destajo, "$.renglones")).isEqualTo(4);
    long pepe =
        numero(
            administracion.get("/api/v1/importaciones/destinos?entidad=persona&q=pepe"), "$[0].id");
    Respuesta filas =
        administracion.get(
            "/api/v1/importaciones/%d/filas?pendientes=true&tamano=200".formatted(cargaDestajo));
    int ilegible =
        filas.<List<Integer>>json("$.elementos[?(@.crudo._celda == 'O8')].numero").getFirst();
    int monto =
        filas.<List<Integer>>json("$.elementos[?(@.crudo._celda == 'I9')].numero").getFirst();
    assertThat(
            decidir(
                    administracion,
                    cargaDestajo,
                    "{\"numero\":%d,\"regla\":\"LOTE_INEXISTENTE\",\"opcion\":\"VALOR\",\"valor\":\"M20L33\"}"
                        .formatted(ilegible),
                    "{\"numero\":%d,\"regla\":\"NO_ES_PERSONA\",\"opcion\":\"ELEGIR\",\"destinoId\":%d}"
                        .formatted(monto, pepe))
                .estado())
        .isEqualTo(200);
    Respuesta destajoConfirmado =
        administracion.post("/api/v1/importaciones/%d/confirmar".formatted(cargaDestajo), null);
    assertThat(destajoConfirmado.estado()).as(destajoConfirmado.cuerpo()).isEqualTo(200);
    assertThat(numero(destajoConfirmado, "$.creados.pago_destajo")).isEqualTo(5);
    sinDiferencias(administracion, cargaDestajo);
    Respuesta conciliacion =
        administracion.get(
            "/api/v1/importaciones/%d/conciliacion?todas=true".formatted(cargaDestajo));
    List<Number> pagado =
        conciliacion.json("$.conceptos[?(@.concepto == 'Pagado por hoja y lote')].cargado");
    assertThat(pagado).hasSize(1);
    assertThat(pagado.getFirst().doubleValue()).as("al peso con el Excel").isEqualTo(9400.0);

    // Un archivo más nuevo no se suma al anterior: primero se revierte
    Respuesta otro =
        subir(
            administracion,
            "HIST_DESTAJO",
            "muestra-historico-destajo.xlsx",
            Map.of("anio", "2026"),
            otraVersion("muestra-historico-destajo.xlsx"));
    assertThat(otro.estado()).as(otro.cuerpo()).isEqualTo(201);
    long otraCarga = numero(otro, "$.id");
    Respuesta otraFilas =
        administracion.get(
            "/api/v1/importaciones/%d/filas?pendientes=true&tamano=200".formatted(otraCarga));
    decidir(
        administracion,
        otraCarga,
        "{\"numero\":%d,\"regla\":\"LOTE_INEXISTENTE\",\"opcion\":\"VALOR\",\"valor\":\"M20L33\"}"
            .formatted(
                otraFilas
                    .<List<Integer>>json("$.elementos[?(@.crudo._celda == 'O8')].numero")
                    .getFirst()),
        "{\"numero\":%d,\"regla\":\"NO_ES_PERSONA\",\"opcion\":\"ELEGIR\",\"destinoId\":%d}"
            .formatted(
                otraFilas
                    .<List<Integer>>json("$.elementos[?(@.crudo._celda == 'I9')].numero")
                    .getFirst(),
                pepe));
    Respuesta duplicada =
        administracion.post("/api/v1/importaciones/%d/confirmar".formatted(otraCarga), null);
    assertThat(duplicada.estado()).isEqualTo(409);
    assertThat(duplicada.<String>json("$.codigo")).isEqualTo("HISTORICO_YA_CARGADO");

    // El SUA y el destajo traen datos personales: solo con personas:administrar
    String coordinacion = unico("coordinacion.h5");
    crearUsuario(empresa, coordinacion, CONTRASENA, "coordinacion");
    Respuesta historia = sesion(coordinacion).get("/api/v1/historico/unidades/" + loteM20L33);
    assertThat(historia.estado()).as(historia.cuerpo()).isEqualTo(200);
    assertThat(historia.<List<Object>>json("$.pagos"))
        .as("el pago de la columna M2OL3, corregida a M20L33")
        .hasSize(1);
    assertThat(historia.<List<List<String>>>json("$.pagos[*].trabajadores"))
        .allMatch(List::isEmpty);
    assertThat(historia.<List<Object>>json("$.altas")).isEmpty();
    assertThat(historia.<Boolean>json("$.vePersonas")).isFalse();
  }
}

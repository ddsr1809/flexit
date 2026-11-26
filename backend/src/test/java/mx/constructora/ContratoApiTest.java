package mx.constructora;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Escribe el contrato OpenAPI en {@code target/openapi.json}. CI lo pasa al frontend, que genera su
 * cliente desde ahí: si un cambio del backend rompe al frontend, el build del frontend falla.
 */
class ContratoApiTest extends PruebaIntegracion {

  @Test
  void elContratoSePublicaYSeGuardaParaElFrontend() throws IOException {
    ClienteHttp.Respuesta contrato = cliente().get("/v3/api-docs");

    assertThat(contrato.estado()).isEqualTo(200);
    Map<String, Object> rutas = contrato.json("$.paths");
    assertThat(rutas)
        .containsKeys(
            "/api/v1/auth/login",
            "/api/v1/auth/yo",
            "/api/v1/auth/csrf",
            "/api/v1/usuarios",
            "/api/v1/usuarios/roles",
            "/api/v1/version",
            "/api/v1/clientes",
            "/api/v1/frentes",
            "/api/v1/prototipos",
            "/api/v1/tipos-unidad",
            "/api/v1/contratos",
            "/api/v1/contratos/{id}",
            "/api/v1/contratos/{id}/unidades",
            "/api/v1/contratos/{id}/residentes",
            "/api/v1/contratos/{id}/precios",
            "/api/v1/unidades/{id}",
            "/api/v1/residentes",
            "/api/v1/paquetes",
            "/api/v1/paquetes/{id}",
            "/api/v1/paquetes/equivalencias",
            "/api/v1/paquetes/{id}/equivalencias",
            "/api/v1/unidades-medida",
            "/api/v1/conversiones",
            "/api/v1/categorias",
            "/api/v1/clasificaciones-costo",
            "/api/v1/materiales",
            "/api/v1/materiales/buscar",
            "/api/v1/materiales/{id}",
            "/api/v1/proveedores",
            "/api/v1/proveedores/{id}",
            "/api/v1/importaciones",
            "/api/v1/importaciones/tipos",
            "/api/v1/importaciones/{id}",
            "/api/v1/importaciones/{id}/filas",
            "/api/v1/importaciones/{id}/decisiones",
            "/api/v1/importaciones/{id}/confirmar",
            "/api/v1/importaciones/{id}/revertir",
            "/api/v1/alias",
            "/api/v1/personas",
            "/api/v1/personas/{id}");

    Path destino = Path.of("target", "openapi.json");
    Files.createDirectories(destino.getParent());
    Files.writeString(destino, contrato.cuerpo());
  }
}

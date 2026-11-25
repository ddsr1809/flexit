package mx.constructora;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base de las pruebas de integración: la app completa sobre PostgreSQL 18 real, con los mismos dos
 * roles que producción. Flyway corre como {@code migrador}; la app se conecta como {@code app}, que
 * está sujeto a RLS. Un superusuario se saltaría las políticas y la prueba no probaría nada.
 *
 * <p>El contenedor es uno solo para todas las clases de prueba; cada prueba crea sus propias
 * empresas con nombres únicos para no depender del orden.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class PruebaIntegracion {

  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

  static {
    // Llamadas sueltas (sin encadenar) para no depender de si la clase es genérica.
    POSTGRES.withDatabaseName("constructora");
    POSTGRES.withUsername("postgres");
    POSTGRES.withPassword("postgres");
    POSTGRES.withCopyFileToContainer(
        MountableFile.forClasspathResource("db/roles-prueba.sql"),
        "/docker-entrypoint-initdb.d/01-roles.sql");
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void baseDePrueba(DynamicPropertyRegistry registro) {
    registro.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registro.add("spring.datasource.username", () -> "app");
    registro.add("spring.datasource.password", () -> "app-prueba");
    registro.add("app.migraciones.modo", () -> "migrar");
    registro.add("app.migraciones.url", POSTGRES::getJdbcUrl);
    registro.add("app.migraciones.usuario", () -> "migrador");
    registro.add("app.migraciones.contrasena", () -> "migrador-prueba");
  }

  /** Empresa de prueba con su identificador. */
  protected record Empresa(long id, String nombre) {}

  @Value("${local.server.port}")
  protected int puerto;

  @Autowired protected PasswordEncoder codificador;

  /** Conexión como migrador: se salta RLS. Solo para preparar datos y revisar resultados. */
  protected static JdbcClient comoMigrador() {
    return JdbcClient.create(
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "migrador", "migrador-prueba"));
  }

  protected static String unico(String prefijo) {
    return prefijo + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  protected Empresa crearEmpresa(String nombre) {
    String nombreUnico = unico(nombre);
    long id =
        comoMigrador()
            .sql("INSERT INTO organizacion.empresa (nombre) VALUES (:nombre) RETURNING id")
            .param("nombre", nombreUnico)
            .query(Long.class)
            .single();
    return new Empresa(id, nombreUnico);
  }

  protected long crearUsuario(Empresa empresa, String usuario, String contrasena, String... roles) {
    JdbcClient jdbc = comoMigrador();
    long id =
        jdbc.sql(
                """
                INSERT INTO organizacion.usuario (empresa_id, usuario, nombre, hash_contrasena)
                VALUES (:empresa, :usuario, :nombre, :hash) RETURNING id
                """)
            .param("empresa", empresa.id())
            .param("usuario", usuario)
            .param("nombre", "Usuario " + usuario)
            .param("hash", codificador.encode(contrasena))
            .query(Long.class)
            .single();
    for (String rol : roles) {
      jdbc.sql(
              "INSERT INTO organizacion.usuario_rol (empresa_id, usuario_id, rol)"
                  + " VALUES (:empresa, :usuario, :rol)")
          .param("empresa", empresa.id())
          .param("usuario", id)
          .param("rol", rol)
          .update();
    }
    return id;
  }

  protected ClienteHttp cliente() {
    return new ClienteHttp("http://localhost:" + puerto);
  }
}

package mx.constructora.importacion.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import mx.constructora.catalogo.CatalogoApi;
import mx.constructora.catalogo.ImportacionCatalogo;
import mx.constructora.compartido.Autorizacion;
import mx.constructora.compartido.ContextoEmpresa;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Permisos;
import mx.constructora.compartido.Textos;
import mx.constructora.compartido.Versiones;
import mx.constructora.estructura.ImportacionEstructura;
import mx.constructora.historico.ImportacionHistorico;
import mx.constructora.importacion.dominio.Comun;
import mx.constructora.importacion.dominio.Conciliacion;
import mx.constructora.importacion.dominio.Importador;
import mx.constructora.importacion.dominio.Importadores;
import mx.constructora.importacion.dominio.Json;
import mx.constructora.importacion.dominio.Modelo.Decision;
import mx.constructora.importacion.dominio.Modelo.Decisiones;
import mx.constructora.importacion.dominio.Modelo.EstadoFila;
import mx.constructora.importacion.dominio.Modelo.FilaCruda;
import mx.constructora.importacion.dominio.Modelo.FilaValidada;
import mx.constructora.importacion.dominio.Modelo.Lectura;
import mx.constructora.importacion.dominio.Modelo.Opcion;
import mx.constructora.importacion.dominio.Normal;
import mx.constructora.importacion.dominio.TipoCarga;
import mx.constructora.importacion.dominio.xlsx.ArchivoInvalido;
import mx.constructora.importacion.dominio.xlsx.LibroXlsx;
import mx.constructora.importacion.infraestructura.RepositorioMigracion;
import mx.constructora.importacion.infraestructura.RepositorioMigracion.Conteo;
import mx.constructora.importacion.infraestructura.RepositorioMigracion.FilaAlias;
import mx.constructora.importacion.infraestructura.RepositorioMigracion.FilaCarga;
import mx.constructora.importacion.infraestructura.RepositorioMigracion.FilaGuardada;
import mx.constructora.importacion.infraestructura.RepositorioMigracion.IncidenciaGuardada;
import mx.constructora.organizacion.DirectorioUsuarios;
import mx.constructora.organizacion.DirectorioUsuarios.UsuarioResumen;
import mx.constructora.organizacion.Personas;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Centro de migración: subir un Excel, validarlo, resolver la cuarentena, confirmar y revertir.
 * Solo administración ({@code sistema:administrar}); las cargas de personas además piden {@code
 * personas:administrar}, porque traen datos personales.
 *
 * <p>Nada se escribe en estructura, catálogo ni personas hasta confirmar, y confirmar vuelve a
 * validar dentro de la misma transacción: si alguien cambió un catálogo entre la validación y la
 * confirmación, el resultado sigue siendo correcto.
 */
@Service
public class ServicioImportacion {

  static final String ADMINISTRAR = "hasAuthority('" + Permisos.ADMINISTRAR + "')";

  /** 20 MB: los tres Excel de la constructora suman 4.6 MB. */
  public static final int TAMANO_MAXIMO = 20 * 1024 * 1024;

  public static final int PAGINA_MAXIMA = 200;

  private static final Set<String> ENTIDADES =
      Set.of(
          "frente",
          "contrato",
          "prototipo",
          "unidad_medida",
          "material",
          "proveedor",
          "persona",
          "paquete",
          "unidad_obra");

  /** Entidades que se ligan por alias (las demás solo se eligen en un renglón). */
  private static final Set<String> CON_ALIAS =
      Set.of(
          "frente",
          "contrato",
          "prototipo",
          "unidad_medida",
          "material",
          "proveedor",
          "persona",
          "paquete");

  /** Total de conciliación con más líneas que esto solo devuelve las que tienen algo que ver. */
  public static final int LINEAS_MAXIMAS = 500;

  // ------------------------------------------------------------------------------------- DTOs

  /** Un paso del orden de carga. */
  public record TipoCargaInfo(
      @Schema(requiredMode = REQUIRED) String tipo,
      @Schema(requiredMode = REQUIRED) int paso,
      @Schema(requiredMode = REQUIRED) String nombre,
      @Schema(requiredMode = REQUIRED) String origen,
      @Schema(requiredMode = REQUIRED) List<String> requiere,
      @Schema(requiredMode = REQUIRED) List<String> parametros,
      @Schema(requiredMode = REQUIRED, description = "Hay al menos una carga confirmada")
          boolean confirmada,
      @Schema(requiredMode = REQUIRED, description = "Sus cargas previas ya están confirmadas")
          boolean disponible,
      Long ultimaCargaId,
      String ultimoEstado) {}

  /** Una carga con sus totales. */
  public record ResumenCarga(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String tipo,
      @Schema(requiredMode = REQUIRED) String tipoNombre,
      @Schema(requiredMode = REQUIRED) String archivo,
      @Schema(requiredMode = REQUIRED) String hojas,
      @Schema(requiredMode = REQUIRED, description = "validada, confirmada o revertida")
          String estado,
      @Schema(requiredMode = REQUIRED) String versionReglas,
      @Schema(requiredMode = REQUIRED) String usuario,
      @Schema(requiredMode = REQUIRED) OffsetDateTime creada,
      OffsetDateTime confirmada,
      OffsetDateTime revertida,
      @Schema(requiredMode = REQUIRED) long renglones,
      @Schema(requiredMode = REQUIRED) long validos,
      @Schema(requiredMode = REQUIRED) long corregidos,
      @Schema(requiredMode = REQUIRED) long enCuarentena,
      @Schema(requiredMode = REQUIRED) long descartados,
      @Schema(requiredMode = REQUIRED) long porCrear,
      @Schema(requiredMode = REQUIRED) long existentes,
      @Schema(
              requiredMode = REQUIRED,
              description = "El mismo archivo ya estaba cargado: se devuelve esa carga")
          boolean repetida) {}

  /** Conciliación de una hoja: cada renglón leído termina en un estado. */
  public record HojaConciliada(
      @Schema(requiredMode = REQUIRED) String hoja,
      @Schema(requiredMode = REQUIRED) long leidos,
      @Schema(requiredMode = REQUIRED) long validos,
      @Schema(requiredMode = REQUIRED) long corregidos,
      @Schema(requiredMode = REQUIRED) long enCuarentena,
      @Schema(requiredMode = REQUIRED) long descartados,
      @Schema(requiredMode = REQUIRED) long porCrear,
      @Schema(requiredMode = REQUIRED) long existentes,
      @Schema(requiredMode = REQUIRED, description = "Leídos que no quedaron en ningún estado: 0")
          long diferencia) {}

  /** Cuántos renglones tocó cada regla. */
  public record ReglaAplicada(
      @Schema(requiredMode = REQUIRED) String regla,
      @Schema(requiredMode = REQUIRED) String severidad,
      @Schema(requiredMode = REQUIRED) long renglones,
      @Schema(requiredMode = REQUIRED) long pendientes,
      @Schema(requiredMode = REQUIRED) String ejemplo) {}

  /** Una carga con su conciliación por hoja y por regla. */
  public record DetalleCarga(
      @Schema(requiredMode = REQUIRED) ResumenCarga resumen,
      @Schema(requiredMode = REQUIRED) Map<String, String> parametros,
      @Schema(requiredMode = REQUIRED) List<HojaConciliada> conciliacion,
      @Schema(requiredMode = REQUIRED) List<ReglaAplicada> reglas,
      @Schema(requiredMode = REQUIRED, description = "Registros creados al confirmar, por entidad")
          Map<String, Long> creados,
      String confirmadaPor,
      String revertidaPor) {}

  /** Una incidencia de un renglón. */
  public record IncidenciaCarga(
      @Schema(requiredMode = REQUIRED) String regla,
      @Schema(requiredMode = REQUIRED, description = "info, advertencia, cuarentena o bloqueo")
          String severidad,
      @Schema(requiredMode = REQUIRED) String detalle,
      @Schema(
              description =
                  "Sugerencia: destinoId y destino; con entidad y texto, aceptarla guarda un alias")
          Map<String, Object> sugerencia,
      @Schema(requiredMode = REQUIRED, description = "ACEPTAR, ELEGIR, CREAR, VALOR o DESCARTAR")
          List<String> opciones,
      @Schema(requiredMode = REQUIRED) boolean pendiente,
      Map<String, Object> resolucion,
      String resueltaPor,
      OffsetDateTime resueltaEn) {}

  /** Un renglón de la carga. */
  public record FilaCargada(
      @Schema(requiredMode = REQUIRED) int numero,
      @Schema(requiredMode = REQUIRED) String hoja,
      Integer filaExcel,
      @Schema(requiredMode = REQUIRED) Map<String, String> crudo,
      @Schema(requiredMode = REQUIRED) Map<String, String> normal,
      @Schema(requiredMode = REQUIRED) String estado,
      @Schema(requiredMode = REQUIRED, description = "crear, existe u omitir") String accion,
      @Schema(requiredMode = REQUIRED) List<IncidenciaCarga> incidencias) {}

  public record PaginaFilas(
      @Schema(requiredMode = REQUIRED) List<FilaCargada> elementos,
      @Schema(requiredMode = REQUIRED) long total,
      @Schema(requiredMode = REQUIRED) int pagina,
      @Schema(requiredMode = REQUIRED) int tamano) {}

  /** La decisión de una persona sobre una incidencia. */
  public record DecisionCarga(
      @NotNull @Positive Integer numero,
      @NotBlank @Pattern(regexp = "^[A-Z0-9_]{3,40}$") String regla,
      @NotBlank @Pattern(regexp = "^(ACEPTAR|ELEGIR|CREAR|VALOR|DESCARTAR)$") String opcion,
      Long destinoId,
      @Size(max = 200) String valor,
      @Size(max = 300) String motivo) {}

  public record DecisionesCarga(
      @NotEmpty @Size(max = 1000) List<@Valid DecisionCarga> decisiones) {}

  /** Cómo se escribía algo en el Excel y qué es en el sistema. */
  public record AliasCarga(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String entidad,
      @Schema(requiredMode = REQUIRED) String texto,
      @Schema(requiredMode = REQUIRED) long destinoId,
      @Schema(description = "Nombre del registro; vacío si ya no existe") String destino,
      @Schema(requiredMode = REQUIRED, description = "regla, sugerencia_aceptada o manual")
          String origen,
      Long cargaId,
      String usuario,
      @Schema(requiredMode = REQUIRED) OffsetDateTime creado,
      @Schema(requiredMode = REQUIRED) int version) {}

  public record CambioAlias(@NotNull Long destinoId) {}

  /** Un registro al que se puede ligar un nombre del Excel. */
  public record DestinoPosible(
      @Schema(requiredMode = REQUIRED) long id, @Schema(requiredMode = REQUIRED) String texto) {}

  /** Una línea de la conciliación del histórico: lo que dice el Excel contra lo que se carga. */
  public record TotalConciliado(
      @Schema(requiredMode = REQUIRED) String concepto,
      @Schema(requiredMode = REQUIRED, description = "Material y mes, frente y lote, trabajador…")
          String llave,
      @Schema(requiredMode = REQUIRED) BigDecimal excel,
      @Schema(requiredMode = REQUIRED) BigDecimal cargado,
      @Schema(requiredMode = REQUIRED, description = "En cuarentena") BigDecimal pendiente,
      @Schema(requiredMode = REQUIRED, description = "Descartado con motivo") BigDecimal descartado,
      @Schema(
              requiredMode = REQUIRED,
              description = "excel − cargado − pendiente − descartado: tiene que ser 0")
          BigDecimal diferencia) {}

  /** Un concepto de la conciliación con sus sumas. */
  public record ConceptoConciliado(
      @Schema(requiredMode = REQUIRED) String concepto,
      @Schema(requiredMode = REQUIRED) BigDecimal excel,
      @Schema(requiredMode = REQUIRED) BigDecimal cargado,
      @Schema(requiredMode = REQUIRED) BigDecimal pendiente,
      @Schema(requiredMode = REQUIRED) BigDecimal descartado,
      @Schema(requiredMode = REQUIRED) BigDecimal diferencia,
      @Schema(requiredMode = REQUIRED) long lineas,
      @Schema(requiredMode = REQUIRED, description = "Líneas con diferencia distinta de 0")
          long conDiferencia) {}

  /** Conciliación de totales de una carga del histórico (H5). */
  public record ConciliacionCarga(
      @Schema(requiredMode = REQUIRED) long cargaId,
      @Schema(requiredMode = REQUIRED) List<ConceptoConciliado> conceptos,
      @Schema(
              requiredMode = REQUIRED,
              description =
                  "Líneas con diferencia, pendiente o descartado (o todas, si se piden), hasta 500")
          List<TotalConciliado> lineas,
      @Schema(requiredMode = REQUIRED) boolean recortada) {}

  // ------------------------------------------------------------------------------- servicio

  private final RepositorioMigracion repositorio;
  private final ImportacionEstructura estructura;
  private final ImportacionCatalogo catalogo;
  private final Personas personas;
  private final DirectorioUsuarios usuarios;
  private final CatalogoApi catalogoApi;
  private final ImportacionHistorico historico;

  public ServicioImportacion(
      RepositorioMigracion repositorio,
      ImportacionEstructura estructura,
      ImportacionCatalogo catalogo,
      Personas personas,
      DirectorioUsuarios usuarios,
      CatalogoApi catalogoApi,
      ImportacionHistorico historico) {
    this.repositorio = repositorio;
    this.estructura = estructura;
    this.catalogo = catalogo;
    this.personas = personas;
    this.usuarios = usuarios;
    this.catalogoApi = catalogoApi;
    this.historico = historico;
  }

  /** Los tipos de carga en su orden, con lo que ya está confirmado. */
  @PreAuthorize(ADMINISTRAR)
  @Transactional(readOnly = true)
  public List<TipoCargaInfo> orden() {
    Set<String> confirmados = new HashSet<>(repositorio.tiposConfirmados(empresa()));
    List<FilaCarga> cargas = repositorio.cargas(empresa());
    List<TipoCargaInfo> orden = new ArrayList<>();
    for (TipoCarga tipo : TipoCarga.values()) {
      Optional<FilaCarga> ultima =
          cargas.stream()
              .filter(c -> c.tipo().equals(tipo.name()) && !c.estado().equals("revertida"))
              .findFirst();
      orden.add(
          new TipoCargaInfo(
              tipo.name(),
              tipo.paso(),
              tipo.nombre(),
              tipo.origen(),
              tipo.requiere().stream().map(Enum::name).toList(),
              tipo.parametros(),
              confirmados.contains(tipo.name()),
              tipo.requiere().stream().allMatch(r -> confirmados.contains(r.name())),
              ultima.map(FilaCarga::id).orElse(null),
              ultima.map(FilaCarga::estado).orElse(null)));
    }
    return orden;
  }

  /**
   * Sube un archivo y lo valida. Si el mismo archivo ya se cargó con el mismo tipo y reglas,
   * devuelve esa carga ({@code repetida}) sin crear otra.
   */
  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public ResumenCarga subir(
      String tipoTexto, String nombre, byte[] contenido, Long clienteId, String mes, Integer anio) {
    TipoCarga tipo = tipo(tipoTexto);
    exigirPermiso(tipo);
    String archivo = Textos.limpio(nombre);
    if (archivo == null || !archivo.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
      throw ErrorNegocio.invalido("ARCHIVO_NO_XLSX", "Sube un libro de Excel (.xlsx)");
    }
    if (archivo.length() > 200) {
      archivo = archivo.substring(archivo.length() - 200);
    }
    if (contenido == null || contenido.length == 0) {
      throw ErrorNegocio.invalido("ARCHIVO_VACIO", "El archivo está vacío");
    }
    if (contenido.length > TAMANO_MAXIMO) {
      throw new ErrorNegocio(
          HttpStatus.PAYLOAD_TOO_LARGE, "ARCHIVO_GRANDE", "El archivo pasa de 20 MB");
    }
    exigirPrevias(tipo);
    Map<String, String> parametros = new LinkedHashMap<>();
    if (tipo.parametros().contains("clienteId")) {
      if (clienteId == null) {
        throw ErrorNegocio.invalido("FALTA_CLIENTE", "Elige el cliente de los frentes");
      }
      if (!estructura.existeCliente(clienteId)) {
        throw ErrorNegocio.invalido("CLIENTE_NO_ENCONTRADO", "No existe el cliente");
      }
      parametros.put("clienteId", clienteId.toString());
    }
    if (tipo.parametros().contains("mes")) {
      String valor = Textos.limpio(mes);
      if (valor == null || !valor.matches("^20[2-9][0-9]-(0[1-9]|1[0-2])$")) {
        throw ErrorNegocio.invalido("FALTA_MES", "Elige el mes del SUA (aaaa-mm)");
      }
      parametros.put("mes", valor);
    }
    if (tipo.parametros().contains("anio")) {
      if (anio == null || anio < 2020 || anio > 2100) {
        throw ErrorNegocio.invalido("FALTA_ANIO", "Elige el año de las semanas del destajo");
      }
      parametros.put("anio", anio.toString());
    }

    String sha = sha256(contenido);
    Optional<FilaCarga> vigente =
        repositorio.vigente(empresa(), sha, tipo.name(), Comun.VERSION_REGLAS);
    if (vigente.isPresent()) {
      return resumen(vigente.get(), true);
    }
    Lectura lectura = leer(tipo, contenido);
    if (lectura.filas().isEmpty()) {
      throw ErrorNegocio.invalido(
          "ARCHIVO_SIN_DATOS", "Las hojas " + lectura.hojas() + " no tienen renglones que cargar");
    }
    repositorio.guardarArchivo(empresa(), sha, archivo, contenido, usuario());
    long id =
        repositorio.crearCarga(
            empresa(),
            tipo.name(),
            archivo,
            sha,
            String.join(", ", lectura.hojas()),
            parametros,
            Comun.VERSION_REGLAS,
            usuario());
    List<FilaValidada> filas =
        Importadores.de(tipo).validar(lectura.filas(), contexto(parametros), Decisiones.ninguna());
    repositorio.reemplazarFilas(empresa(), id, filas);
    return resumen(existente(id), false);
  }

  private static Lectura leer(TipoCarga tipo, byte[] contenido) {
    Path temporal = null;
    try {
      temporal = Files.createTempFile("carga-", ".xlsx");
      Files.write(temporal, contenido);
      try (LibroXlsx libro = LibroXlsx.abrir(temporal)) {
        return Importadores.de(tipo).leer(libro);
      }
    } catch (ArchivoInvalido e) {
      throw ErrorNegocio.invalido("ARCHIVO_INVALIDO", e.getMessage());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      if (temporal != null) {
        try {
          Files.deleteIfExists(temporal);
        } catch (IOException e) {
          // El sistema operativo limpia el temporal; no vale la pena fallar la carga.
        }
      }
    }
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional(readOnly = true)
  public List<ResumenCarga> cargas() {
    return repositorio.cargas(empresa()).stream().map(c -> resumen(c, false)).toList();
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional(readOnly = true)
  public DetalleCarga carga(long id) {
    FilaCarga carga = existente(id);
    exigirPermiso(TipoCarga.valueOf(carga.tipo()));
    return detalle(carga);
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional(readOnly = true)
  public PaginaFilas filas(
      long id,
      String estado,
      String regla,
      String texto,
      boolean soloPendientes,
      int pagina,
      int tamano) {
    FilaCarga carga = existente(id);
    exigirPermiso(TipoCarga.valueOf(carga.tipo()));
    int tamanoValido = Math.clamp(tamano, 1, PAGINA_MAXIMA);
    int paginaValida = Math.max(pagina, 0);
    String estadoValido = Textos.limpio(estado);
    if (estadoValido != null) {
      EstadoFila.deClave(estadoValido);
    }
    String reglaValida = Textos.mayusculas(regla);
    String busqueda = Textos.limpio(texto);
    Map<Long, String> nombres = new LinkedHashMap<>();
    List<FilaGuardada> filas =
        repositorio.filas(
            empresa(),
            id,
            estadoValido,
            reglaValida,
            busqueda,
            soloPendientes,
            tamanoValido,
            (long) paginaValida * tamanoValido);
    Set<Long> resolvieron = new HashSet<>();
    filas.forEach(
        f ->
            f.incidencias().stream()
                .map(IncidenciaGuardada::resueltaPor)
                .filter(Objects::nonNull)
                .forEach(resolvieron::add));
    for (UsuarioResumen usuario : usuarios.buscar(resolvieron)) {
      nombres.put(usuario.id(), usuario.nombre());
    }
    long total =
        repositorio.contarFilas(empresa(), id, estadoValido, reglaValida, busqueda, soloPendientes);
    return new PaginaFilas(
        filas.stream().map(f -> fila(f, nombres)).toList(), total, paginaValida, tamanoValido);
  }

  private static FilaCargada fila(FilaGuardada fila, Map<Long, String> nombres) {
    return new FilaCargada(
        fila.numero(),
        fila.hoja(),
        fila.filaExcel(),
        fila.crudo(),
        fila.normal(),
        fila.estado(),
        fila.accion(),
        fila.incidencias().stream().map(i -> incidencia(i, nombres)).toList());
  }

  @SuppressWarnings("unchecked")
  private static IncidenciaCarga incidencia(IncidenciaGuardada i, Map<Long, String> nombres) {
    Map<String, Object> sugerencia =
        i.sugerencia() == null ? null : new LinkedHashMap<>(i.sugerencia());
    List<String> opciones = List.of();
    if (sugerencia != null && sugerencia.get("opciones") instanceof List<?> lista) {
      opciones = (List<String>) lista;
      sugerencia.remove("opciones");
    }
    boolean detiene = i.severidad().equals("cuarentena") || i.severidad().equals("bloqueo");
    return new IncidenciaCarga(
        i.regla(),
        i.severidad(),
        i.detalle(),
        sugerencia == null || sugerencia.isEmpty() ? null : sugerencia,
        opciones,
        detiene && i.resolucion() == null,
        i.resolucion(),
        i.resueltaPor() == null ? null : nombres.get(i.resueltaPor()),
        i.resueltaEn());
  }

  /**
   * Guarda decisiones y vuelve a validar. Aceptar o elegir un registro para un nombre del Excel
   * guarda un alias (vale para esta y las siguientes cargas); lo demás queda como decisión del
   * renglón.
   */
  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public DetalleCarga decidir(long id, DecisionesCarga datos) {
    FilaCarga carga = abierta(id);
    ContextoBd contexto = contexto(Map.of());
    for (DecisionCarga decision : datos.decisiones()) {
      aplicarDecision(carga, decision, contexto);
    }
    return revalidar(carga);
  }

  private void aplicarDecision(FilaCarga carga, DecisionCarga datos, ContextoBd contexto) {
    IncidenciaGuardada incidencia =
        repositorio
            .incidencia(empresa(), carga.id(), datos.numero(), datos.regla())
            .orElseThrow(
                () ->
                    ErrorNegocio.noEncontrado(
                        "INCIDENCIA_NO_ENCONTRADA",
                        "El renglón %d no tiene la incidencia %s"
                            .formatted(datos.numero(), datos.regla())));
    Map<String, Object> sugerencia =
        incidencia.sugerencia() == null ? Map.of() : incidencia.sugerencia();
    Opcion opcion = Opcion.valueOf(datos.opcion());
    Object permitidas = sugerencia.get("opciones");
    if (!(permitidas instanceof List<?> lista) || !lista.contains(opcion.name())) {
      throw ErrorNegocio.invalido(
          "OPCION_NO_PERMITIDA",
          "La incidencia %s no admite %s".formatted(datos.regla(), opcion.name()));
    }
    String motivo = Textos.limpio(datos.motivo());
    String valor = Textos.limpio(datos.valor());
    Long destino = datos.destinoId();
    switch (opcion) {
      case DESCARTAR -> {
        if (motivo == null) {
          throw ErrorNegocio.invalido("FALTA_MOTIVO", "Escribe por qué se descarta el renglón");
        }
      }
      case VALOR -> {
        if (valor == null) {
          throw ErrorNegocio.invalido("FALTA_VALOR", "Escribe el valor");
        }
      }
      case ELEGIR -> {
        if (destino == null) {
          throw ErrorNegocio.invalido("FALTA_DESTINO", "Elige el registro");
        }
      }
      case ACEPTAR -> {
        if (sugerencia.get("destinoId") == null && sugerencia.get("numero") == null) {
          throw ErrorNegocio.invalido("SIN_SUGERENCIA", "No hay sugerencia que aceptar");
        }
      }
      case CREAR -> {}
    }
    Object entidad = sugerencia.get("entidad");
    Object texto = sugerencia.get("texto");
    boolean conDestino =
        opcion == Opcion.ELEGIR
            || (opcion == Opcion.ACEPTAR && sugerencia.get("destinoId") != null);
    boolean esAlias = entidad instanceof String && texto instanceof String && conDestino;
    if (esAlias) {
      long elegido =
          opcion == Opcion.ACEPTAR ? ((Number) sugerencia.get("destinoId")).longValue() : destino;
      String nombreEntidad = (String) entidad;
      if (!CON_ALIAS.contains(nombreEntidad) || !contexto.existe(nombreEntidad, elegido)) {
        throw ErrorNegocio.invalido("DESTINO_NO_ENCONTRADO", "No existe el registro elegido");
      }
      repositorio.guardarAlias(
          empresa(),
          nombreEntidad,
          (String) texto,
          elegido,
          opcion == Opcion.ACEPTAR ? "sugerencia_aceptada" : "manual",
          carga.id(),
          usuario());
      repositorio.quitarDecision(empresa(), carga.id(), datos.numero(), datos.regla());
      return;
    }
    // Aceptar la sugerencia de un registro (el contrato de otro lote de la manzana) es elegirlo.
    if (opcion == Opcion.ACEPTAR && sugerencia.get("destinoId") != null) {
      opcion = Opcion.ELEGIR;
      destino = ((Number) sugerencia.get("destinoId")).longValue();
    }
    repositorio.guardarDecision(
        empresa(),
        carga.id(),
        new Decision(datos.numero(), datos.regla(), opcion, destino, valor, motivo),
        usuario());
  }

  /** Quita una decisión del renglón (cambio de opinión) y vuelve a validar. */
  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public DetalleCarga quitarDecision(long id, int numero, String regla) {
    FilaCarga carga = abierta(id);
    if (repositorio.quitarDecision(empresa(), id, numero, regla) == 0) {
      throw ErrorNegocio.noEncontrado("DECISION_NO_ENCONTRADA", "No hay decisión que quitar");
    }
    return revalidar(carga);
  }

  /** Vuelve a validar con los catálogos y alias de ahora (por ejemplo, después de dar un alta). */
  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public DetalleCarga revalidar(long id) {
    return revalidar(abierta(id));
  }

  private DetalleCarga revalidar(FilaCarga carga) {
    validarDeNuevo(carga);
    repositorio.marcarValidada(empresa(), carga.id());
    return detalle(existente(carga.id()));
  }

  private List<FilaValidada> validarDeNuevo(FilaCarga carga) {
    List<FilaCruda> crudas = repositorio.crudas(empresa(), carga.id());
    Map<String, String> parametros = Json.textos(carga.parametros());
    List<FilaValidada> filas =
        Importadores.de(TipoCarga.valueOf(carga.tipo()))
            .validar(
                crudas,
                contexto(parametros),
                Decisiones.de(repositorio.decisiones(empresa(), carga.id())));
    repositorio.reemplazarFilas(empresa(), carga.id(), filas);
    return filas;
  }

  /**
   * Confirma la carga: vuelve a validar y, si no queda nada en cuarentena, escribe todo en una
   * transacción.
   */
  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public DetalleCarga confirmar(long id) {
    FilaCarga carga = abierta(id);
    TipoCarga tipo = TipoCarga.valueOf(carga.tipo());
    exigirPrevias(tipo);
    List<FilaValidada> filas = validarDeNuevo(carga);
    long pendientes = filas.stream().filter(f -> f.estado() == EstadoFila.CUARENTENA).count();
    if (pendientes > 0) {
      throw ErrorNegocio.conflicto(
          "CARGA_CON_PENDIENTES",
          "Quedan %d renglones en cuarentena: resuélvelos antes de confirmar"
              .formatted(pendientes));
    }
    Map<String, String> parametros = Json.textos(carga.parametros());
    exigirUnicaEnHistorico(tipo, id, parametros);
    ContextoBd contexto = contexto(parametros);
    DestinoBd destino =
        new DestinoBd(
            empresa(), id, usuario(), estructura, catalogo, personas, historico, repositorio);
    Importador importador = Importadores.de(tipo);
    try {
      importador.aplicar(filas, contexto, destino);
    } catch (DataIntegrityViolationException e) {
      throw ErrorNegocio.conflicto(
          "CONFIRMACION_FALLIDA",
          "Un registro choca con otro que ya existe (¿se dio de alta a mano mientras tanto?)."
              + " No se guardó nada: vuelve a validar.");
    }
    Map<String, Object> resumen = new LinkedHashMap<>(destino.creados());
    repositorio.marcarConfirmada(empresa(), id, usuario(), resumen);
    return detalle(existente(id));
  }

  /**
   * Revierte la carga: si está confirmada borra lo que creó (si nada lo usa) y sus alias; si solo
   * está validada, la cierra. Un archivo revertido se puede volver a subir.
   */
  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public DetalleCarga revertir(long id) {
    FilaCarga carga =
        repositorio
            .cargaParaCambiar(empresa(), id)
            .orElseThrow(
                () -> ErrorNegocio.noEncontrado("CARGA_NO_ENCONTRADA", "No existe la carga"));
    exigirPermiso(TipoCarga.valueOf(carga.tipo()));
    if (carga.estado().equals("revertida")) {
      throw ErrorNegocio.conflicto("CARGA_REVERTIDA", "La carga ya está revertida");
    }
    if (carga.estado().equals("confirmada")) {
      exigirSinDependientesEnHistorico(TipoCarga.valueOf(carga.tipo()));
      try {
        historico.revertirCarga(id);
        estructura.revertirCarga(id);
        catalogo.revertirCarga(id);
        personas.revertirCarga(id);
      } catch (DataIntegrityViolationException e) {
        throw ErrorNegocio.conflicto(
            "CARGA_EN_USO",
            "Otros registros ya usan lo que creó esta carga (lotes, precios o movimientos)."
                + " Revierte primero las cargas que dependen de ella.");
      }
    }
    repositorio.borrarAliasDeCarga(empresa(), id);
    repositorio.marcarRevertida(empresa(), id, usuario());
    return detalle(existente(id));
  }

  /**
   * Conciliación de totales de una carga del histórico: por concepto (cantidad por material y mes,
   * pagado por lote…) lo que dice el Excel contra lo que se carga, lo que está en cuarentena y lo
   * descartado. Se calcula con los renglones de la última validación, que son los que se
   * confirmaron.
   */
  @PreAuthorize(ADMINISTRAR)
  @Transactional(readOnly = true)
  public ConciliacionCarga conciliacion(long id, boolean todas) {
    FilaCarga carga = existente(id);
    TipoCarga tipo = TipoCarga.valueOf(carga.tipo());
    exigirPermiso(tipo);
    if (!tipo.historico()) {
      throw ErrorNegocio.invalido(
          "SIN_TOTALES", "Solo las cargas del histórico tienen conciliación de totales");
    }
    List<Conciliacion.Total> totales =
        Importadores.de(tipo)
            .conciliar(
                repositorio.validadas(empresa(), id), contexto(Json.textos(carga.parametros())));
    Map<String, ConceptoConciliado> conceptos = new LinkedHashMap<>();
    List<TotalConciliado> lineas = new ArrayList<>();
    boolean recortada = false;
    for (Conciliacion.Total t : totales) {
      boolean conDiferencia = t.diferencia().signum() != 0;
      conceptos.merge(
          t.concepto(),
          new ConceptoConciliado(
              t.concepto(),
              t.excel(),
              t.cargado(),
              t.pendiente(),
              t.descartado(),
              t.diferencia(),
              1,
              conDiferencia ? 1 : 0),
          (a, b) ->
              new ConceptoConciliado(
                  a.concepto(),
                  a.excel().add(b.excel()),
                  a.cargado().add(b.cargado()),
                  a.pendiente().add(b.pendiente()),
                  a.descartado().add(b.descartado()),
                  a.diferencia().add(b.diferencia()),
                  a.lineas() + 1,
                  a.conDiferencia() + b.conDiferencia()));
      boolean interesa =
          todas || conDiferencia || t.pendiente().signum() != 0 || t.descartado().signum() != 0;
      if (interesa) {
        if (lineas.size() < LINEAS_MAXIMAS) {
          lineas.add(
              new TotalConciliado(
                  t.concepto(),
                  t.llave(),
                  t.excel(),
                  t.cargado(),
                  t.pendiente(),
                  t.descartado(),
                  t.diferencia()));
        } else {
          recortada = true;
        }
      }
    }
    return new ConciliacionCarga(id, List.copyOf(conceptos.values()), lineas, recortada);
  }

  // ------------------------------------------------------------------------------------ alias

  @PreAuthorize(ADMINISTRAR)
  @Transactional(readOnly = true)
  public List<AliasCarga> alias(String entidad, String texto) {
    String entidadValida = Textos.limpio(entidad);
    if (entidadValida != null && !CON_ALIAS.contains(entidadValida)) {
      throw ErrorNegocio.invalido("ENTIDAD_DESCONOCIDA", "No hay alias de " + entidadValida);
    }
    boolean vePersonas = Autorizacion.tiene(Permisos.PERSONAS_ADMINISTRAR);
    if ("persona".equals(entidadValida) && !vePersonas) {
      throw new AccessDeniedException("personas");
    }
    List<FilaAlias> filas = repositorio.alias(empresa(), entidadValida, Textos.limpio(texto), 1000);
    ContextoBd contexto = contexto(Map.of());
    Map<Long, String> nombres = new LinkedHashMap<>();
    usuarios
        .buscar(filas.stream().map(FilaAlias::usuarioId).filter(Objects::nonNull).toList())
        .forEach(u -> nombres.put(u.id(), u.nombre()));
    return filas.stream()
        .filter(a -> vePersonas || !a.entidad().equals("persona"))
        .map(a -> alias(a, contexto, nombres))
        .toList();
  }

  private static AliasCarga alias(FilaAlias a, ContextoBd contexto, Map<Long, String> nombres) {
    return new AliasCarga(
        a.id(),
        a.entidad(),
        a.texto(),
        a.destinoId(),
        contexto.nombre(a.entidad(), a.destinoId()),
        a.origen(),
        a.cargaId(),
        a.usuarioId() == null ? null : nombres.get(a.usuarioId()),
        a.creado(),
        a.version());
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public AliasCarga cambiarAlias(long id, int version, CambioAlias datos) {
    FilaAlias actual = aliasExistente(id);
    ContextoBd contexto = contexto(Map.of());
    if (!contexto.existe(actual.entidad(), datos.destinoId())) {
      throw ErrorNegocio.invalido("DESTINO_NO_ENCONTRADO", "No existe el registro elegido");
    }
    if (repositorio.cambiarAlias(empresa(), id, version, datos.destinoId(), usuario()) == 0) {
      throw Versiones.desactualizada();
    }
    Map<Long, String> nombres = new LinkedHashMap<>();
    usuarios.buscar(List.of(usuario())).forEach(u -> nombres.put(u.id(), u.nombre()));
    return alias(aliasExistente(id), contexto, nombres);
  }

  @PreAuthorize(ADMINISTRAR)
  @Transactional
  public void borrarAlias(long id) {
    aliasExistente(id);
    repositorio.borrarAlias(empresa(), id);
  }

  private FilaAlias aliasExistente(long id) {
    FilaAlias alias =
        repositorio
            .unAlias(empresa(), id)
            .orElseThrow(
                () -> ErrorNegocio.noEncontrado("ALIAS_NO_ENCONTRADO", "No existe el alias"));
    if (alias.entidad().equals("persona") && !Autorizacion.tiene(Permisos.PERSONAS_ADMINISTRAR)) {
      throw new AccessDeniedException("personas");
    }
    return alias;
  }

  /**
   * Registros a los que se puede ligar un nombre: para el selector de "elegir otro" en la
   * cuarentena. Busca por palabras sueltas, sin acentos.
   */
  @PreAuthorize(ADMINISTRAR)
  @Transactional(readOnly = true)
  public List<DestinoPosible> destinos(String entidad, String texto, Long frenteId) {
    if (!ENTIDADES.contains(entidad)) {
      throw ErrorNegocio.invalido("ENTIDAD_DESCONOCIDA", "No hay registros de " + entidad);
    }
    if (entidad.equals("persona") && !Autorizacion.tiene(Permisos.PERSONAS_ADMINISTRAR)) {
      throw new AccessDeniedException("personas");
    }
    ContextoBd contexto = contexto(Map.of());
    String normal = Normal.texto(texto);
    List<String> palabras = normal == null ? List.of() : Arrays.asList(normal.split(" "));
    Function<String, Boolean> coincide =
        nombre -> {
          String n = Normal.texto(nombre);
          return n != null && palabras.stream().allMatch(n::contains);
        };
    List<DestinoPosible> todos =
        switch (entidad) {
          case "frente" ->
              contexto.frentes().stream()
                  .map(f -> new DestinoPosible(f.id(), f.clave() + " · " + f.nombre()))
                  .toList();
          case "contrato" ->
              contexto.contratos().stream()
                  .filter(c -> frenteId == null || Objects.equals(c.frenteId(), frenteId))
                  .map(c -> new DestinoPosible(c.id(), c.codigo()))
                  .toList();
          case "prototipo" ->
              contexto.prototipos().stream()
                  .map(p -> new DestinoPosible(p.id(), p.clave()))
                  .toList();
          case "unidad_medida" ->
              contexto.unidadesMedida().stream()
                  .map(u -> new DestinoPosible(u.id(), u.clave() + " · " + u.nombre()))
                  .toList();
          case "material" ->
              contexto.materiales().stream()
                  .map(m -> new DestinoPosible(m.id(), m.clave() + " · " + m.nombre()))
                  .toList();
          case "proveedor" ->
              contexto.proveedores().stream()
                  .map(p -> new DestinoPosible(p.id(), p.nombre()))
                  .toList();
          case "paquete" ->
              contexto.paquetes().stream()
                  .filter(p -> p.clienteId() == null)
                  .map(p -> new DestinoPosible(p.id(), p.clave() + " · " + p.nombreNormal()))
                  .toList();
          case "unidad_obra" -> {
            Map<Long, String> frentes = new LinkedHashMap<>();
            contexto.frentes().forEach(f -> frentes.put(f.id(), f.nombre()));
            yield contexto.unidades().stream()
                .filter(u -> frenteId == null || Objects.equals(u.frenteId(), frenteId))
                .map(
                    u ->
                        new DestinoPosible(
                            u.id(), u.clave() + " · " + frentes.getOrDefault(u.frenteId(), "—")))
                .toList();
          }
          default ->
              contexto.personas().stream()
                  .map(p -> new DestinoPosible(p.id(), p.nombre()))
                  .toList();
        };
    return todos.stream().filter(d -> coincide.apply(d.texto())).limit(50).toList();
  }

  // ------------------------------------------------------------------------------- auxiliares

  private ContextoBd contexto(Map<String, String> parametros) {
    return new ContextoBd(
        estructura,
        catalogo,
        catalogoApi,
        personas,
        historico,
        repositorio.mapaAlias(empresa()),
        parametros);
  }

  private FilaCarga existente(long id) {
    return repositorio
        .carga(empresa(), id)
        .orElseThrow(() -> ErrorNegocio.noEncontrado("CARGA_NO_ENCONTRADA", "No existe la carga"));
  }

  /** La carga, bloqueada y todavía sin confirmar ni revertir. */
  private FilaCarga abierta(long id) {
    FilaCarga carga =
        repositorio
            .cargaParaCambiar(empresa(), id)
            .orElseThrow(
                () -> ErrorNegocio.noEncontrado("CARGA_NO_ENCONTRADA", "No existe la carga"));
    exigirPermiso(TipoCarga.valueOf(carga.tipo()));
    if (!carga.estado().equals("validada")) {
      throw ErrorNegocio.conflicto(
          "CARGA_CERRADA", "La carga ya está " + carga.estado() + ": no se puede cambiar");
    }
    return carga;
  }

  private void exigirPrevias(TipoCarga tipo) {
    Set<String> confirmados = new HashSet<>(repositorio.tiposConfirmados(empresa()));
    List<String> faltan =
        tipo.requiere().stream()
            .filter(r -> !confirmados.contains(r.name()))
            .map(TipoCarga::nombre)
            .toList();
    if (!faltan.isEmpty()) {
      throw ErrorNegocio.conflicto(
          "FALTA_CARGA_PREVIA", "Primero confirma la carga de " + String.join(" y ", faltan));
    }
  }

  private static void exigirPermiso(TipoCarga tipo) {
    boolean personal =
        tipo.name().startsWith("PERSONAS")
            || tipo == TipoCarga.HIST_SUA
            || tipo == TipoCarga.HIST_DESTAJO;
    if (personal && !Autorizacion.tiene(Permisos.PERSONAS_ADMINISTRAR)) {
      throw new AccessDeniedException("Las cargas de personas traen datos personales");
    }
  }

  /**
   * El histórico se carga una vez: un archivo más nuevo reemplaza al anterior revirtiendo primero
   * la carga confirmada (si no, cada renglón quedaría dos veces). El SUA, una vez por mes.
   */
  private void exigirUnicaEnHistorico(TipoCarga tipo, long id, Map<String, String> parametros) {
    if (!tipo.historico()) {
      return;
    }
    for (FilaCarga otra : repositorio.confirmadas(empresa(), tipo.name())) {
      boolean mismoMes =
          !tipo.parametros().contains("mes")
              || Objects.equals(Json.textos(otra.parametros()).get("mes"), parametros.get("mes"));
      if (otra.id() != id && mismoMes) {
        throw ErrorNegocio.conflicto(
            "HISTORICO_YA_CARGADO",
            "Ya está confirmada la carga %d (%s)%s. Para cargar un archivo más nuevo, revierte esa carga primero: el histórico se reemplaza completo"
                .formatted(
                    otra.id(),
                    otra.archivo(),
                    parametros.containsKey("mes") ? " del mes " + parametros.get("mes") : ""));
      }
    }
  }

  /**
   * Una carga que el histórico ya usa no se revierte: las salidas se valuaron con las entradas, los
   * pagos se ligaron a los paquetes. Primero se revierte el histórico.
   */
  private void exigirSinDependientesEnHistorico(TipoCarga tipo) {
    List<String> dependientes =
        Arrays.stream(TipoCarga.values())
            .filter(t -> t.historico() && t.requiere().contains(tipo))
            .filter(t -> !repositorio.confirmadas(empresa(), t.name()).isEmpty())
            .map(TipoCarga::nombre)
            .toList();
    if (!dependientes.isEmpty()) {
      throw ErrorNegocio.conflicto(
          "CARGA_EN_USO",
          "El histórico ya usa esta carga (%s). Revierte primero esas cargas."
              .formatted(String.join(", ", dependientes)));
    }
  }

  private static TipoCarga tipo(String texto) {
    try {
      return TipoCarga.valueOf(Textos.mayusculas(texto));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw ErrorNegocio.invalido("TIPO_DESCONOCIDO", "No hay cargas de tipo " + texto);
    }
  }

  private DetalleCarga detalle(FilaCarga carga) {
    List<Conteo> conteos = repositorio.conteos(empresa(), carga.id());
    Map<String, long[]> porHoja = new LinkedHashMap<>();
    for (Conteo c : conteos) {
      long[] t = porHoja.computeIfAbsent(c.hoja(), h -> new long[7]);
      t[0] += c.renglones();
      switch (c.estado()) {
        case "valida" -> t[1] += c.renglones();
        case "corregida" -> t[2] += c.renglones();
        case "cuarentena" -> t[3] += c.renglones();
        default -> t[4] += c.renglones();
      }
      if (c.accion().equals("crear") && !c.estado().equals("cuarentena")) {
        t[5] += c.renglones();
      } else if (c.accion().equals("existe")) {
        t[6] += c.renglones();
      }
    }
    List<HojaConciliada> conciliacion = new ArrayList<>();
    porHoja.forEach(
        (hoja, t) ->
            conciliacion.add(
                new HojaConciliada(
                    hoja,
                    t[0],
                    t[1],
                    t[2],
                    t[3],
                    t[4],
                    t[5],
                    t[6],
                    t[0] - t[1] - t[2] - t[3] - t[4])));
    List<ReglaAplicada> reglas =
        repositorio.conteosPorRegla(empresa(), carga.id()).stream()
            .map(
                r ->
                    new ReglaAplicada(
                        r.regla(), r.severidad(), r.renglones(), r.pendientes(), r.ejemplo()))
            .toList();
    Map<Long, String> nombres = nombresDe(carga);
    return new DetalleCarga(
        resumen(carga, false, conteos, nombres),
        Json.textos(carga.parametros()),
        conciliacion,
        reglas,
        creados(carga),
        carga.confirmadaPor() == null ? null : nombres.get(carga.confirmadaPor()),
        carga.revertidaPor() == null ? null : nombres.get(carga.revertidaPor()));
  }

  /** Lo que creó la carga: registros de catálogo, o renglones del histórico (de su resumen). */
  private Map<String, Long> creados(FilaCarga carga) {
    Map<String, Long> creados = repositorio.creados(empresa(), carga.id());
    if (TipoCarga.valueOf(carga.tipo()).historico() && carga.resumen() != null) {
      Json.objeto(carga.resumen())
          .forEach(
              (tabla, n) -> {
                if (n instanceof Number numero) {
                  creados.put(tabla, numero.longValue());
                }
              });
    }
    return creados;
  }

  private Map<Long, String> nombresDe(FilaCarga carga) {
    List<Long> ids = new ArrayList<>();
    ids.add(carga.usuarioId());
    if (carga.confirmadaPor() != null) {
      ids.add(carga.confirmadaPor());
    }
    if (carga.revertidaPor() != null) {
      ids.add(carga.revertidaPor());
    }
    Map<Long, String> nombres = new LinkedHashMap<>();
    usuarios.buscar(ids).forEach(u -> nombres.put(u.id(), u.nombre()));
    return nombres;
  }

  private ResumenCarga resumen(FilaCarga carga, boolean repetida) {
    return resumen(carga, repetida, repositorio.conteos(empresa(), carga.id()), nombresDe(carga));
  }

  private static ResumenCarga resumen(
      FilaCarga carga, boolean repetida, List<Conteo> conteos, Map<Long, String> nombres) {
    long total = 0;
    long validos = 0;
    long corregidos = 0;
    long cuarentena = 0;
    long descartados = 0;
    long crear = 0;
    long existen = 0;
    for (Conteo c : conteos) {
      total += c.renglones();
      switch (c.estado()) {
        case "valida" -> validos += c.renglones();
        case "corregida" -> corregidos += c.renglones();
        case "cuarentena" -> cuarentena += c.renglones();
        default -> descartados += c.renglones();
      }
      if (c.accion().equals("crear") && !c.estado().equals("cuarentena")) {
        crear += c.renglones();
      } else if (c.accion().equals("existe")) {
        existen += c.renglones();
      }
    }
    TipoCarga tipo = TipoCarga.valueOf(carga.tipo());
    return new ResumenCarga(
        carga.id(),
        tipo.name(),
        tipo.nombre(),
        carga.archivo(),
        carga.hojas(),
        carga.estado(),
        carga.versionReglas(),
        nombres.getOrDefault(carga.usuarioId(), "—"),
        carga.creada(),
        carga.confirmada(),
        carga.revertida(),
        total,
        validos,
        corregidos,
        cuarentena,
        descartados,
        crear,
        existen,
        repetida);
  }

  static String sha256(byte[] contenido) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static long empresa() {
    return ContextoEmpresa.requerida().empresaId();
  }

  private static long usuario() {
    return ContextoEmpresa.requerida().usuarioId();
  }
}

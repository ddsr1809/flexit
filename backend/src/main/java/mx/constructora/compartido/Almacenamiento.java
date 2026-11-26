package mx.constructora.compartido;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import mx.constructora.compartido.infraestructura.RepositorioArchivos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Archivos subidos como evidencia (fotos de merma, actas). Hoy se guardan en la base ({@code
 * compartido.archivo}); en H7 pasan a S3 sin que cambie quien los usa. El tipo se decide por los
 * primeros bytes, no por la extensión ni por lo que diga el navegador: solo JPEG, PNG, WebP y PDF,
 * hasta 10 MB.
 */
@Service
public class Almacenamiento {

  /** Tamaño máximo de un archivo: 10 MB. */
  public static final int TAMANO_MAXIMO = 10 * 1024 * 1024;

  /** Archivo guardado. */
  public record Archivo(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String nombre,
      @Schema(requiredMode = REQUIRED) String tipo,
      @Schema(requiredMode = REQUIRED) int tamano) {}

  /** Archivo con su contenido, para descargarlo. */
  public record Contenido(String nombre, String tipo, byte[] bytes) {}

  private final RepositorioArchivos repositorio;

  Almacenamiento(RepositorioArchivos repositorio) {
    this.repositorio = repositorio;
  }

  /**
   * @throws ErrorNegocio {@code ARCHIVO_VACIO}, {@code ARCHIVO_GRANDE} o {@code ARCHIVO_TIPO}
   */
  @Transactional
  public Archivo guardar(String nombre, byte[] contenido) {
    if (contenido == null || contenido.length == 0) {
      throw ErrorNegocio.invalido("ARCHIVO_VACIO", "El archivo está vacío");
    }
    if (contenido.length > TAMANO_MAXIMO) {
      throw ErrorNegocio.invalido("ARCHIVO_GRANDE_10", "El archivo pasa de 10 MB");
    }
    String tipo =
        tipoPorContenido(contenido)
            .orElseThrow(
                () ->
                    ErrorNegocio.invalido(
                        "ARCHIVO_TIPO", "Solo se aceptan fotos (JPEG, PNG, WebP) y PDF"));
    String limpio = nombreLimpio(nombre);
    var identidad = ContextoEmpresa.requerida();
    long id =
        repositorio.insertar(
            identidad.empresaId(),
            limpio,
            tipo,
            contenido,
            sha256(contenido),
            identidad.usuarioId());
    return new Archivo(id, limpio, tipo, contenido.length);
  }

  /** {@code true} si el archivo existe en la empresa en curso. */
  @Transactional(readOnly = true)
  public boolean existe(long id) {
    return repositorio.existe(ContextoEmpresa.requerida().empresaId(), id);
  }

  @Transactional(readOnly = true)
  public Optional<Contenido> leer(long id) {
    return repositorio.leer(ContextoEmpresa.requerida().empresaId(), id);
  }

  /** JPEG, PNG, WebP o PDF según los primeros bytes. */
  static Optional<String> tipoPorContenido(byte[] b) {
    if (empieza(b, 0xFF, 0xD8, 0xFF)) {
      return Optional.of("image/jpeg");
    }
    if (empieza(b, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
      return Optional.of("image/png");
    }
    if (b.length >= 12
        && empieza(b, 'R', 'I', 'F', 'F')
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P') {
      return Optional.of("image/webp");
    }
    if (empieza(b, '%', 'P', 'D', 'F', '-')) {
      return Optional.of("application/pdf");
    }
    return Optional.empty();
  }

  private static boolean empieza(byte[] b, int... firma) {
    if (b.length < firma.length) {
      return false;
    }
    for (int i = 0; i < firma.length; i++) {
      if ((b[i] & 0xFF) != firma[i]) {
        return false;
      }
    }
    return true;
  }

  /** Sin rutas ni caracteres de control; 200 caracteres como máximo. */
  static String nombreLimpio(String nombre) {
    String base = nombre == null ? "" : nombre;
    base = base.substring(Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\')) + 1);
    base = base.replaceAll("[\\p{Cntrl}\"]", "").strip();
    if (base.isEmpty()) {
      base = "archivo";
    }
    return base.length() > 200 ? base.substring(0, 200) : base;
  }

  private static String sha256(byte[] contenido) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

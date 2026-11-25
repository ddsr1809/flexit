package mx.constructora.catalogo.aplicacion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static mx.constructora.catalogo.aplicacion.ServicioCatalogo.EDITAR;
import static mx.constructora.catalogo.aplicacion.ServicioCatalogo.empresa;
import static mx.constructora.catalogo.aplicacion.ServicioCatalogo.exigirActualizado;
import static mx.constructora.catalogo.aplicacion.ServicioCatalogo.unico;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import mx.constructora.catalogo.infraestructura.RepositorioProveedores;
import mx.constructora.compartido.ErrorNegocio;
import mx.constructora.compartido.Textos;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Proveedores de material, con RFC y tasa de IVA por defecto. */
@Service
public class ServicioProveedores {

  /** Proveedor. */
  public record Proveedor(
      @Schema(requiredMode = REQUIRED) long id,
      @Schema(requiredMode = REQUIRED) String nombre,
      String rfc,
      String contacto,
      String telefono,
      String correo,
      @Schema(description = "Tasa de IVA por defecto: 0.16, 0.08 o 0") BigDecimal tasaIva,
      Integer diasCredito,
      @Schema(requiredMode = REQUIRED) boolean activo,
      @Schema(requiredMode = REQUIRED) int version) {}

  /** Alta o cambio de proveedor. */
  public record DatosProveedor(
      @NotBlank @Size(max = 160) String nombre,
      @Pattern(
              regexp = "^\\s*$|^\\s*[A-ZÑ&]{3,4}[0-9]{6}[A-Z0-9]{3}\\s*$",
              flags = Pattern.Flag.CASE_INSENSITIVE,
              message = "RFC inválido")
          String rfc,
      @Size(max = 120) String contacto,
      @Pattern(regexp = "^\\s*$|^\\s*[0-9 +()-]{7,20}\\s*$", message = "Teléfono inválido")
          String telefono,
      @Email @Size(max = 160) String correo,
      @DecimalMin("0") @DecimalMax("0.16") BigDecimal tasaIva,
      @Min(0) @Max(365) Integer diasCredito,
      Boolean activo) {}

  private final RepositorioProveedores repositorio;

  public ServicioProveedores(RepositorioProveedores repositorio) {
    this.repositorio = repositorio;
  }

  /** Proveedores; con {@code texto}, los que contienen todas sus palabras en el nombre o el RFC. */
  @Transactional(readOnly = true)
  public List<Proveedor> proveedores(String texto) {
    return repositorio.proveedores(empresa(), Textos.limpio(texto));
  }

  @PreAuthorize(EDITAR)
  @Transactional
  public Proveedor crear(DatosProveedor datos) {
    long id =
        unico(
            () ->
                repositorio.insertar(
                    empresa(),
                    Textos.limpio(datos.nombre()),
                    Textos.mayusculas(datos.rfc()),
                    Textos.limpio(datos.contacto()),
                    Textos.limpio(datos.telefono()),
                    Textos.limpio(datos.correo()),
                    datos.tasaIva(),
                    datos.diasCredito()),
            "PROVEEDOR_DUPLICADO",
            "Ya existe un proveedor con ese nombre o RFC");
    return repositorio.proveedor(empresa(), id).orElseThrow();
  }

  @PreAuthorize(EDITAR)
  @Transactional
  public Proveedor cambiar(long id, int version, DatosProveedor datos) {
    Proveedor actual =
        repositorio
            .proveedor(empresa(), id)
            .orElseThrow(
                () ->
                    ErrorNegocio.noEncontrado("PROVEEDOR_NO_ENCONTRADO", "No existe el proveedor"));
    exigirActualizado(
        unico(
            () ->
                repositorio.actualizar(
                    empresa(),
                    id,
                    version,
                    Textos.limpio(datos.nombre()),
                    Textos.mayusculas(datos.rfc()),
                    Textos.limpio(datos.contacto()),
                    Textos.limpio(datos.telefono()),
                    Textos.limpio(datos.correo()),
                    datos.tasaIva(),
                    datos.diasCredito(),
                    datos.activo() == null ? actual.activo() : datos.activo()),
            "PROVEEDOR_DUPLICADO",
            "Ya existe un proveedor con ese nombre o RFC"));
    return repositorio.proveedor(empresa(), id).orElseThrow();
  }
}

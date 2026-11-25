package mx.constructora.catalogo.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import mx.constructora.catalogo.aplicacion.ServicioCatalogo;
import mx.constructora.catalogo.aplicacion.ServicioCatalogo.Categoria;
import mx.constructora.catalogo.aplicacion.ServicioCatalogo.ClasificacionCosto;
import mx.constructora.catalogo.aplicacion.ServicioCatalogo.Conversion;
import mx.constructora.catalogo.aplicacion.ServicioCatalogo.DatosCategoria;
import mx.constructora.catalogo.aplicacion.ServicioCatalogo.DatosConversion;
import mx.constructora.catalogo.aplicacion.ServicioCatalogo.DatosFactor;
import mx.constructora.catalogo.aplicacion.ServicioCatalogo.DatosUnidadMedida;
import mx.constructora.catalogo.aplicacion.ServicioCatalogo.UnidadMedida;
import mx.constructora.compartido.Versiones;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Unidades de medida, conversiones, categorías y clasificaciones de costo. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Catálogo")
class CatalogoControlador {

  private final ServicioCatalogo servicio;

  CatalogoControlador(ServicioCatalogo servicio) {
    this.servicio = servicio;
  }

  @GetMapping("/unidades-medida")
  @Operation(operationId = "listarUnidadesMedida", summary = "Unidades del sistema y propias")
  List<UnidadMedida> unidades() {
    return servicio.unidades();
  }

  @PostMapping("/unidades-medida")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "crearUnidadMedida", summary = "Dar de alta una unidad propia")
  UnidadMedida crearUnidad(@Valid @RequestBody DatosUnidadMedida datos) {
    return servicio.crearUnidad(datos);
  }

  @PatchMapping("/unidades-medida/{id}")
  @Operation(operationId = "cambiarUnidadMedida", summary = "Cambiar una unidad propia")
  UnidadMedida cambiarUnidad(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody DatosUnidadMedida datos) {
    return servicio.cambiarUnidad(id, Versiones.deEncabezado(siCoincide), datos);
  }

  @GetMapping("/conversiones")
  @Operation(
      operationId = "listarConversiones",
      summary = "Conversiones generales y, con materialId, las de ese material")
  List<Conversion> conversiones(@RequestParam(required = false) Long materialId) {
    return servicio.conversiones(materialId);
  }

  @PostMapping("/conversiones")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "crearConversion", summary = "Dar de alta una conversión")
  Conversion crearConversion(@Valid @RequestBody DatosConversion datos) {
    return servicio.crearConversion(datos);
  }

  @PatchMapping("/conversiones/{id}")
  @Operation(operationId = "cambiarConversion", summary = "Cambiar el factor de una conversión")
  Conversion cambiarConversion(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody DatosFactor datos) {
    return servicio.cambiarConversion(id, Versiones.deEncabezado(siCoincide), datos);
  }

  @GetMapping("/categorias")
  @Operation(operationId = "listarCategorias", summary = "Categorías de material")
  List<Categoria> categorias() {
    return servicio.categorias();
  }

  @PostMapping("/categorias")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "crearCategoria", summary = "Dar de alta una categoría")
  Categoria crearCategoria(@Valid @RequestBody DatosCategoria datos) {
    return servicio.crearCategoria(datos);
  }

  @PatchMapping("/categorias/{id}")
  @Operation(operationId = "cambiarCategoria", summary = "Cambiar una categoría")
  Categoria cambiarCategoria(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody DatosCategoria datos) {
    return servicio.cambiarCategoria(id, Versiones.deEncabezado(siCoincide), datos);
  }

  @GetMapping("/clasificaciones-costo")
  @Operation(operationId = "listarClasificaciones", summary = "Razones de salida")
  List<ClasificacionCosto> clasificaciones() {
    return servicio.clasificaciones();
  }
}

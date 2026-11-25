package mx.constructora.catalogo.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import mx.constructora.catalogo.aplicacion.ServicioMateriales;
import mx.constructora.catalogo.aplicacion.ServicioMateriales.DatosMaterial;
import mx.constructora.catalogo.aplicacion.ServicioMateriales.Material;
import mx.constructora.catalogo.aplicacion.ServicioMateriales.PaginaMateriales;
import mx.constructora.compartido.Versiones;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/** Materiales: consulta paginada, búsqueda rápida, alta y cambio. */
@RestController
@RequestMapping("/api/v1/materiales")
@Tag(name = "Catálogo")
class MaterialesControlador {

  private final ServicioMateriales servicio;

  MaterialesControlador(ServicioMateriales servicio) {
    this.servicio = servicio;
  }

  @GetMapping
  @Operation(operationId = "listarMateriales", summary = "Materiales paginados con filtros")
  PaginaMateriales materiales(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Long categoriaId,
      @RequestParam(defaultValue = "false") boolean soloActivos,
      @RequestParam(defaultValue = "0") int pagina,
      @RequestParam(defaultValue = "50") int tamano) {
    return servicio.materiales(q, categoriaId, soloActivos, pagina, tamano);
  }

  @GetMapping("/buscar")
  @Operation(
      operationId = "buscarMateriales",
      summary = "Búsqueda rápida por clave o nombre parcial (20 activos)")
  List<Material> buscar(@RequestParam String q) {
    return servicio.sugerencias(q);
  }

  @GetMapping("/{id}")
  @Operation(operationId = "obtenerMaterial", summary = "Un material (ETag con su versión)")
  ResponseEntity<Material> material(@PathVariable long id) {
    Material material = servicio.material(id);
    return ResponseEntity.ok().eTag(Versiones.etag(material.version())).body(material);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "crearMaterial", summary = "Dar de alta un material")
  Material crear(@Valid @RequestBody DatosMaterial datos) {
    return servicio.crear(datos);
  }

  @PatchMapping("/{id}")
  @Operation(operationId = "cambiarMaterial", summary = "Cambiar un material")
  Material cambiar(
      @PathVariable long id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String siCoincide,
      @Valid @RequestBody DatosMaterial datos) {
    return servicio.cambiar(id, Versiones.deEncabezado(siCoincide), datos);
  }
}

package mx.constructora.inventario.aplicacion;

import static mx.constructora.inventario.aplicacion.Sesion.empresa;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import mx.constructora.catalogo.CatalogoApi;
import mx.constructora.catalogo.CatalogoApi.MaterialInfo;
import mx.constructora.estructura.EstructuraApi;
import mx.constructora.estructura.EstructuraApi.ContratoInfo;
import mx.constructora.estructura.EstructuraApi.PaqueteInfo;
import mx.constructora.estructura.EstructuraApi.UnidadInfo;
import mx.constructora.inventario.infraestructura.RepositorioAlmacenes;
import mx.constructora.organizacion.DirectorioUsuarios;
import mx.constructora.organizacion.Personas;
import org.springframework.stereotype.Component;

/**
 * Nombres para mostrar, pedidos en grupo a las APIs de los otros módulos (regla 7: inventario no
 * lee sus tablas). Una llamada por tipo de dato, no una por renglón.
 */
@Component
class Nombres {

  private final CatalogoApi catalogo;
  private final EstructuraApi estructura;
  private final Personas personas;
  private final DirectorioUsuarios usuarios;
  private final RepositorioAlmacenes almacenes;

  Nombres(
      CatalogoApi catalogo,
      EstructuraApi estructura,
      Personas personas,
      DirectorioUsuarios usuarios,
      RepositorioAlmacenes almacenes) {
    this.catalogo = catalogo;
    this.estructura = estructura;
    this.personas = personas;
    this.usuarios = usuarios;
    this.almacenes = almacenes;
  }

  Map<Long, MaterialInfo> materiales(Collection<Long> ids) {
    return porId(catalogo.materiales(sinNulos(ids)), MaterialInfo::id);
  }

  Map<Long, String> unidadesMedida() {
    return catalogo.unidades().stream()
        .collect(Collectors.toMap(CatalogoApi.UnidadInfo::id, CatalogoApi.UnidadInfo::clave));
  }

  Map<Long, ContratoInfo> contratos(Collection<Long> ids) {
    return porId(estructura.contratos(sinNulos(ids)), ContratoInfo::id);
  }

  Map<Long, UnidadInfo> unidades(Collection<Long> ids) {
    return porId(estructura.unidades(sinNulos(ids)), UnidadInfo::id);
  }

  Map<Long, PaqueteInfo> paquetes(Collection<Long> ids) {
    return porId(estructura.paquetes(sinNulos(ids)), PaqueteInfo::id);
  }

  Map<Integer, String> clasificaciones() {
    return catalogo.clasificaciones().stream()
        .collect(
            Collectors.toMap(
                CatalogoApi.ClasificacionInfo::id, CatalogoApi.ClasificacionInfo::nombre));
  }

  Map<Long, String> personas(Collection<Long> ids) {
    return personas.nombres(sinNulos(ids)).stream()
        .collect(Collectors.toMap(Personas.PersonaNombre::id, Personas.PersonaNombre::nombre));
  }

  Map<Long, String> usuarios(Collection<Long> ids) {
    return usuarios.buscar(sinNulos(ids)).stream()
        .collect(
            Collectors.toMap(
                DirectorioUsuarios.UsuarioResumen::id, DirectorioUsuarios.UsuarioResumen::nombre));
  }

  Map<Long, String> almacenes() {
    Map<Long, String> claves = new HashMap<>();
    almacenes.almacenes(empresa()).forEach(a -> claves.put(a.id(), a.clave()));
    return claves;
  }

  Map<Long, String> proveedores(Collection<Long> ids) {
    return catalogo.proveedores(sinNulos(ids)).stream()
        .collect(
            Collectors.toMap(CatalogoApi.ProveedorInfo::id, CatalogoApi.ProveedorInfo::nombre));
  }

  private static java.util.List<Long> sinNulos(Collection<Long> ids) {
    return ids.stream().filter(Objects::nonNull).distinct().toList();
  }

  private static <T> Map<Long, T> porId(Collection<T> elementos, Function<T, Long> id) {
    return elementos.stream().collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
  }
}

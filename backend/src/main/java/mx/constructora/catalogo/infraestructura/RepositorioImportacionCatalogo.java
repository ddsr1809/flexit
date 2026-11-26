package mx.constructora.catalogo.infraestructura;

import java.math.BigDecimal;
import java.util.List;
import mx.constructora.catalogo.ImportacionCatalogo.MaterialDato;
import mx.constructora.catalogo.ImportacionCatalogo.ProveedorDato;
import mx.constructora.catalogo.ImportacionCatalogo.UnidadDato;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Lecturas completas y altas marcadas con la carga, para la migración desde Excel. */
@Repository
public class RepositorioImportacionCatalogo {

  private final JdbcClient jdbc;

  RepositorioImportacionCatalogo(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<UnidadDato> unidades(long empresa) {
    return jdbc.sql(
            """
            SELECT id, clave, nombre FROM catalogo.unidad_medida
             WHERE (empresa_id IS NULL OR empresa_id = :empresa) AND activa
             ORDER BY empresa_id NULLS FIRST, clave
            """)
        .param("empresa", empresa)
        .query(UnidadDato.class)
        .list();
  }

  public List<MaterialDato> materiales(long empresa) {
    return jdbc.sql(
            """
            SELECT id, clave, nombre, nombre_normal FROM catalogo.material
             WHERE empresa_id = :empresa ORDER BY id
            """)
        .param("empresa", empresa)
        .query(MaterialDato.class)
        .list();
  }

  public List<ProveedorDato> proveedores(long empresa) {
    return jdbc.sql(
            """
            SELECT id, nombre, nombre_normal FROM catalogo.proveedor
             WHERE empresa_id = :empresa ORDER BY id
            """)
        .param("empresa", empresa)
        .query(ProveedorDato.class)
        .list();
  }

  public long insertarMaterial(
      long empresa, long carga, String clave, String nombre, long unidad, BigDecimal precio) {
    return jdbc.sql(
            """
            INSERT INTO catalogo.material
              (empresa_id, clave, nombre, unidad_control_id, precio_referencia, origen_carga)
            VALUES (:empresa, :clave, :nombre, :unidad, :precio, :carga)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("clave", clave)
        .param("nombre", nombre)
        .param("unidad", unidad)
        .param("precio", precio)
        .param("carga", carga)
        .query(Long.class)
        .single();
  }

  public long insertarProveedor(long empresa, long carga, String nombre, BigDecimal tasaIva) {
    return jdbc.sql(
            """
            INSERT INTO catalogo.proveedor (empresa_id, nombre, tasa_iva, origen_carga)
            VALUES (:empresa, :nombre, :tasa, :carga)
            RETURNING id
            """)
        .param("empresa", empresa)
        .param("nombre", nombre)
        .param("tasa", tasaIva)
        .param("carga", carga)
        .query(Long.class)
        .single();
  }

  public int revertir(long carga) {
    return jdbc.sql("SELECT catalogo.revertir_carga(:carga)")
        .param("carga", carga)
        .query(Integer.class)
        .single();
  }
}

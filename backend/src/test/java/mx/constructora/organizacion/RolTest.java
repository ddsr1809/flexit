package mx.constructora.organizacion;

import static mx.constructora.compartido.Permisos.CATALOGO_EDITAR;
import static mx.constructora.compartido.Permisos.COSTO_VER_PROPIO;
import static mx.constructora.compartido.Permisos.COSTO_VER_TODO;
import static mx.constructora.compartido.Permisos.DOCUMENTO_AUTORIZAR;
import static mx.constructora.compartido.Permisos.ESTRUCTURA_VER_TODO;
import static mx.constructora.compartido.Permisos.INCIDENTE_ATENDER;
import static mx.constructora.compartido.Permisos.MOVIMIENTO_CAPTURAR;
import static mx.constructora.compartido.Permisos.PEDIDO_CREAR;
import static mx.constructora.compartido.Permisos.PERSONAS_ADMINISTRAR;
import static mx.constructora.compartido.Permisos.PRESUPUESTO_EDITAR;
import static mx.constructora.compartido.Permisos.REQUISICION_CREAR;
import static mx.constructora.compartido.Permisos.USUARIOS_ADMINISTRAR;
import static mx.constructora.compartido.Permisos.VALE_AUTORIZAR;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import mx.constructora.organizacion.aplicacion.UsuarioSesion;
import org.junit.jupiter.api.Test;

/** La matriz "Roles y permisos" del plan técnico, fila por fila. */
class RolTest {

  @Test
  void losIncidentesLosAtiendeAdministracion() {
    for (Rol rol : Rol.values()) {
      assertThat(rol.permisos().contains(INCIDENTE_ATENDER))
          .as(rol.clave())
          .isEqualTo(rol == Rol.ADMIN);
    }
  }

  @Test
  void soloElAlmacenistaCapturaMovimientos() {
    for (Rol rol : Rol.values()) {
      assertThat(rol.permisos().contains(MOVIMIENTO_CAPTURAR))
          .as(rol.clave())
          .isEqualTo(rol == Rol.ALMACENISTA);
    }
  }

  @Test
  void requisicionesLasCreanResidenteYCoordinacion() {
    assertThat(Rol.RESIDENTE.permisos()).contains(REQUISICION_CREAR);
    assertThat(Rol.COORDINACION.permisos()).contains(REQUISICION_CREAR);
    assertThat(Rol.ALMACENISTA.permisos()).doesNotContain(REQUISICION_CREAR);
  }

  @Test
  void autorizanCoordinacionYDireccion() {
    for (Rol rol : Rol.values()) {
      assertThat(rol.permisos().contains(DOCUMENTO_AUTORIZAR))
          .as(rol.clave())
          .isEqualTo(rol == Rol.COORDINACION || rol == Rol.DIRECCION);
    }
  }

  @Test
  void losValesLosAutorizanResidenteCoordinacionYDireccion() {
    for (Rol rol : Rol.values()) {
      assertThat(rol.permisos().contains(VALE_AUTORIZAR))
          .as(rol.clave())
          .isEqualTo(rol == Rol.RESIDENTE || rol == Rol.COORDINACION || rol == Rol.DIRECCION);
    }
  }

  @Test
  void soloComprasCreaPedidos() {
    for (Rol rol : Rol.values()) {
      assertThat(rol.permisos().contains(PEDIDO_CREAR))
          .as(rol.clave())
          .isEqualTo(rol == Rol.COMPRAS);
    }
  }

  @Test
  void elPresupuestoLoAsignanCoordinacionDireccionYAdministracion() {
    for (Rol rol : Rol.values()) {
      assertThat(rol.permisos().contains(PRESUPUESTO_EDITAR))
          .as(rol.clave())
          .isEqualTo(rol == Rol.COORDINACION || rol == Rol.DIRECCION || rol == Rol.ADMIN);
    }
  }

  @Test
  void elResidenteSoloVeElCostoDeSusLotes() {
    assertThat(Rol.RESIDENTE.permisos()).contains(COSTO_VER_PROPIO).doesNotContain(COSTO_VER_TODO);
    assertThat(Rol.ALMACENISTA.permisos()).doesNotContain(COSTO_VER_PROPIO, COSTO_VER_TODO);
  }

  @Test
  void soloAdministracionAdministraUsuarios() {
    for (Rol rol : Rol.values()) {
      assertThat(rol.permisos().contains(USUARIOS_ADMINISTRAR))
          .as(rol.clave())
          .isEqualTo(rol == Rol.ADMIN);
    }
  }

  @Test
  void losDatosPersonalesSoloLosVeAdministracion() {
    for (Rol rol : Rol.values()) {
      assertThat(rol.permisos().contains(PERSONAS_ADMINISTRAR))
          .as(rol.clave())
          .isEqualTo(rol == Rol.ADMIN);
    }
  }

  @Test
  void soloElResidenteVeUnicamenteSusContratos() {
    for (Rol rol : Rol.values()) {
      assertThat(rol.permisos().contains(ESTRUCTURA_VER_TODO))
          .as(rol.clave())
          .isEqualTo(rol != Rol.RESIDENTE);
    }
  }

  @Test
  void elCatalogoLoEditanComprasYAdministracion() {
    for (Rol rol : Rol.values()) {
      assertThat(rol.permisos().contains(CATALOGO_EDITAR))
          .as(rol.clave())
          .isEqualTo(rol == Rol.COMPRAS || rol == Rol.ADMIN);
    }
  }

  @Test
  void laSesionExponeRolesYPermisosComoAutoridades() {
    var sesion =
        new UsuarioSesion(
            1, 2, "Empresa", "ana", "Ana", "{argon2}x", true, null, List.of(Rol.ALMACENISTA));
    assertThat(sesion.getAuthorities())
        .extracting(Object::toString)
        .contains("ROLE_ALMACENISTA", MOVIMIENTO_CAPTURAR);
    sesion.eraseCredentials();
    assertThat(sesion.getPassword()).isNull();
  }

  @Test
  void unaCuentaBloqueadaSeDesbloqueaSola() {
    var bloqueada =
        new UsuarioSesion(
            1, 2, "E", "ana", "Ana", "x", true, Instant.now().plusSeconds(60), List.of());
    var vencida =
        new UsuarioSesion(
            1, 2, "E", "ana", "Ana", "x", true, Instant.now().minusSeconds(1), List.of());
    assertThat(bloqueada.isAccountNonLocked()).isFalse();
    assertThat(vencida.isAccountNonLocked()).isTrue();
  }
}

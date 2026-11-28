package mx.constructora.abastecimiento.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import mx.constructora.abastecimiento.infraestructura.RepositorioRequisiciones;
import mx.constructora.abastecimiento.infraestructura.RepositorioRequisiciones.Folios;
import mx.constructora.inventario.AvisosDeCierre;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Avisos del abastecimiento antes de cerrar un mes del inventario (H9): requisiciones autorizadas
 * que se necesitaban en el mes y siguen sin surtir (lo apartado sigue apartado) y requisiciones que
 * nadie ha autorizado. No impiden cerrar.
 */
@Component
class AvisosCierreAbastecimiento implements AvisosDeCierre {

  private final RepositorioRequisiciones requisiciones;

  AvisosCierreAbastecimiento(RepositorioRequisiciones requisiciones) {
    this.requisiciones = requisiciones;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Aviso> avisos(LocalDate desde, LocalDate hasta) {
    List<Aviso> avisos = new ArrayList<>();
    Folios abiertas = requisiciones.autorizadasAbiertas(Acceso.empresa(), hasta);
    if (abiertas.total() > 0) {
      avisos.add(
          new Aviso(
              "REQUISICIONES_SIN_SURTIR",
              abiertas.total()
                  + (abiertas.total() == 1
                      ? " requisición autorizada que se necesitaba en el mes sigue abierta"
                      : " requisiciones autorizadas que se necesitaban en el mes siguen abiertas")
                  + ": lo apartado sigue apartado. Surte lo que falta o ciérralas.",
              abiertas.total(),
              abiertas.primeros()));
    }
    Folios pendientes = requisiciones.sinAutorizar(Acceso.empresa(), hasta);
    if (pendientes.total() > 0) {
      avisos.add(
          new Aviso(
              "REQUISICIONES_SIN_AUTORIZAR",
              pendientes.total()
                  + (pendientes.total() == 1
                      ? " requisición espera autorización"
                      : " requisiciones esperan autorización")
                  + " desde el mes o antes.",
              pendientes.total(),
              pendientes.primeros()));
    }
    return avisos;
  }
}

package mx.constructora.inventario.dominio;

import java.math.BigDecimal;

/** La salida pide más de lo que hay. El servicio la traduce a {@code EXISTENCIA_INSUFICIENTE}. */
public class ExistenciaInsuficiente extends RuntimeException {

  private final BigDecimal disponible;
  private final BigDecimal solicitada;

  public ExistenciaInsuficiente(BigDecimal disponible, BigDecimal solicitada) {
    super("Se piden " + solicitada.toPlainString() + " y hay " + disponible.toPlainString());
    this.disponible = disponible;
    this.solicitada = solicitada;
  }

  public BigDecimal disponible() {
    return disponible;
  }

  public BigDecimal solicitada() {
    return solicitada;
  }
}

package mx.constructora.reportes.dominio;

import java.time.LocalDate;
import java.util.List;

/**
 * El criterio de aceptación de H9 (ADR-015): dos semanas seguidas sin capturar en Excel, cero
 * incidentes críticos abiertos y el primer cierre de mes hecho.
 *
 * <p>El sistema no ve el Excel: que nadie lo tocó lo prueba {@code ensayo.py verificar} con el
 * sha256 del archivo congelado. Lo que el sistema sí ve es si lo que pasa en el almacén se captura
 * a tiempo. Un día cuenta para la racha si no tiene documentos capturados más de {@link
 * #TOLERANCIA_DIAS} día después de su fecha; un día sin documentos (un domingo) no la rompe. La
 * racha se cuenta hacia atrás desde el último día y necesita al menos un vale aplicado.
 */
public final class CriterioEstabilizacion {

  /** Días después de su fecha en que todavía se acepta capturar un documento. */
  public static final int TOLERANCIA_DIAS = 1;

  /** Días seguidos sin capturas tardías que pide el criterio. */
  public static final int DIAS_REQUERIDOS = 14;

  /** Un día del almacén: vales aplicados y documentos capturados tarde. */
  public record DiaUso(LocalDate fecha, int salidas, int tardios) {}

  /** El resultado del criterio. */
  public record Resultado(
      int racha,
      int valesEnRacha,
      boolean dosSemanasATiempo,
      boolean sinCriticos,
      boolean primerCierre,
      boolean cumple) {}

  private CriterioEstabilizacion() {}

  /** Días seguidos sin capturas tardías, contados hacia atrás desde el último día de la lista. */
  public static int racha(List<DiaUso> dias) {
    int racha = 0;
    for (int i = dias.size() - 1; i >= 0; i--) {
      if (dias.get(i).tardios() > 0) {
        break;
      }
      racha++;
    }
    return racha;
  }

  /** Vales aplicados en los últimos {@code racha} días de la lista. */
  static int vales(List<DiaUso> dias, int racha) {
    int vales = 0;
    for (int i = dias.size() - racha; i < dias.size(); i++) {
      vales += dias.get(i).salidas();
    }
    return vales;
  }

  /**
   * Evalúa el criterio. {@code dias} va en orden de fecha y empieza a más tardar el día de la
   * apertura del almacén.
   *
   * @param criticosAbiertos incidentes críticos abiertos hoy
   * @param primerCierre si ya se cerró un mes desde la apertura
   */
  public static Resultado evaluar(List<DiaUso> dias, int criticosAbiertos, boolean primerCierre) {
    int racha = racha(dias);
    int vales = vales(dias, racha);
    boolean aTiempo = racha >= DIAS_REQUERIDOS && vales > 0;
    boolean sinCriticos = criticosAbiertos == 0;
    return new Resultado(
        racha, vales, aTiempo, sinCriticos, primerCierre, aTiempo && sinCriticos && primerCierre);
  }
}

package mx.constructora.importacion.dominio;

import java.util.EnumMap;
import java.util.Map;
import mx.constructora.importacion.dominio.ImportadorPersonas.Perfil;

/** Un importador por tipo de carga. */
public final class Importadores {

  private static final Map<TipoCarga, Importador> POR_TIPO = new EnumMap<>(TipoCarga.class);

  static {
    for (Importador importador :
        new Importador[] {
          new ImportadorFrentes(),
          new ImportadorContratos(),
          new ImportadorLotes(),
          new ImportadorPaquetes(),
          new ImportadorMateriales(),
          new ImportadorProveedores(),
          new ImportadorPersonas(Perfil.SUA),
          new ImportadorPersonas(Perfil.DESTAJO),
          new ImportadorPersonas(Perfil.ALMACEN),
          new ImportadorEntradasHistoricas(),
          new ImportadorSalidasHistoricas(),
          new ImportadorRequisicionesHistoricas(),
          new ImportadorPrestamosHistoricos(),
          new ImportadorExistenciasHistoricas(),
          new ImportadorSuaHistorico(),
          new ImportadorDestajoHistorico()
        }) {
      POR_TIPO.put(importador.tipo(), importador);
    }
  }

  private Importadores() {}

  public static Importador de(TipoCarga tipo) {
    Importador importador = POR_TIPO.get(tipo);
    if (importador == null) {
      throw new IllegalArgumentException("No hay importador para " + tipo);
    }
    return importador;
  }
}

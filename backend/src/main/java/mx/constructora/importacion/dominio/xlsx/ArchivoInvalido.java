package mx.constructora.importacion.dominio.xlsx;

/** El archivo no se puede leer como libro de Excel, o no tiene lo que la carga espera. */
public class ArchivoInvalido extends RuntimeException {

  public ArchivoInvalido(String mensaje) {
    super(mensaje);
  }

  public ArchivoInvalido(String mensaje, Throwable causa) {
    super(mensaje, causa);
  }
}

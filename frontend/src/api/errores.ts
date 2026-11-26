/**
 * Errores de la API. El backend responde Problem Details (RFC 9457) con un `codigo` estable; aquí,
 * y solo aquí, se decide qué ve la persona.
 */
export interface ProblemaApi {
  codigo?: string
  detail?: string
  status?: number
  campos?: Record<string, string>
}

const MENSAJES: Record<string, string> = {
  CREDENCIALES_INVALIDAS: 'Usuario o contraseña incorrectos.',
  CUENTA_BLOQUEADA:
    'La cuenta quedó bloqueada por varios intentos fallidos. Intenta de nuevo en 15 minutos.',
  CUENTA_INACTIVA: 'Esta cuenta está desactivada. Pide a administración que la reactive.',
  SIN_PERMISO: 'No tienes permiso para esta operación.',
  USUARIO_DUPLICADO: 'Ya existe un usuario con ese nombre.',
  ROL_DESCONOCIDO: 'Uno de los roles elegidos no existe.',
  CONTRASENA_CORTA: 'La contraseña debe tener al menos 10 caracteres.',
  VALIDACION: 'Revisa los campos marcados.',
  VERSION_DESACTUALIZADA:
    'Alguien más guardó un cambio en este registro. Se recargó: revisa y vuelve a guardar.',
  FALTA_VERSION: 'No se pudo confirmar qué versión estabas editando. Recarga la pantalla.',
  DUPLICADO: 'Ya existe un registro con esos datos.',
  DATOS_INCONSISTENTES: 'Los datos no son consistentes con otros registros.',
  CUERPO_INVALIDO: 'La información enviada no tiene el formato esperado.',
  ARCHIVO_NO_XLSX: 'Sube un libro de Excel (.xlsx). Si está en .xls, ábrelo y guárdalo como .xlsx.',
  ARCHIVO_GRANDE: 'El archivo pasa de 20 MB.',
  ARCHIVO_VACIO: 'El archivo está vacío.',
  FALTA_MOTIVO: 'Escribe por qué se descarta.',
  FALTA_VALOR: 'Escribe el valor.',
  FALTA_DESTINO: 'Elige el registro.',
  CARGA_CERRADA: 'La carga ya se confirmó o se revirtió: ya no se puede cambiar.',
  PERSONA_DUPLICADA: 'Otra persona ya tiene ese NSS o CURP.',
  ERROR_INTERNO: 'Ocurrió un error inesperado. Si se repite, avisa a sistemas.',
}

export class ErrorApi extends Error {
  readonly codigo: string
  readonly estado: number
  readonly campos: Record<string, string>

  constructor(estado: number, problema: ProblemaApi | undefined) {
    const codigo = problema?.codigo ?? (estado === 403 ? 'SIN_PERMISO' : 'ERROR_INTERNO')
    super(MENSAJES[codigo] ?? problema?.detail ?? MENSAJES.ERROR_INTERNO)
    this.name = 'ErrorApi'
    this.codigo = codigo
    this.estado = estado
    this.campos = problema?.campos ?? {}
  }
}

export function mensajeDe(error: unknown): string {
  if (error instanceof ErrorApi) return error.message
  return 'No se pudo conectar con el servidor. Revisa tu conexión e intenta de nuevo.'
}

/** Convierte la respuesta de openapi-fetch en el dato o en un `ErrorApi`. */
export function exigir<T>(resultado: { data?: T; error?: unknown; response: Response }): T {
  if (resultado.response.ok && resultado.data !== undefined) return resultado.data
  if (resultado.response.ok) return undefined as T
  throw new ErrorApi(resultado.response.status, resultado.error as ProblemaApi | undefined)
}

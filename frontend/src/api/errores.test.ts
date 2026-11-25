import { ErrorApi, exigir, mensajeDe } from './errores'

const respuesta = (status: number) => new Response(null, { status })

describe('errores de la API', () => {
  it('traduce el código del backend a un mensaje claro', () => {
    const error = new ErrorApi(401, { codigo: 'CUENTA_BLOQUEADA', detail: 'texto del servidor' })
    expect(error.message).toMatch(/15 minutos/)
    expect(error.codigo).toBe('CUENTA_BLOQUEADA')
  })

  it('usa el detalle del servidor si el código es nuevo', () => {
    expect(new ErrorApi(409, { codigo: 'ALGO_NUEVO', detail: 'Detalle' }).message).toBe('Detalle')
  })

  it('un 403 sin cuerpo es falta de permiso', () => {
    expect(new ErrorApi(403, undefined).codigo).toBe('SIN_PERMISO')
  })

  it('exigir devuelve el dato o lanza ErrorApi con los campos', () => {
    expect(exigir({ data: { a: 1 }, response: respuesta(200) })).toEqual({ a: 1 })
    try {
      exigir({
        error: { codigo: 'VALIDACION', campos: { nombre: 'vacío' } },
        response: respuesta(400),
      })
      expect.unreachable()
    } catch (error) {
      expect(error).toBeInstanceOf(ErrorApi)
      expect((error as ErrorApi).campos).toEqual({ nombre: 'vacío' })
    }
  })

  it('un fallo de red se explica sin jerga', () => {
    expect(mensajeDe(new TypeError('Failed to fetch'))).toMatch(/conexión/)
  })
})

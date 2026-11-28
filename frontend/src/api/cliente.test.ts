import type { MiddlewareCallbackParams } from 'openapi-fetch'
import { registrarFallas, ultimaFalla } from './cliente'

const responder = (estado: number, peticion?: string) =>
  registrarFallas.onResponse!({
    request: new Request('http://local/api/v1/movimientos/12/aplicar', { method: 'POST' }),
    response: new Response(null, {
      status: estado,
      headers: peticion ? { 'X-Request-Id': peticion } : {},
    }),
  } as MiddlewareCallbackParams & { response: Response })

describe('última petición que falló', () => {
  it('guarda el X-Request-Id, el método, la ruta y el estado de un error', async () => {
    await responder(500, 'abc-123')
    expect(ultimaFalla()).toEqual({
      peticion: 'abc-123',
      metodo: 'POST',
      ruta: '/api/v1/movimientos/12/aplicar',
      estado: 500,
    })
  })

  it('una respuesta correcta o una sesión vencida no la cambian', async () => {
    await responder(500, 'primera')
    await responder(200, 'bien')
    await responder(401, 'sesion')
    expect(ultimaFalla()?.peticion).toBe('primera')
  })
})

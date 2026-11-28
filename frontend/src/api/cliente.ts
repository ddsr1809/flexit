import createClient, { type Middleware } from 'openapi-fetch'
import type { paths } from './esquema'

/**
 * Único punto de acceso a la API. Los tipos salen de `openapi.json` (npm run gen:api), así que un
 * cambio del backend que rompe al frontend rompe el build.
 *
 * Seguridad: la sesión viaja en una cookie HttpOnly del mismo dominio. Cada petición que modifica
 * algo lleva el token CSRF de la cookie `XSRF-TOKEN` en el encabezado `X-XSRF-TOKEN`.
 */
export const api = createClient<paths>({ baseUrl: '', credentials: 'same-origin' })

const METODOS_SEGUROS = new Set(['GET', 'HEAD', 'OPTIONS'])

export function leerCookie(nombre: string): string | undefined {
  const par = document.cookie.split('; ').find((c) => c.startsWith(`${nombre}=`))
  return par ? decodeURIComponent(par.slice(nombre.length + 1)) : undefined
}

/** Pide el token si todavía no hay cookie (primera visita o después de cerrar sesión). */
async function asegurarTokenCsrf(): Promise<string | undefined> {
  const existente = leerCookie('XSRF-TOKEN')
  if (existente) return existente
  await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })
  return leerCookie('XSRF-TOKEN')
}

const csrf: Middleware = {
  async onRequest({ request }) {
    if (METODOS_SEGUROS.has(request.method)) return request
    const token = await asegurarTokenCsrf()
    if (token) request.headers.set('X-XSRF-TOKEN', token)
    return request
  },
}

/** La última petición que falló: la pantalla para reportar un problema la propone. */
export interface Falla {
  /** X-Request-Id que devolvió el servidor: con él se encuentra el error en los logs. */
  peticion: string
  metodo: string
  ruta: string
  estado: number
}

let falla: Falla | undefined

export const ultimaFalla = (): Falla | undefined => falla

/** Guarda la última respuesta de error (salvo 401, que es solo sesión vencida). */
export const registrarFallas: Middleware = {
  onResponse({ request, response }) {
    const peticion = response.headers.get('X-Request-Id')
    if (response.status >= 400 && response.status !== 401 && peticion) {
      falla = {
        peticion,
        metodo: request.method,
        ruta: new URL(request.url, 'http://local').pathname,
        estado: response.status,
      }
    }
    return response
  },
}

api.use(csrf)
api.use(registrarFallas)

/** Cierre de sesión. Lo atiende Spring Security directamente, por eso no está en el contrato. */
export async function cerrarSesionEnServidor(): Promise<boolean> {
  const token = await asegurarTokenCsrf()
  const respuesta = await fetch('/api/v1/auth/logout', {
    method: 'POST',
    credentials: 'same-origin',
    headers: token ? { 'X-XSRF-TOKEN': token } : {},
  })
  return respuesta.ok
}

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

api.use(csrf)

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

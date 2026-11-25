import { queryOptions, useMutation, useQueryClient, useSuspenseQuery } from '@tanstack/react-query'
import { api, cerrarSesionEnServidor } from '@/api/cliente'
import { exigir } from '@/api/errores'
import type { components } from '@/api/esquema'

export type Sesion = components['schemas']['SesionDto']

/** Permisos finos; deben coincidir con mx.constructora.compartido.Permisos. */
export const PERMISOS = {
  movimientoCapturar: 'movimiento:capturar',
  requisicionCrear: 'requisicion:crear',
  documentoAutorizar: 'documento:autorizar',
  pedidoCrear: 'pedido:crear',
  existenciasVer: 'existencias:ver',
  precioVer: 'precio:ver',
  costoVerPropio: 'costo:ver-propio',
  costoVerTodo: 'costo:ver-todo',
  administrar: 'sistema:administrar',
  usuariosAdministrar: 'usuarios:administrar',
  catalogoEditar: 'catalogo:editar',
  estructuraVerTodo: 'estructura:ver-todo',
} as const

export type Permiso = (typeof PERMISOS)[keyof typeof PERMISOS]

/** Cómo se le describe cada permiso a la persona. */
export const DESCRIPCION_PERMISO: Record<string, string> = {
  'movimiento:capturar': 'Capturar entradas, vales y devoluciones',
  'requisicion:crear': 'Crear requisiciones',
  'documento:autorizar': 'Autorizar requisiciones y traspasos',
  'pedido:crear': 'Crear pedidos a proveedor',
  'existencias:ver': 'Consultar existencias',
  'precio:ver': 'Ver precios de compra',
  'costo:ver-propio': 'Ver el costo de sus contratos',
  'costo:ver-todo': 'Ver el costo de todos los contratos',
  'sistema:administrar': 'Administrar contratos, lotes y paquetes',
  'usuarios:administrar': 'Dar de alta usuarios y roles',
  'catalogo:editar': 'Dar de alta materiales y proveedores',
  'estructura:ver-todo': 'Ver todos los contratos',
}

export const puede = (sesion: Sesion | null, permiso: Permiso) =>
  sesion?.permisos.includes(permiso) ?? false

export const puedeAlguno = (sesion: Sesion | null, ...permisos: Permiso[]) =>
  permisos.some((permiso) => puede(sesion, permiso))

/** La sesión actual, o `null` si no hay. Se usa como guardia de las rutas privadas. */
export const consultaSesion = queryOptions({
  queryKey: ['sesion'],
  queryFn: async (): Promise<Sesion | null> => {
    const resultado = await api.GET('/api/v1/auth/yo')
    if (resultado.response.status === 401) return null
    return exigir(resultado)
  },
  staleTime: 5 * 60 * 1000,
})

export function useSesion() {
  return useSuspenseQuery(consultaSesion).data
}

export function useIniciarSesion() {
  const cliente = useQueryClient()
  return useMutation({
    mutationFn: async (datos: { usuario: string; contrasena: string }) =>
      exigir(await api.POST('/api/v1/auth/login', { body: datos })),
    onSuccess: (sesion) => cliente.setQueryData(consultaSesion.queryKey, sesion),
  })
}

export function useCerrarSesion() {
  const cliente = useQueryClient()
  return useMutation({
    mutationFn: cerrarSesionEnServidor,
    onSettled: () => {
      cliente.setQueryData(consultaSesion.queryKey, null)
      cliente.removeQueries({ predicate: (q) => q.queryKey[0] !== 'sesion' })
    },
  })
}

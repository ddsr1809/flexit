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
  valeAutorizar: 'vale:autorizar',
  pedidoCrear: 'pedido:crear',
  existenciasVer: 'existencias:ver',
  precioVer: 'precio:ver',
  costoVerPropio: 'costo:ver-propio',
  costoVerTodo: 'costo:ver-todo',
  administrar: 'sistema:administrar',
  usuariosAdministrar: 'usuarios:administrar',
  personasAdministrar: 'personas:administrar',
  catalogoEditar: 'catalogo:editar',
  estructuraVerTodo: 'estructura:ver-todo',
  presupuestoEditar: 'presupuesto:editar',
  incidenteAtender: 'incidente:atender',
} as const

export type Permiso = (typeof PERMISOS)[keyof typeof PERMISOS]

/** Cómo se le describe cada permiso a la persona. */
export const DESCRIPCION_PERMISO: Record<string, string> = {
  'movimiento:capturar': 'Capturar recepciones, vales, devoluciones y préstamos',
  'requisicion:crear': 'Crear requisiciones',
  'documento:autorizar': 'Autorizar traspasos, mermas, reasignaciones y ajustes de costo',
  'vale:autorizar': 'Autorizar vales de salida',
  'pedido:crear': 'Crear pedidos a proveedor',
  'existencias:ver': 'Consultar existencias',
  'precio:ver': 'Ver precios de compra',
  'costo:ver-propio': 'Ver el costo de sus contratos',
  'costo:ver-todo': 'Ver el costo de todos los contratos',
  'sistema:administrar': 'Administrar contratos, almacenes, conteos y cierre de mes',
  'usuarios:administrar': 'Dar de alta usuarios y roles',
  'personas:administrar': 'Ver y corregir personas (datos personales)',
  'catalogo:editar': 'Dar de alta materiales y proveedores',
  'estructura:ver-todo': 'Ver todos los contratos',
  'presupuesto:editar': 'Asignar el presupuesto de material de los lotes',
  'incidente:atender': 'Atender los incidentes del piloto',
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

/** Qué falta después de la contraseña: nada, el código de la app o darla de alta. */
export type PasoSegundoFactor = 'codigo' | 'alta'

export type ResultadoLogin =
  { tipo: 'sesion'; sesion: Sesion } | { tipo: 'segundo-factor'; paso: PasoSegundoFactor }

export type AltaSegundoFactor = components['schemas']['AltaSegundoFactorDto']

/**
 * Contraseña. El servidor responde 200 con la sesión, o 202 si falta el segundo factor
 * (administración y dirección): en ese caso todavía no hay sesión.
 */
export function useIniciarSesion() {
  const cliente = useQueryClient()
  return useMutation({
    mutationFn: async (datos: { usuario: string; contrasena: string }): Promise<ResultadoLogin> => {
      const resultado = await api.POST('/api/v1/auth/login', { body: datos })
      const cuerpo = exigir(resultado)
      if (resultado.response.status === 202 && cuerpo && 'paso' in cuerpo) {
        return { tipo: 'segundo-factor', paso: cuerpo.paso as PasoSegundoFactor }
      }
      return { tipo: 'sesion', sesion: cuerpo as Sesion }
    },
    onSuccess: (resultado) => {
      if (resultado.tipo === 'sesion') {
        cliente.setQueryData(consultaSesion.queryKey, resultado.sesion)
      }
    },
  })
}

/** Código de la app del teléfono (paso 'codigo'). */
export function useVerificarSegundoFactor() {
  const cliente = useQueryClient()
  return useMutation({
    mutationFn: async (codigo: string) =>
      exigir(await api.POST('/api/v1/auth/segundo-factor', { body: { codigo } })),
    onSuccess: (sesion) => cliente.setQueryData(consultaSesion.queryKey, sesion),
  })
}

/** Secreto nuevo para dar de alta el segundo factor (paso 'alta'). */
export function useAltaSegundoFactor() {
  return useMutation({
    mutationFn: async () => exigir(await api.POST('/api/v1/auth/segundo-factor/alta')),
  })
}

/** Primer código: confirma el alta e inicia la sesión. */
export function useConfirmarAltaSegundoFactor() {
  const cliente = useQueryClient()
  return useMutation({
    mutationFn: async (codigo: string) =>
      exigir(await api.POST('/api/v1/auth/segundo-factor/alta/confirmar', { body: { codigo } })),
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

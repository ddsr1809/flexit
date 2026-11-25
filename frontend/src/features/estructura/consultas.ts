import { queryOptions } from '@tanstack/react-query'
import { api } from '@/api/cliente'
import { exigir } from '@/api/errores'
import type { components } from '@/api/esquema'
import { formatearFecha } from '@/lib/formato'

export type Cliente = components['schemas']['Cliente']
export type Frente = components['schemas']['Frente']
export type Prototipo = components['schemas']['Prototipo']
export type TipoUnidad = components['schemas']['TipoUnidad']
export type Contrato = components['schemas']['Contrato']
export type UnidadObra = components['schemas']['UnidadObra']
export type PrecioPaquete = components['schemas']['PrecioPaquete']
export type Paquete = components['schemas']['Paquete']
export type Equivalencia = components['schemas']['Equivalencia']
export type UsuarioResumen = components['schemas']['UsuarioResumen']

/** Encabezado de concurrencia optimista: la versión que se leyó. */
export const siCoincide = (version: number) => ({ 'If-Match': String(version) })

export const consultas = {
  clientes: queryOptions({
    queryKey: ['clientes'],
    queryFn: async () => exigir(await api.GET('/api/v1/clientes')),
  }),
  frentes: queryOptions({
    queryKey: ['frentes'],
    queryFn: async () => exigir(await api.GET('/api/v1/frentes')),
  }),
  prototipos: queryOptions({
    queryKey: ['prototipos'],
    queryFn: async () => exigir(await api.GET('/api/v1/prototipos')),
  }),
  tiposUnidad: queryOptions({
    queryKey: ['tipos-unidad'],
    queryFn: async () => exigir(await api.GET('/api/v1/tipos-unidad')),
    staleTime: Infinity,
  }),
  contratos: (estado?: string) =>
    queryOptions({
      queryKey: ['contratos', { estado }],
      queryFn: async () =>
        exigir(await api.GET('/api/v1/contratos', { params: { query: { estado } } })),
    }),
  contrato: (id: number) =>
    queryOptions({
      queryKey: ['contratos', id],
      queryFn: async () =>
        exigir(await api.GET('/api/v1/contratos/{id}', { params: { path: { id } } })),
    }),
  unidades: (contrato: number) =>
    queryOptions({
      queryKey: ['contratos', contrato, 'unidades'],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/contratos/{id}/unidades', { params: { path: { id: contrato } } }),
        ),
    }),
  residentes: (contrato: number) =>
    queryOptions({
      queryKey: ['contratos', contrato, 'residentes'],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/contratos/{id}/residentes', {
            params: { path: { id: contrato } },
          }),
        ),
    }),
  candidatosResidente: queryOptions({
    queryKey: ['residentes'],
    queryFn: async () => exigir(await api.GET('/api/v1/residentes')),
  }),
  precios: (contrato: number) =>
    queryOptions({
      queryKey: ['contratos', contrato, 'precios'],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/contratos/{id}/precios', { params: { path: { id: contrato } } }),
        ),
    }),
  paquetes: queryOptions({
    queryKey: ['paquetes'],
    queryFn: async () => exigir(await api.GET('/api/v1/paquetes')),
  }),
  equivalencias: queryOptions({
    queryKey: ['paquetes', 'equivalencias'],
    queryFn: async () => exigir(await api.GET('/api/v1/paquetes/equivalencias')),
  }),
}

/** Profundidad de cada paquete en su árbol (0 = sección), para sangrar la lista. */
export function profundidades(paquetes: Paquete[]): Map<number, number> {
  const porId = new Map(paquetes.map((p) => [p.id, p]))
  const resultado = new Map<number, number>()
  const nivel = (paquete: Paquete, visitados = new Set<number>()): number => {
    const conocido = resultado.get(paquete.id)
    if (conocido !== undefined) return conocido
    const padre = paquete.padreId == null ? undefined : porId.get(paquete.padreId)
    const valor =
      !padre || visitados.has(padre.id) ? 0 : nivel(padre, visitados.add(paquete.id)) + 1
    resultado.set(paquete.id, valor)
    return valor
  }
  paquetes.forEach((p) => nivel(p))
  return resultado
}

/** "12/07/2026 – 28/03/2027". Las fechas sin hora se leen al mediodía para no cambiar de día. */
export const vigencia = (c: Contrato) =>
  c.inicio || c.fin
    ? `${c.inicio ? formatearFecha(`${c.inicio}T12:00:00`) : '¿?'} – ${c.fin ? formatearFecha(`${c.fin}T12:00:00`) : '¿?'}`
    : '—'

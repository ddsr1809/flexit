import { queryOptions } from '@tanstack/react-query'
import { api } from '@/api/cliente'
import { exigir } from '@/api/errores'
import type { components, operations } from '@/api/esquema'

export type ResumenContrato = components['schemas']['ResumenContrato']
export type CostoContrato = components['schemas']['CostoContrato']
export type LoteCosto = components['schemas']['LoteCosto']
export type ReporteCosto = components['schemas']['ReporteCosto']
export type LotePresupuesto = components['schemas']['LotePresupuesto']
export type ValorInventario = components['schemas']['ValorInventario']
export type SurtidoRequisicion = components['schemas']['SurtidoRequisicion']
export type ConciliacionContrato = components['schemas']['ConciliacionContrato']

export const AGRUPACIONES: Record<Agrupacion, string> = {
  contrato: 'Contrato',
  frente: 'Frente',
  unidad: 'Lote',
  paquete: 'Paquete',
  clasificacion: 'Razón (clasificación)',
  material: 'Material',
  mes: 'Mes',
}

export type Agrupacion = NonNullable<
  NonNullable<operations['reporteCosto']['parameters']['query']>['agrupar']
>

export interface FiltroCosto {
  agrupar: Agrupacion
  contratoId?: number
  fuente?: 'historico' | 'sistema'
  desde?: string
  hasta?: string
}

/** La misma ruta con ?formato=xlsx: el navegador la descarga con la cookie de sesión. */
export function rutaExcel(ruta: string, parametros: Record<string, unknown> = {}): string {
  const busqueda = new URLSearchParams()
  for (const [clave, valor] of Object.entries(parametros)) {
    if (valor === undefined || valor === null || valor === '') continue
    if (Array.isArray(valor)) valor.forEach((v) => busqueda.append(clave, String(v)))
    else busqueda.append(clave, String(valor))
  }
  busqueda.set('formato', 'xlsx')
  return `${ruta}?${busqueda.toString()}`
}

export const consultasReportes = {
  contratos: queryOptions({
    queryKey: ['reportes', 'contratos'],
    queryFn: async () =>
      exigir(await api.GET('/api/v1/reportes/contratos')) as unknown as ResumenContrato[],
  }),
  costoPorLote: (contratoId: number) =>
    queryOptions({
      queryKey: ['reportes', 'contratos', contratoId, 'lotes'],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/reportes/contratos/{id}/lotes', {
            params: { path: { id: contratoId } },
          }),
        ) as unknown as CostoContrato,
    }),
  costo: (filtro: FiltroCosto) =>
    queryOptions({
      queryKey: ['reportes', 'costo', filtro],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/reportes/costo', { params: { query: filtro } }),
        ) as unknown as ReporteCosto,
    }),
  avisos: queryOptions({
    queryKey: ['reportes', 'presupuesto', 'avisos'],
    queryFn: async () =>
      exigir(
        await api.GET('/api/v1/reportes/presupuesto', { params: { query: { avisos: true } } }),
      ) as unknown as LotePresupuesto[],
  }),
  valorInventario: (almacenId?: number) =>
    queryOptions({
      queryKey: ['reportes', 'valor-inventario', { almacenId }],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/reportes/valor-inventario', { params: { query: { almacenId } } }),
        ) as unknown as ValorInventario,
    }),
  surtido: (contratoId?: number, desde?: string, hasta?: string) =>
    queryOptions({
      queryKey: ['reportes', 'requisicion-vs-surtido', { contratoId, desde, hasta }],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/reportes/requisicion-vs-surtido', {
            params: { query: { contratoId, desde, hasta } },
          }),
        ) as unknown as SurtidoRequisicion[],
    }),
  conciliacion: (contratoId: number) =>
    queryOptions({
      queryKey: ['conciliacion', 'contrato', contratoId],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/conciliacion/contratos/{id}', {
            params: { path: { id: contratoId } },
          }),
        ) as unknown as ConciliacionContrato,
    }),
}

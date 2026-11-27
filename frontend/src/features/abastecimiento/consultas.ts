import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import { api } from '@/api/cliente'
import { exigir } from '@/api/errores'
import type { components } from '@/api/esquema'

export type Requisicion = components['schemas']['Requisicion']
export type ResumenRequisicion = components['schemas']['ResumenRequisicion']
export type RenglonRequisicion = components['schemas']['RenglonRequisicion']
export type DatosRequisicion = components['schemas']['DatosRequisicion']
export type RenglonCaptura = components['schemas']['RenglonCaptura']
export type Pedido = components['schemas']['Pedido']
export type ResumenPedido = components['schemas']['ResumenPedido']
export type RenglonPedido = components['schemas']['RenglonPedido']
export type DatosPedido = components['schemas']['DatosPedido']
export type RenglonPedidoCaptura = components['schemas']['RenglonPedidoCaptura']
export type GrupoFaltantes = components['schemas']['GrupoFaltantes']
export type Faltante = components['schemas']['Faltante']
export type ResultadoRecepcion = components['schemas']['ResultadoRecepcion']

export const ESTADOS_REQUISICION: Record<string, string> = {
  borrador: 'Borrador',
  pendiente: 'Por autorizar',
  autorizada: 'Autorizada',
  cerrada: 'Cerrada',
  cancelada: 'Cancelada',
}

export const ESTADOS_PEDIDO: Record<string, string> = {
  borrador: 'Borrador',
  enviado: 'Enviado',
  recibido: 'Recibido',
  cerrado: 'Cerrado',
  cancelado: 'Cancelado',
}

export interface FiltroRequisiciones {
  estados?: string
  contratoId?: number
  folio?: string
  pagina: number
}

export interface FiltroPedidos {
  estados?: string
  proveedorId?: number
  almacenId?: number
  folio?: string
  pagina: number
}

export const TAMANO_PAGINA = 50

export const consultasAbastecimiento = {
  requisiciones: (filtro: FiltroRequisiciones) =>
    queryOptions({
      queryKey: ['requisiciones', 'lista', filtro],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/requisiciones', {
            params: { query: { ...filtro, tamano: TAMANO_PAGINA } },
          }),
        ),
      placeholderData: keepPreviousData,
    }),
  porAutorizar: queryOptions({
    queryKey: ['requisiciones', 'por-autorizar'],
    queryFn: async () => exigir(await api.GET('/api/v1/requisiciones/por-autorizar')),
  }),
  requisicion: (id: number) =>
    queryOptions({
      queryKey: ['requisiciones', id],
      queryFn: async () =>
        exigir(await api.GET('/api/v1/requisiciones/{id}', { params: { path: { id } } })),
    }),
  faltantes: (proveedorId?: number, almacenId?: number) =>
    queryOptions({
      queryKey: ['faltantes', { proveedorId, almacenId }],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/faltantes', { params: { query: { proveedorId, almacenId } } }),
        ),
    }),
  pedidos: (filtro: FiltroPedidos) =>
    queryOptions({
      queryKey: ['pedidos', 'lista', filtro],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/pedidos', {
            params: { query: { ...filtro, tamano: TAMANO_PAGINA } },
          }),
        ),
      placeholderData: keepPreviousData,
    }),
  pedido: (id: number) =>
    queryOptions({
      queryKey: ['pedidos', id],
      queryFn: async () =>
        exigir(await api.GET('/api/v1/pedidos/{id}', { params: { path: { id } } })),
    }),
}

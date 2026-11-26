import { queryOptions } from '@tanstack/react-query'
import { api } from '@/api/cliente'
import { exigir } from '@/api/errores'
import type { components } from '@/api/esquema'

export type HistoriaUnidad = components['schemas']['HistoriaUnidad']
export type SalidaHistorica = components['schemas']['SalidaHistorica']
export type PagoHistorico = components['schemas']['PagoHistorico']

export const consultasHistorico = {
  unidad: (id: number) =>
    queryOptions({
      queryKey: ['historico', 'unidad', id],
      queryFn: async () =>
        exigir(await api.GET('/api/v1/historico/unidades/{id}', { params: { path: { id } } })),
    }),
}

export const TIPOS_SALIDA: Record<string, string> = {
  salida: 'Vale',
  devolucion: 'Devolución',
  reasignacion: 'Reasignación',
}

/** "38, 39" o "—" si el pago no dice semana. */
export function semanas(pago: PagoHistorico): string {
  return pago.semanas.length > 0 ? pago.semanas.join(', ') : '—'
}

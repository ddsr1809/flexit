import { queryOptions } from '@tanstack/react-query'
import { api } from '@/api/cliente'
import { exigir } from '@/api/errores'
import type { components } from '@/api/esquema'

export type Incidente = components['schemas']['Incidente']
export type IncidenteResumen = components['schemas']['IncidenteResumen']
export type DatosIncidente = components['schemas']['DatosIncidente']
export type Severidad = Incidente['severidad']
export type EstadoIncidente = Incidente['estado']

/** Cómo se explica cada severidad a quien reporta (ADR-015). */
export const SEVERIDADES: { clave: Severidad; nombre: string; ayuda: string }[] = [
  {
    clave: 'critica',
    nombre: 'Crítica',
    ayuda:
      'No se pueden registrar entradas o salidas, o quedó mal una existencia, un costo o un permiso.',
  },
  {
    clave: 'mayor',
    nombre: 'Mayor',
    ayuda: 'Se puede seguir trabajando con un rodeo, o un dato importante se ve mal.',
  },
  { clave: 'menor', nombre: 'Menor', ayuda: 'Un detalle: un texto, un acomodo, algo que estorba.' },
]

export const nombreSeveridad = (s: Severidad) => SEVERIDADES.find((x) => x.clave === s)?.nombre ?? s

export const ESTADOS: Record<EstadoIncidente, string> = {
  abierto: 'Abierto',
  resuelto: 'Resuelto',
  descartado: 'Descartado',
}

export const TIPOS_NOTA: Record<string, string> = {
  nota: 'Nota',
  severidad: 'Severidad',
  issue: 'GitHub',
  resuelto: 'Resuelto',
  descartado: 'Descartado',
  reabierto: 'Reabierto',
}

export interface FiltroIncidentes {
  estado?: EstadoIncidente
  severidad?: Severidad
}

export const consultasSoporte = {
  incidentes: (filtro: FiltroIncidentes) =>
    queryOptions({
      queryKey: ['incidentes', filtro],
      queryFn: async () =>
        exigir(await api.GET('/api/v1/incidentes', { params: { query: filtro } })),
    }),
  incidente: (id: number) =>
    queryOptions({
      queryKey: ['incidente', id],
      queryFn: async () =>
        exigir(await api.GET('/api/v1/incidentes/{id}', { params: { path: { id } } })),
    }),
}

/** Lo que propone el formulario de reporte: la pantalla y, si hubo, la última petición fallida. */
export function propuesta(
  pantalla: string,
  falla: { peticion: string; ruta: string; estado: number } | undefined,
): Pick<DatosIncidente, 'pantalla' | 'peticion'> & { descripcion: string } {
  return {
    pantalla: pantalla.slice(0, 200),
    peticion: falla?.peticion,
    descripcion: falla
      ? `El sistema respondió ${falla.estado} en ${falla.ruta}.\n\nQué estaba haciendo: `
      : '',
  }
}

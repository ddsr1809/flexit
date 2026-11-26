import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import { api } from '@/api/cliente'
import { exigir } from '@/api/errores'
import type { components } from '@/api/esquema'

export type TipoCargaInfo = components['schemas']['TipoCargaInfo']
export type ResumenCarga = components['schemas']['ResumenCarga']
export type DetalleCarga = components['schemas']['DetalleCarga']
export type FilaCargada = components['schemas']['FilaCargada']
export type IncidenciaCarga = components['schemas']['IncidenciaCarga']
export type DecisionCarga = components['schemas']['DecisionCarga']
export type AliasCarga = components['schemas']['AliasCarga']
export type DestinoPosible = components['schemas']['DestinoPosible']
export type Persona = components['schemas']['Persona']

export type Opcion = 'ACEPTAR' | 'ELEGIR' | 'CREAR' | 'VALOR' | 'DESCARTAR'

export interface FiltroFilas {
  estado?: string
  regla?: string
  q?: string
  pendientes: boolean
  pagina: number
}

export const TAMANO_PAGINA = 50

/** Entidades a las que se liga un nombre del Excel, con cómo se le dice a la persona. */
export const ENTIDADES: Record<string, string> = {
  frente: 'Frente',
  contrato: 'Contrato',
  prototipo: 'Prototipo',
  unidad_medida: 'Unidad de medida',
  material: 'Material',
  proveedor: 'Proveedor',
  persona: 'Persona',
}

export const consultasMigracion = {
  orden: queryOptions({
    queryKey: ['importaciones', 'tipos'],
    queryFn: async () => exigir(await api.GET('/api/v1/importaciones/tipos')),
  }),
  cargas: queryOptions({
    queryKey: ['importaciones', 'lista'],
    queryFn: async () => exigir(await api.GET('/api/v1/importaciones')),
  }),
  carga: (id: number) =>
    queryOptions({
      queryKey: ['importaciones', id],
      queryFn: async () =>
        exigir(await api.GET('/api/v1/importaciones/{id}', { params: { path: { id } } })),
    }),
  filas: (id: number, filtro: FiltroFilas) =>
    queryOptions({
      queryKey: ['importaciones', id, 'filas', filtro],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/importaciones/{id}/filas', {
            params: { path: { id }, query: { ...filtro, tamano: TAMANO_PAGINA } },
          }),
        ),
      placeholderData: keepPreviousData,
    }),
  destinos: (entidad: string, q: string, frenteId?: number) =>
    queryOptions({
      queryKey: ['importaciones', 'destinos', { entidad, q, frenteId }],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/importaciones/destinos', {
            params: { query: { entidad, q: q || undefined, frenteId } },
          }),
        ),
      placeholderData: keepPreviousData,
    }),
  alias: (entidad?: string, q?: string) =>
    queryOptions({
      queryKey: ['alias', { entidad, q }],
      queryFn: async () =>
        exigir(await api.GET('/api/v1/alias', { params: { query: { entidad, q } } })),
      placeholderData: keepPreviousData,
    }),
  personas: (q?: string, soloRevisar = false) =>
    queryOptions({
      queryKey: ['personas', { q, soloRevisar }],
      queryFn: async () =>
        exigir(await api.GET('/api/v1/personas', { params: { query: { q, soloRevisar } } })),
      placeholderData: keepPreviousData,
    }),
}

/** Sube el Excel como multipart/form-data. */
export async function subirArchivo(tipo: string, archivo: File, clienteId?: number) {
  const datos = new FormData()
  datos.append('archivo', archivo, archivo.name)
  return exigir(
    await api.POST('/api/v1/importaciones', {
      params: { query: { tipo, clienteId } },
      body: datos as unknown as { archivo: string },
      bodySerializer: (cuerpo) => cuerpo as unknown as FormData,
    }),
  )
}

/** Cómo se le dice a la persona cada estado de renglón o de carga. */
export const ESTADOS: Record<string, string> = {
  valida: 'Válido',
  corregida: 'Corregido',
  cuarentena: 'En cuarentena',
  descartada: 'Descartado',
  validada: 'Validada',
  confirmada: 'Confirmada',
  revertida: 'Revertida',
}

export const ACCIONES: Record<string, string> = {
  crear: 'Se crea',
  existe: 'Ya existe',
  omitir: 'No hace nada',
}

export const OPCIONES: Record<Opcion, string> = {
  ACEPTAR: 'Aceptar sugerencia',
  ELEGIR: 'Elegir otro',
  CREAR: 'Dar de alta',
  VALOR: 'Capturar valor',
  DESCARTAR: 'Descartar',
}

/** Lo que se muestra de un renglón: sus valores capturados, sin los datos de contexto (_celdas). */
export function textoCrudo(crudo: Record<string, string>): string {
  return Object.entries(crudo)
    .filter(([clave]) => !clave.startsWith('_'))
    .map(([clave, valor]) => (/^[A-Z]{1,3}$/.test(clave) ? `${clave}: ${valor}` : valor))
    .join(' · ')
}

/** Dónde está el renglón en el Excel: "Salidas fila 12" o "Salidas J4,J5". */
export function origen(fila: FilaCargada): string {
  const celdas = fila.crudo['_celdas'] ?? fila.crudo['_celda']
  if (celdas) return `${fila.hoja} · ${celdas}`
  return fila.filaExcel ? `${fila.hoja} · fila ${fila.filaExcel}` : fila.hoja
}

/** El valor de la sugerencia como texto, si existe. */
export function sugerido(incidencia: IncidenciaCarga, campo: string): string | undefined {
  const valor = incidencia.sugerencia?.[campo]
  return valor === undefined || valor === null ? undefined : String(valor)
}

/** Candidatos que el sistema propone (apodos con varias personas posibles). */
export function candidatos(incidencia: IncidenciaCarga): DestinoPosible[] {
  const lista = incidencia.sugerencia?.['candidatos']
  if (!Array.isArray(lista)) return []
  return lista
    .map((c) => c as { id?: unknown; nombre?: unknown })
    .filter((c) => typeof c.id === 'number' && typeof c.nombre === 'string')
    .map((c) => ({ id: c.id as number, texto: c.nombre as string }))
}

/** Entidad a buscar cuando la persona elige "otro": la de la sugerencia, o contrato para un lote. */
export function entidadDe(incidencia: IncidenciaCarga): string | undefined {
  const entidad = sugerido(incidencia, 'entidad')
  if (entidad) return entidad
  if (incidencia.regla === 'LOTE_SIN_CONTRATO') return 'contrato'
  return undefined
}

/**
 * La misma decisión para varios renglones con la misma regla pendiente: así se aceptan sugerencias
 * en grupo o se elige el contrato de todos los lotes de una manzana.
 */
export function decisionesEnGrupo(
  filas: FilaCargada[],
  regla: string,
  opcion: Opcion,
  extra: { destinoId?: number; valor?: string; motivo?: string } = {},
): DecisionCarga[] {
  const decisiones: DecisionCarga[] = []
  for (const fila of filas) {
    const incidencia = fila.incidencias.find((i) => i.regla === regla && i.pendiente)
    if (!incidencia || !incidencia.opciones.includes(opcion)) continue
    if (
      opcion === 'ACEPTAR' &&
      !sugerido(incidencia, 'destinoId') &&
      !sugerido(incidencia, 'numero')
    )
      continue
    decisiones.push({ numero: fila.numero, regla, opcion, ...extra })
  }
  return decisiones
}

/** Lotes pendientes de contrato agrupados por frente y manzana, para decidir de una vez. */
export function lotesPorManzana(filas: FilaCargada[]) {
  const grupos = new Map<string, { frenteId?: number; manzana: string; filas: FilaCargada[] }>()
  for (const fila of filas) {
    const incidencia = fila.incidencias.find((i) => i.regla === 'LOTE_SIN_CONTRATO' && i.pendiente)
    if (!incidencia) continue
    const frenteId = Number(sugerido(incidencia, 'frenteId'))
    const manzana = sugerido(incidencia, 'manzana') ?? '?'
    const llave = `${frenteId}|${manzana}`
    const grupo = grupos.get(llave) ?? {
      frenteId: Number.isFinite(frenteId) ? frenteId : undefined,
      manzana,
      filas: [],
    }
    grupo.filas.push(fila)
    grupos.set(llave, grupo)
  }
  return [...grupos.values()]
}

import type { Apertura, LineaApertura } from './consultas'

export const ESTADO_APERTURA: Record<Apertura['estado'], string> = {
  abierta: 'Contando',
  en_firma: 'En firma',
  aplicada: 'Aplicada',
  cancelada: 'Cancelada',
}

export const ESTADO_PRESTAMO_APERTURA: Record<string, string> = {
  pendiente: 'Por resolver',
  listo: 'Listo',
  descartado: 'Descartado',
}

export const ORIGEN_COSTO: Record<string, string> = {
  capturado: 'Capturado',
  historico: 'Ponderado del Excel',
  referencia: 'Precio de referencia',
}

export type FiltroLineas = 'todas' | 'existencia' | 'sin-contar' | 'sin-costo' | 'diferencia'

export const FILTROS_LINEAS: { valor: FiltroLineas; texto: string }[] = [
  { valor: 'todas', texto: 'Todos los materiales' },
  { valor: 'existencia', texto: 'Con existencia' },
  { valor: 'sin-contar', texto: 'Sin contar' },
  { valor: 'sin-costo', texto: 'Sin costo' },
  { valor: 'diferencia', texto: 'Con diferencia contra el Excel' },
]

/** Los materiales que se ven con el filtro y el texto buscado (clave o palabras del nombre). */
export function filtrarLineas(
  lineas: LineaApertura[],
  filtro: FiltroLineas,
  texto = '',
): LineaApertura[] {
  const palabras = normalizar(texto).split(' ').filter(Boolean)
  return lineas.filter((l) => {
    const pasa =
      filtro === 'todas' ||
      (filtro === 'existencia' && l.cantidad > 0) ||
      (filtro === 'sin-contar' && l.contado == null) ||
      (filtro === 'sin-costo' && l.faltaCosto) ||
      (filtro === 'diferencia' && l.diferenciaExcel != null && l.diferenciaExcel !== 0)
    if (!pasa) return false
    if (palabras.length === 0) return true
    const nombre = normalizar(`${l.clave} ${l.material}`)
    return palabras.every((p) => nombre.includes(p))
  })
}

/** Quién firmó y qué falta, en una línea. */
export function textoFirmas(a: Apertura): string {
  const partes = [
    a.firmaCoordinacion ? `Coordinación: ${a.firmaCoordinacion}` : 'Falta la firma de coordinación',
    a.firmaAdministracion
      ? `Administración: ${a.firmaAdministracion}`
      : 'Falta la firma de administración',
  ]
  return partes.join(' · ')
}

/** Sin acentos, en minúsculas y con espacios simples. */
export function normalizar(texto: string): string {
  return texto.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/\s+/g, ' ').trim()
}

import { expandirLotes, numeroLote } from '@/lib/lotes'

/** Lote de un contrato: id y clave (M16-L34). */
export interface LoteContrato {
  id: number
  clave: string
  manzana?: string | null
  lote?: string | null
  activa: boolean
}

export type ResultadoReparto = { ids: number[]; claves: string[] } | { error: string }

/**
 * Los lotes a los que va un renglón del vale, a partir de la manzana y la lista que escribe el
 * almacenista ("1-18, 20"), con las mismas reglas que el alta de lotes. Cada lote tiene que existir
 * y estar activo en el contrato.
 */
export function lotesDeRenglon(
  lotes: LoteContrato[],
  manzana: string,
  expresion: string,
): ResultadoReparto {
  const mz = numeroLote(manzana)
  if (!mz) return { error: 'Escribe la manzana' }
  const resultado = expandirLotes(expresion)
  if ('error' in resultado) return resultado
  const porClave = new Map(lotes.map((l) => [l.clave, l]))
  const ids: number[] = []
  const claves: string[] = []
  const faltan: string[] = []
  for (const numero of resultado.lotes) {
    const clave = `M${mz}-L${numero}`
    const lote = porClave.get(clave)
    if (!lote || !lote.activa) faltan.push(clave)
    else {
      ids.push(lote.id)
      claves.push(clave)
    }
  }
  if (faltan.length > 0) {
    const muestra = faltan.slice(0, 5).join(', ')
    return {
      error: `${faltan.length === 1 ? 'No existe el lote' : 'No existen los lotes'} ${muestra}${faltan.length > 5 ? '…' : ''} en el contrato`,
    }
  }
  return { ids, claves }
}

/**
 * Vista previa del reparto con la regla del servidor: partes iguales redondeadas hacia abajo y el
 * residuo en la última. En unidades enteras (piezas, sacos) se reparte sin decimales.
 */
export function vistaReparto(total: number, partes: number, fraccionable: boolean): number[] {
  if (partes < 1 || !Number.isFinite(total)) return []
  const escala = fraccionable ? 10_000 : 1
  const totalEscalado = Math.round(total * escala)
  const base = Math.floor(totalEscalado / partes)
  const lista = Array.from({ length: partes }, () => base / escala)
  lista[partes - 1] = (totalEscalado - base * (partes - 1)) / escala
  return lista
}

/** Conversión del catálogo: 1 origen = factor × destino (de un material o general). */
export interface ConversionCatalogo {
  materialId?: number | null
  origenId: number
  destinoId: number
  factor: number
}

/**
 * Factor para pasar lo capturado a la unidad de control, con el mismo orden de búsqueda que
 * mx.constructora.catalogo.Conversiones: misma unidad, del material, general directa, general
 * inversa. Solo para la vista previa; el servidor convierte de nuevo.
 */
export function factorConversion(
  conversiones: ConversionCatalogo[],
  materialId: number,
  captura: number,
  control: number,
): number | undefined {
  if (captura === control) return 1
  const delMaterial = conversiones.find(
    (c) => c.materialId === materialId && c.origenId === captura && c.destinoId === control,
  )
  if (delMaterial) return delMaterial.factor
  const directa = conversiones.find(
    (c) => c.materialId == null && c.origenId === captura && c.destinoId === control,
  )
  if (directa) return directa.factor
  const inversa = conversiones.find(
    (c) => c.materialId == null && c.origenId === control && c.destinoId === captura,
  )
  return inversa ? 1 / inversa.factor : undefined
}

/**
 * Cuentas de abastecimiento que la pantalla hace antes de mandar al servidor (que vuelve a
 * validar todo): qué surtir de cada renglón, cómo armar un pedido con los faltantes y cuánto falta
 * por recibir en la unidad en que se pidió.
 */

const DECIMALES = 10_000
const redondear = (valor: number) => Math.round(valor * DECIMALES) / DECIMALES

/** Lo que importa de un renglón de requisición para sugerir el vale. */
export interface AvanceRenglon {
  id: number
  materialId: number
  porSurtir: number
  reservado: number
  enVale: number
  /** Libre en el almacén para cualquier vale (el mismo valor en cada renglón del material). */
  disponible: number
}

/**
 * Cuánto surtir de cada renglón: primero lo apartado para él que no está ya en un vale abierto, y
 * después lo libre del almacén, repartido en el orden de los renglones sin pasar lo que hay.
 */
export function sugerirSurtido(renglones: AvanceRenglon[]): Map<number, number> {
  const libre = new Map<number, number>()
  const sugerido = new Map<number, number>()
  for (const r of renglones) {
    if (!libre.has(r.materialId)) libre.set(r.materialId, Math.max(r.disponible, 0))
    const apartado = Math.min(Math.max(r.reservado - r.enVale, 0), Math.max(r.porSurtir, 0))
    const resta = libre.get(r.materialId)!
    const extra = Math.min(Math.max(r.porSurtir - apartado, 0), resta)
    libre.set(r.materialId, redondear(resta - extra))
    sugerido.set(r.id, redondear(apartado + extra))
  }
  return sugerido
}

/** Un renglón del pedido mientras se arma. */
export interface RenglonArmado {
  materialId: number
  material: string
  unidad: string
  cantidad: number
  precio?: number
  requisicionLineaIds: number[]
  /** Folios y lotes que cubre, para mostrarlos. */
  cubre: string[]
}

interface FaltanteElegible {
  requisicionLineaId: number
  materialId: number
  material: string
  unidadControl: string
  faltante: number
  precioReferencia?: number | null
  folio: string
  unidadObra: string
}

/**
 * Junta los faltantes elegidos por material: un renglón por material con la suma de lo que falta
 * y los renglones de requisición que cubre, en el orden en que llegaron (el más urgente primero).
 */
export function armarPedido(faltantes: FaltanteElegible[]): RenglonArmado[] {
  const porMaterial = new Map<number, RenglonArmado>()
  for (const f of faltantes) {
    let renglon = porMaterial.get(f.materialId)
    if (!renglon) {
      renglon = {
        materialId: f.materialId,
        material: f.material,
        unidad: f.unidadControl,
        cantidad: 0,
        precio: f.precioReferencia ?? undefined,
        requisicionLineaIds: [],
        cubre: [],
      }
      porMaterial.set(f.materialId, renglon)
    }
    renglon.cantidad = redondear(renglon.cantidad + f.faltante)
    renglon.requisicionLineaIds.push(f.requisicionLineaId)
    renglon.cubre.push(`${f.folio} ${f.unidadObra}`)
    if (renglon.precio === undefined && f.precioReferencia != null)
      renglon.precio = f.precioReferencia
  }
  return [...porMaterial.values()]
}

/**
 * Lo que falta por recibir de un renglón del pedido, en la unidad en que se pidió. El servidor lo
 * da en unidad de control; la recepción se captura en la unidad del pedido.
 */
export function pendienteEnCaptura(renglon: {
  cantidad: number
  cantidadCaptura: number
  pendiente: number
}): number {
  if (renglon.pendiente <= 0 || renglon.cantidad <= 0) return 0
  return redondear((renglon.pendiente * renglon.cantidadCaptura) / renglon.cantidad)
}

/** Clase de color para el semáforo de presupuesto. */
export const TONO_SEMAFORO: Record<string, string> = {
  verde: 'bg-exito/10 text-exito',
  amarillo: 'bg-acento/25 text-texto',
  rojo: 'bg-peligro/10 text-peligro',
  sin: 'bg-fondo text-tenue',
}

export const TEXTO_SEMAFORO: Record<string, string> = {
  verde: 'En presupuesto',
  amarillo: 'Aviso: 80% o más',
  rojo: 'Rebasado',
  sin: 'Sin presupuesto',
}

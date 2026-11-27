import { armarPedido, pendienteEnCaptura, sugerirSurtido } from './calculos'

describe('vale sugerido de una requisición', () => {
  it('surte primero lo apartado y después lo libre, sin pasar lo que hay', () => {
    const sugerido = sugerirSurtido([
      { id: 1, materialId: 9, porSurtir: 6, reservado: 6, enVale: 0, disponible: 1 },
      { id: 2, materialId: 9, porSurtir: 6, reservado: 4, enVale: 0, disponible: 1 },
      { id: 3, materialId: 7, porSurtir: 5, reservado: 0, enVale: 0, disponible: 3 },
    ])
    expect(sugerido.get(1)).toBe(6)
    expect(sugerido.get(2)).toBe(5) // 4 apartados + el único libre
    expect(sugerido.get(3)).toBe(3)
  })

  it('lo apartado que ya va en un vale abierto no se vuelve a sugerir', () => {
    const sugerido = sugerirSurtido([
      { id: 1, materialId: 9, porSurtir: 4, reservado: 6, enVale: 6, disponible: 0 },
    ])
    expect(sugerido.get(1)).toBe(0)
  })
})

describe('pedido armado con faltantes', () => {
  it('junta por material, suma lo que falta y conserva el orden de urgencia', () => {
    const renglones = armarPedido([
      falta(11, 1, 'Cemento', 2, 'REQ-1', 'M20-L30', 205),
      falta(12, 2, 'Pintura', 5, 'REQ-1', 'M20-L29'),
      falta(13, 1, 'Cemento', 3.5, 'REQ-2', 'M21-L1'),
    ])
    expect(renglones).toHaveLength(2)
    expect(renglones[0]).toMatchObject({
      materialId: 1,
      cantidad: 5.5,
      precio: 205,
      requisicionLineaIds: [11, 13],
      cubre: ['REQ-1 M20-L30', 'REQ-2 M21-L1'],
    })
    expect(renglones[1]!.precio).toBeUndefined()
  })
})

describe('pendiente por recibir', () => {
  it('se pasa a la unidad del pedido', () => {
    // 2 cubetas de 19 L: pidió 38 L, faltan 19 L = 1 cubeta.
    expect(pendienteEnCaptura({ cantidad: 38, cantidadCaptura: 2, pendiente: 19 })).toBe(1)
    expect(pendienteEnCaptura({ cantidad: 10, cantidadCaptura: 10, pendiente: 0 })).toBe(0)
  })
})

function falta(
  requisicionLineaId: number,
  materialId: number,
  material: string,
  faltante: number,
  folio: string,
  unidadObra: string,
  precioReferencia?: number,
) {
  return {
    requisicionLineaId,
    materialId,
    material,
    unidadControl: 'SAC',
    faltante,
    precioReferencia,
    folio,
    unidadObra,
  }
}

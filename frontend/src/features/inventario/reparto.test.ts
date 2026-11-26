import { factorConversion, lotesDeRenglon, vistaReparto, type LoteContrato } from './reparto'

const lotes: LoteContrato[] = Array.from({ length: 20 }, (_, i) => ({
  id: 100 + i,
  clave: `M16-L${i + 1}`,
  activa: i !== 19,
}))

describe('lotes de un renglón del vale', () => {
  it('expande rangos y listas a ids del contrato', () => {
    const r = lotesDeRenglon(lotes, '16', '1-3, 5')
    expect(r).toEqual({
      ids: [100, 101, 102, 104],
      claves: ['M16-L1', 'M16-L2', 'M16-L3', 'M16-L5'],
    })
  })

  it('avisa los lotes que no existen o están inactivos', () => {
    expect(lotesDeRenglon(lotes, '16', '19-21')).toEqual({
      error: 'No existen los lotes M16-L20, M16-L21 en el contrato',
    })
    expect(lotesDeRenglon(lotes, '', '1')).toEqual({ error: 'Escribe la manzana' })
  })
})

describe('vista previa del reparto', () => {
  it('un bote de 19 L entre 18 lotes suma exacto y el residuo va al último', () => {
    const partes = vistaReparto(19, 18, true)
    expect(partes).toHaveLength(18)
    expect(partes[0]).toBe(1.0555)
    expect(partes[17]).toBe(1.0565)
    expect(Math.round(partes.reduce((a, b) => a + b, 0) * 10_000) / 10_000).toBe(19)
  })

  it('en piezas reparte enteros', () => {
    expect(vistaReparto(10, 3, false)).toEqual([3, 3, 4])
  })
})

describe('conversión para la vista previa', () => {
  const conversiones = [
    { materialId: 7, origenId: 2, destinoId: 1, factor: 19 },
    { materialId: null, origenId: 3, destinoId: 4, factor: 1000 },
  ]
  it('usa la del material, la general o su inversa', () => {
    expect(factorConversion(conversiones, 7, 2, 1)).toBe(19)
    expect(factorConversion(conversiones, 8, 2, 1)).toBeUndefined()
    expect(factorConversion(conversiones, 8, 3, 4)).toBe(1000)
    expect(factorConversion(conversiones, 8, 4, 3)).toBe(0.001)
    expect(factorConversion(conversiones, 8, 5, 5)).toBe(1)
  })
})

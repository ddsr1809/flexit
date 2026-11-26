import { describe, expect, it } from 'vitest'
import { semanas, type PagoHistorico } from './consultas'

const pago = (lista: number[]): PagoHistorico => ({
  id: 1,
  anio: 2026,
  semanas: lista,
  parcial: 1,
  montoCompartido: lista.length > 1,
  sobreImporte: false,
  trabajadores: [],
  origen: 'Pagos Granada P-51 I8',
})

describe('pagos de destajo del histórico', () => {
  it('muestra las semanas de la celda o un guion si no dice', () => {
    expect(semanas(pago([39, 38]))).toBe('39, 38')
    expect(semanas(pago([]))).toBe('—')
  })
})

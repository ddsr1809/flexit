import { clavesDeLotes, expandirLotes } from './lotes'

describe('lotes', () => {
  it('expande rangos y listas sin repetir, igual que el backend', () => {
    expect(expandirLotes('29-31, 35, 40a')).toEqual({ lotes: ['29', '30', '31', '35', '40A'] })
    expect(expandirLotes('29 - 31 35;036 30')).toEqual({ lotes: ['29', '30', '31', '35', '36'] })
  })

  it('explica lo que no entiende', () => {
    expect(expandirLotes('31-29')).toEqual({ error: 'El rango 31-29 va al revés' })
    expect(expandirLotes('1-600')).toHaveProperty('error')
    expect(expandirLotes('abc')).toHaveProperty('error')
    expect(expandirLotes('  ')).toHaveProperty('error')
  })

  it('arma las claves con la manzana', () => {
    expect(clavesDeLotes('020', '29-30')).toEqual({ lotes: ['M20-L29', 'M20-L30'] })
    expect(clavesDeLotes('', '1')).toHaveProperty('error')
  })
})

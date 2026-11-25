import { id, numero, texto } from './valores'

describe('valores de formulario', () => {
  it('limpia texto vacío', () => {
    expect(texto('  hola ')).toBe('hola')
    expect(texto('   ')).toBeUndefined()
  })

  it('entiende montos con separador de miles y coma decimal', () => {
    expect(numero('4,882,033.11')).toBe(4882033.11)
    expect(numero('12,5')).toBe(12.5)
    expect(numero('')).toBeUndefined()
    expect(numero('abc')).toBeNaN()
  })

  it('convierte la selección a id', () => {
    expect(id('7')).toBe(7)
    expect(id('')).toBeUndefined()
  })
})

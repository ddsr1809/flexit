import { nombreSeveridad, propuesta } from './consultas'

describe('reportar un problema', () => {
  it('sin falla reciente propone solo la pantalla', () => {
    expect(propuesta('/existencias?almacen=3', undefined)).toEqual({
      pantalla: '/existencias?almacen=3',
      peticion: undefined,
      descripcion: '',
    })
  })

  it('con una falla reciente lleva el identificador de la petición y dónde falló', () => {
    const p = propuesta('/movimientos/12', {
      peticion: 'b7e0c2d4-1f3a',
      ruta: '/api/v1/movimientos/12/aplicar',
      estado: 500,
    })
    expect(p.peticion).toBe('b7e0c2d4-1f3a')
    expect(p.descripcion).toContain('respondió 500 en /api/v1/movimientos/12/aplicar')
  })

  it('la pantalla no pasa de 200 caracteres (lo que acepta el servidor)', () => {
    expect(propuesta('/x'.repeat(150), undefined).pantalla).toHaveLength(200)
  })

  it('las severidades se muestran con acento', () => {
    expect(nombreSeveridad('critica')).toBe('Crítica')
  })
})

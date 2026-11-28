import { filtrarLineas, normalizar, textoFirmas } from './apertura'
import type { Apertura, LineaApertura } from './consultas'

const linea = (cambios: Partial<LineaApertura>): LineaApertura => ({
  materialId: 1,
  clave: '001',
  material: 'Pintura vinílica blanca',
  unidad: 'L',
  contado: 12,
  prestado: 0,
  cantidad: 12,
  faltaCosto: false,
  ...cambios,
})

const lineas = [
  linea({}),
  linea({ materialId: 2, clave: '002', material: 'Rodillo', contado: undefined, cantidad: 0 }),
  linea({
    materialId: 3,
    clave: '003',
    material: 'Compresor grande',
    contado: 0,
    prestado: 1,
    cantidad: 1,
    faltaCosto: true,
    existenciaExcel: 0,
    diferenciaExcel: 1,
  }),
]

describe('apertura', () => {
  it('filtra por lo que falta y por diferencia contra el Excel', () => {
    expect(filtrarLineas(lineas, 'todas')).toHaveLength(3)
    expect(filtrarLineas(lineas, 'existencia').map((l) => l.materialId)).toEqual([1, 3])
    expect(filtrarLineas(lineas, 'sin-contar').map((l) => l.materialId)).toEqual([2])
    expect(filtrarLineas(lineas, 'sin-costo').map((l) => l.materialId)).toEqual([3])
    expect(filtrarLineas(lineas, 'diferencia').map((l) => l.materialId)).toEqual([3])
  })

  it('busca por clave o por palabras del nombre, sin acentos', () => {
    expect(filtrarLineas(lineas, 'todas', 'vinilica').map((l) => l.materialId)).toEqual([1])
    expect(filtrarLineas(lineas, 'todas', '  GRANDE compresor ').map((l) => l.materialId)).toEqual([
      3,
    ])
    expect(filtrarLineas(lineas, 'todas', '002').map((l) => l.materialId)).toEqual([2])
    expect(normalizar('  Árbol   Ñandú ')).toBe('arbol nandu')
  })

  it('dice qué firma falta', () => {
    const base = { firmaCoordinacion: 'Ana' } as Apertura
    expect(textoFirmas(base)).toBe('Coordinación: Ana · Falta la firma de administración')
    expect(textoFirmas({ ...base, firmaAdministracion: 'Luis' })).toBe(
      'Coordinación: Ana · Administración: Luis',
    )
  })
})

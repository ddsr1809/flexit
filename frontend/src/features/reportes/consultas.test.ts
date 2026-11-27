import { rutaExcel } from './consultas'

describe('descarga en Excel', () => {
  it('agrega formato=xlsx y omite los filtros vacíos', () => {
    expect(
      rutaExcel('/api/v1/reportes/costo', { agrupar: 'paquete', contratoId: 4, fuente: '' }),
    ).toBe('/api/v1/reportes/costo?agrupar=paquete&contratoId=4&formato=xlsx')
  })

  it('repite los parámetros de lista', () => {
    expect(rutaExcel('/api/v1/reportes/presupuesto', { unidadIds: [1, 2] })).toBe(
      '/api/v1/reportes/presupuesto?unidadIds=1&unidadIds=2&formato=xlsx',
    )
  })
})

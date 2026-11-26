import {
  candidatos,
  decisionesEnGrupo,
  entidadDe,
  lotesPorManzana,
  origen,
  textoCrudo,
  type FilaCargada,
  type IncidenciaCarga,
} from './consultas'

const incidencia = (cambios: Partial<IncidenciaCarga>): IncidenciaCarga => ({
  regla: 'FRENTE_POR_CONFIRMAR',
  severidad: 'cuarentena',
  detalle: '',
  opciones: ['ACEPTAR', 'ELEGIR', 'DESCARTAR'],
  pendiente: true,
  ...cambios,
})

const fila = (numero: number, incidencias: IncidenciaCarga[], crudo = {}): FilaCargada => ({
  numero,
  hoja: 'Pagos Corceles P-65',
  filaExcel: 6,
  crudo,
  normal: {},
  estado: 'cuarentena',
  accion: 'omitir',
  incidencias,
})

describe('centro de migración', () => {
  it('muestra lo capturado sin los datos de contexto', () => {
    expect(textoCrudo({ B: '60-KBU-MEK/C001/2026', C: 'QUINTA GRANADA', _celdas: 'B4' })).toBe(
      'B: 60-KBU-MEK/C001/2026 · C: QUINTA GRANADA',
    )
    expect(textoCrudo({ texto: 'Lija 221', _apariciones: '2' })).toBe('Lija 221')
  })

  it('dice de qué hoja y celdas salió el renglón', () => {
    expect(origen(fila(1, [], { lote: 'M20L10', _celda: 'I6' }))).toBe('Pagos Corceles P-65 · I6')
    expect(origen(fila(1, []))).toBe('Pagos Corceles P-65 · fila 6')
  })

  it('acepta en grupo solo lo que tiene sugerencia', () => {
    const filas = [
      fila(1, [incidencia({ sugerencia: { destinoId: 7, entidad: 'frente', texto: 'X' } })]),
      fila(2, [incidencia({ sugerencia: { entidad: 'frente', texto: 'Y' } })]),
      fila(3, [incidencia({ pendiente: false, sugerencia: { destinoId: 7 } })]),
    ]
    expect(decisionesEnGrupo(filas, 'FRENTE_POR_CONFIRMAR', 'ACEPTAR')).toEqual([
      { numero: 1, regla: 'FRENTE_POR_CONFIRMAR', opcion: 'ACEPTAR' },
    ])
    expect(
      decisionesEnGrupo(filas, 'FRENTE_POR_CONFIRMAR', 'DESCARTAR', { motivo: 'no aplica' }),
    ).toHaveLength(2)
  })

  it('agrupa los lotes sin contrato por manzana', () => {
    const lote = (n: number, manzana: string) =>
      fila(n, [
        incidencia({
          regla: 'LOTE_SIN_CONTRATO',
          opciones: ['ELEGIR', 'DESCARTAR'],
          sugerencia: { frenteId: 3, manzana },
        }),
      ])
    const grupos = lotesPorManzana([lote(1, '20'), lote(2, '20'), lote(3, '21')])
    expect(grupos).toHaveLength(2)
    expect(grupos[0]!).toMatchObject({ frenteId: 3, manzana: '20' })
    expect(grupos[0]!.filas.map((f) => f.numero)).toEqual([1, 2])
  })

  it('sabe qué buscar al elegir otro registro', () => {
    expect(entidadDe(incidencia({ sugerencia: { entidad: 'persona' } }))).toBe('persona')
    expect(entidadDe(incidencia({ regla: 'LOTE_SIN_CONTRATO' }))).toBe('contrato')
    expect(
      candidatos(
        incidencia({ sugerencia: { candidatos: [{ id: 4, nombre: 'Ana' }, { id: 'x' }] } }),
      ),
    ).toEqual([{ id: 4, texto: 'Ana' }])
  })
})

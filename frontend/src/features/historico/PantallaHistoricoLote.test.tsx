import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import type { HistoriaUnidad } from './consultas'
import { PantallaHistoricoLote } from './PantallaHistoricoLote'

const historia: HistoriaUnidad = {
  unidadId: 7,
  clave: 'M16-L34',
  contratoId: 3,
  contrato: '60-BBB-MEO-C001-2026',
  salidas: [
    {
      id: 1,
      fecha: '2026-06-22',
      tipo: 'salida',
      folio: '3042',
      material: '151 · Silletas 7.5 p/malla (tambor)',
      unidad: 'PZA',
      cantidad: 100,
      clasificacion: 'Programa',
      solicitante: 'Misael Prueba',
      entregado: true,
      costoUnitario: 4.84296,
      importe: 484.296,
      costoEstimado: false,
      origen: 'Salidas fila 4',
    },
    {
      id: 2,
      fecha: '2026-06-23',
      tipo: 'devolucion',
      material: '002 · Cemento gris 50 kg',
      unidad: 'SAC',
      cantidad: -1,
      entregado: false,
      costoEstimado: true,
      importe: -205,
      origen: 'Salidas fila 9',
    },
  ],
  pagos: [
    {
      id: 1,
      anio: 2026,
      semanas: [39, 38],
      concepto: 'CIMENTACION',
      monto: 1400,
      parcial: 2,
      montoCompartido: true,
      sobreImporte: true,
      trabajadores: ['MEDINA VALLADO HERIBERTO'],
      origen: 'MDZ Palermo P-45 I8',
    },
  ],
  altas: [
    {
      mes: '2026-10-01',
      origen: 'mes',
      persona: 'GARCIA SANCHEZ FRANCISCO',
      estatus: 'ALTA',
      dias: 31,
    },
  ],
  costoMaterial: 279.296,
  destajo: 1400,
  veCostos: true,
  vePersonas: true,
}

vi.mock('./consultas', async (original) => {
  const real = await original<typeof import('./consultas')>()
  return {
    ...real,
    consultasHistorico: {
      unidad: (id: number) => ({
        queryKey: ['historico', 'unidad', id],
        queryFn: async () => datos.actual,
      }),
    },
  }
})

const datos: { actual: HistoriaUnidad } = { actual: historia }

function montar() {
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const raiz = createRootRoute({ component: () => <PantallaHistoricoLote unidadId={7} /> })
  const router = createRouter({ routeTree: raiz, history: createMemoryHistory() })
  render(
    <QueryClientProvider client={cliente}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

describe('historia del lote en el Excel', () => {
  it('muestra vales, pagos y altas con sus banderas', async () => {
    datos.actual = historia
    montar()
    expect(await screen.findByRole('heading', { name: /Lote M16-L34/ })).toBeInTheDocument()
    expect(screen.getByText('151 · Silletas 7.5 p/malla (tambor)')).toBeInTheDocument()
    expect(screen.getByText(/Devolución/)).toBeInTheDocument()
    expect(screen.getByText('No entregado')).toBeInTheDocument()
    expect(screen.getByText('estimado')).toBeInTheDocument()
    expect(screen.getByText('39, 38')).toBeInTheDocument()
    expect(screen.getByText('Sobre importe')).toBeInTheDocument()
    expect(screen.getByText('parcial 2')).toBeInTheDocument()
    expect(screen.getByText('GARCIA SANCHEZ FRANCISCO')).toBeInTheDocument()
    expect(screen.getByText('Destajo pagado')).toBeInTheDocument()
  })

  it('sin permisos no muestra importes, trabajadores ni altas', async () => {
    datos.actual = {
      ...historia,
      veCostos: false,
      vePersonas: false,
      altas: [],
      salidas: historia.salidas.map((s) => ({ ...s, importe: undefined, solicitante: undefined })),
      pagos: historia.pagos.map((p) => ({ ...p, monto: undefined, trabajadores: [] })),
    }
    montar()
    expect(await screen.findByRole('heading', { name: /Lote M16-L34/ })).toBeInTheDocument()
    expect(screen.queryByText('Destajo pagado')).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Trabajador' })).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Importe' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Dados de alta en el IMSS/)).not.toBeInTheDocument()
  })
})

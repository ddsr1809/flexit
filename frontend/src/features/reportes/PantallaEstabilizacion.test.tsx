import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import type { Estabilizacion } from './consultas'
import { Reporte } from './PantallaEstabilizacion'

const reporte = (parcial: Partial<Estabilizacion['criterio']>): Estabilizacion => ({
  almacenId: 3,
  almacen: 'PIL',
  apertura: '2027-03-15',
  desde: '2027-03-15',
  hasta: '2027-04-02',
  toleranciaDias: 1,
  criterio: {
    racha: 14,
    diasRequeridos: 14,
    valesEnRacha: 212,
    dosSemanasATiempo: true,
    criticosAbiertos: 0,
    sinCriticos: true,
    primerCierre: true,
    ultimoMesCerrado: '2027-03',
    cumple: true,
    ...parcial,
  },
  indicadores: {
    documentos: 260,
    salidas: 230,
    entradas: 18,
    tardios: 2,
    porcentajeTardios: 0.8,
    cancelados: 3,
    porcentajeCancelados: 1.2,
    diasSinCaptura: 3,
    pendientes: 1,
    pendientesViejos: 0,
    medianaHorasVale: 1.5,
    prestamosAbiertos: 17,
    prestamosVencidos: 2,
    conteosCerrados: 0,
    ajustesDeConteo: 0,
    incidentesReportados: 6,
    incidentesResueltos: 5,
    mayoresAbiertos: 1,
    menoresAbiertos: 0,
    medianaHorasResolucion: 4,
  },
  dias: [
    { fecha: '2027-03-15', documentos: 1, salidas: 0, entradas: 0, tardios: 0, cancelados: 0 },
    { fecha: '2027-03-16', documentos: 20, salidas: 18, entradas: 1, tardios: 2, cancelados: 0 },
  ],
  tardios: [
    {
      folio: 'SAL-2027-000031',
      tipo: 'salida',
      estado: 'aplicado',
      fecha: '2027-03-16',
      capturado: '2027-03-19',
      dias: 3,
    },
  ],
  pendientes: [],
  criticos: [],
})

/** El texto completo de un punto del criterio, con su "cumple" o "no cumple" para lector. */
const punto = (texto: string) =>
  screen.getByText((_, el) => el?.tagName === 'P' && el.textContent === texto)

function montar(r: Estabilizacion) {
  const cliente = new QueryClient()
  const raiz = createRootRoute({ component: () => <Reporte r={r} /> })
  const router = createRouter({ routeTree: raiz, history: createMemoryHistory() })
  render(
    <QueryClientProvider client={cliente}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

describe('estabilización del piloto', () => {
  it('con los tres puntos en verde, el criterio de H9 se cumple', async () => {
    montar(reporte({}))
    expect(
      await screen.findByRole('heading', { name: 'Criterio de H9: se cumple' }),
    ).toBeInTheDocument()
    expect(punto('Dos semanas capturando a tiempo: cumple')).toBeInTheDocument()
    expect(screen.getByText('SAL-2027-000031')).toBeInTheDocument()
    expect(screen.getByText('No hay documentos pendientes.')).toBeInTheDocument()
  })

  it('un incidente crítico abierto detiene el criterio y se lista', async () => {
    const r = reporte({ criticosAbiertos: 1, sinCriticos: false, cumple: false })
    r.criticos = [
      {
        id: 7,
        folio: 'INC-2027-000007',
        titulo: 'No deja aplicar',
        reportadoEn: '2027-04-01T09:00:00-06:00',
      },
    ]
    montar(r)
    expect(
      await screen.findByRole('heading', { name: 'Criterio de H9: todavía no' }),
    ).toBeInTheDocument()
    expect(punto('Cero incidentes críticos abiertos: no cumple')).toBeInTheDocument()
    expect(screen.getByText(/INC-2027-000007/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ir a Incidentes' })).toBeInTheDocument()
  })
})

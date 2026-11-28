import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Sesion } from '@/sesion/sesion'
import type { Incidente, IncidenteResumen } from './consultas'
import { PantallaIncidentes } from './PantallaIncidentes'

const resumen: IncidenteResumen = {
  id: 7,
  folio: 'INC-2027-000007',
  severidad: 'critica',
  estado: 'abierto',
  titulo: 'No deja aplicar el vale',
  almacenId: 3,
  almacen: 'PIL',
  reporto: 'Almacenista Uno',
  reportadoEn: '2027-03-16T09:10:00-06:00',
  reaperturas: 0,
}

const detalle = (parcial: Partial<Incidente>): Incidente => ({
  ...resumen,
  descripcion: 'Al aplicar sale un error',
  pantalla: '/movimientos/12',
  peticion: 'b7e0c2d4-1f3a',
  documento: 'SAL-2027-000012',
  puedeAtender: true,
  notas: [
    {
      id: 1,
      tipo: 'nota',
      texto: 'Se revisó en el almacén',
      usuario: 'Administración',
      creado: '2027-03-16T09:30:00-06:00',
    },
  ],
  version: 0,
  ...parcial,
})

let sesion: Sesion
let incidente: Incidente

vi.mock('@/sesion/sesion', async (original) => {
  const real = await original<typeof import('@/sesion/sesion')>()
  return { ...real, useSesion: () => sesion }
})

vi.mock('./consultas', async (original) => {
  const real = await original<typeof import('./consultas')>()
  return {
    ...real,
    consultasSoporte: {
      incidentes: () => ({ queryKey: ['incidentes'], queryFn: async () => [resumen] }),
      incidente: (id: number) => ({ queryKey: ['incidente', id], queryFn: async () => incidente }),
    },
  }
})

function montar(permisos: string[], datos: Incidente) {
  sesion = {
    usuarioId: 1,
    usuario: 'ana',
    nombre: 'Ana',
    empresaId: 1,
    empresa: 'RM Alemán',
    roles: [],
    permisos,
  }
  incidente = datos
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const raiz = createRootRoute({ component: () => <PantallaIncidentes /> })
  const router = createRouter({ routeTree: raiz, history: createMemoryHistory() })
  render(
    <QueryClientProvider client={cliente}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

describe('incidentes del piloto', () => {
  it('quien atiende ve el seguimiento y resuelve solo con lo que se hizo', async () => {
    montar(['incidente:atender'], detalle({}))
    await userEvent.click(await screen.findByRole('button', { name: 'INC-2027-000007' }))
    expect(await screen.findByText('Se revisó en el almacén')).toBeInTheDocument()
    expect(screen.getByText(/b7e0c2d4-1f3a/)).toBeInTheDocument()
    const resolver = screen.getByRole('button', { name: 'Resolver' })
    expect(resolver).toBeDisabled()
    await userEvent.type(screen.getByLabelText('Nota, resolución o motivo'), 'Corregido en 1.9.1')
    expect(resolver).toBeEnabled()
  })

  it('quien reportó no resuelve; si ya se resolvió y sigue pasando, lo reabre', async () => {
    montar(
      [],
      detalle({
        estado: 'resuelto',
        puedeAtender: false,
        cerro: 'Administración',
        cerradoEn: '2027-03-16T12:00:00-06:00',
        resolucion: 'Corregido en 1.9.1',
      }),
    )
    await userEvent.click(await screen.findByRole('button', { name: 'INC-2027-000007' }))
    expect(await screen.findByRole('button', { name: 'Reabrir' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Resolver' })).not.toBeInTheDocument()
  })
})

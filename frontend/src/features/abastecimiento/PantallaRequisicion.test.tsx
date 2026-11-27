import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { RenglonRequisicion, Requisicion } from './consultas'
import { PantallaRequisicion } from './PantallaRequisicion'

const renglon = (parcial: Partial<RenglonRequisicion>): RenglonRequisicion => ({
  id: 1,
  renglon: 1,
  materialId: 9,
  materialClave: 'CEM',
  material: 'Cemento gris 50 kg',
  unidadControl: 'SAC',
  cantidad: 6,
  cantidadCaptura: 6,
  unidadCapturaId: 3,
  unidadCaptura: 'SAC',
  unidadObraId: 29,
  unidadObra: 'M20-L29',
  paqueteId: 3,
  paquete: '3 Losa',
  clasificacionId: 1,
  clasificacion: 'Programa',
  surtido: 0,
  enVale: 0,
  reservado: 6,
  porRecibir: 0,
  porSurtir: 6,
  faltante: 0,
  disponible: 0,
  ...parcial,
})

const requisicion: Requisicion = {
  id: 5,
  folio: 'REQ-2026-000005',
  estado: 'autorizada',
  contratoId: 3,
  contrato: '60-H6-UNO',
  almacenId: 1,
  almacen: 'COR',
  fecha: '2026-11-25',
  fechaRequerida: '2026-11-28',
  urgente: true,
  capturo: 'Residente',
  autorizo: 'Coordinación',
  creado: '2026-11-25T10:00:00-06:00',
  avance: 0,
  version: 2,
  renglones: [
    renglon({}),
    renglon({
      id: 2,
      renglon: 2,
      unidadObraId: 30,
      unidadObra: 'M20-L30',
      reservado: 4,
      faltante: 2,
    }),
  ],
  vales: [],
  pedidos: [],
  acciones: {
    editar: false,
    enviar: false,
    autorizar: false,
    cancelar: false,
    cerrar: true,
    reservar: true,
    surtir: true,
  },
}

vi.mock('./consultas', async (original) => {
  const real = await original<typeof import('./consultas')>()
  return {
    ...real,
    consultasAbastecimiento: {
      ...real.consultasAbastecimiento,
      requisicion: (id: number) => ({
        queryKey: ['requisiciones', id],
        queryFn: async () => requisicion,
      }),
    },
  }
})

function montar() {
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const raiz = createRootRoute({ component: () => <PantallaRequisicion id={5} /> })
  const router = createRouter({ routeTree: raiz, history: createMemoryHistory() })
  render(
    <QueryClientProvider client={cliente}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

describe('detalle de requisición', () => {
  it('muestra lo apartado y lo que falta comprar por lote', async () => {
    montar()
    expect(await screen.findByRole('heading', { name: /REQ-2026-000005/ })).toBeInTheDocument()
    expect(screen.getByText('Urgente')).toBeInTheDocument()
    expect(screen.getByText('2 SAC')).toBeInTheDocument() // falta comprar en M20-L30
    expect(screen.getByRole('button', { name: /Surtir/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Autorizar/ })).not.toBeInTheDocument()
  })

  it('el vale sugerido surte lo apartado de cada renglón', async () => {
    montar()
    await userEvent.click(await screen.findByRole('button', { name: /Surtir/ }))
    const cantidades = screen.getAllByLabelText(/Cantidad \(SAC\)/)
    expect(cantidades.map((c) => (c as HTMLInputElement).value)).toEqual(['6', '4'])
  })
})

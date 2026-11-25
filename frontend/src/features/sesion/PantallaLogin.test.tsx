import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { PantallaLogin } from './PantallaLogin'

const { POST, GET } = vi.hoisted(() => ({ POST: vi.fn(), GET: vi.fn() }))
vi.mock('@/api/cliente', () => ({ api: { POST, GET }, cerrarSesionEnServidor: vi.fn() }))

function conConsultas(hijo: ReactNode) {
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={cliente}>{hijo}</QueryClientProvider>
}

beforeEach(() => {
  POST.mockReset()
  GET.mockResolvedValue({
    data: { version: 'abc1234def', entorno: 'pruebas' },
    response: new Response(),
  })
})

describe('pantalla de inicio de sesión', () => {
  it('pide usuario y contraseña antes de llamar al servidor', async () => {
    render(conConsultas(<PantallaLogin alEntrar={vi.fn()} />))
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }))
    expect(await screen.findByText('Escribe tu usuario')).toBeInTheDocument()
    expect(screen.getByText('Escribe tu contraseña')).toBeInTheDocument()
    expect(POST).not.toHaveBeenCalled()
  })

  it('muestra el motivo cuando el servidor rechaza', async () => {
    POST.mockResolvedValue({
      error: { codigo: 'CREDENCIALES_INVALIDAS' },
      response: new Response(null, { status: 401 }),
    })
    render(conConsultas(<PantallaLogin alEntrar={vi.fn()} />))
    await userEvent.type(screen.getByLabelText('Usuario'), 'ana')
    await userEvent.type(screen.getByLabelText('Contraseña'), 'mala')
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Usuario o contraseña incorrectos.')
  })

  it('entra con credenciales correctas', async () => {
    const alEntrar = vi.fn()
    POST.mockResolvedValue({
      data: {
        usuarioId: 1,
        usuario: 'ana',
        nombre: 'Ana',
        empresaId: 1,
        empresa: 'E',
        roles: [],
        permisos: [],
      },
      response: new Response(null, { status: 200 }),
    })
    render(conConsultas(<PantallaLogin alEntrar={alEntrar} />))
    await userEvent.type(screen.getByLabelText('Usuario'), ' ana ')
    await userEvent.type(screen.getByLabelText('Contraseña'), 'buena-contrasena')
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }))
    await vi.waitFor(() => expect(alEntrar).toHaveBeenCalled())
    expect(POST).toHaveBeenCalledWith('/api/v1/auth/login', {
      body: { usuario: 'ana', contrasena: 'buena-contrasena' },
    })
  })

  it('muestra la versión desplegada', async () => {
    render(conConsultas(<PantallaLogin alEntrar={vi.fn()} />))
    expect(await screen.findByText(/Versión abc1234 · pruebas/)).toBeInTheDocument()
  })
})

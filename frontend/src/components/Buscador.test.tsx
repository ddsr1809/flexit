import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Buscador } from './Buscador'

interface Material {
  id: number
  nombre: string
}

const materiales: Material[] = [
  { id: 1, nombre: 'Cemento gris 50 kg' },
  { id: 2, nombre: 'Cemento blanco' },
]

function montar(alElegir: (m: Material) => void) {
  const buscar = vi.fn(async (q: string) =>
    materiales.filter((m) => m.nombre.toLowerCase().includes(q.toLowerCase())),
  )
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={cliente}>
      <Buscador<Material>
        etiqueta="Material"
        nombre="materiales"
        buscar={buscar}
        texto={(m) => m.nombre}
        clave={(m) => m.id}
        alElegir={alElegir}
      />
    </QueryClientProvider>,
  )
  return buscar
}

describe('buscador con teclado', () => {
  it('Enter toma la primera sugerencia y las flechas cambian la marcada', async () => {
    const elegido = vi.fn()
    montar(elegido)
    const campo = screen.getByRole('combobox', { name: 'Material' })
    await userEvent.type(campo, 'cemento')
    expect(await screen.findAllByRole('option')).toHaveLength(2)
    await userEvent.keyboard('{ArrowDown}{Enter}')
    expect(elegido).toHaveBeenCalledWith(materiales[1])
  })

  it('no busca con menos de dos letras', async () => {
    const buscar = montar(vi.fn())
    await userEvent.type(screen.getByRole('combobox', { name: 'Material' }), 'c')
    await new Promise((r) => setTimeout(r, 300))
    expect(buscar).not.toHaveBeenCalled()
  })
})

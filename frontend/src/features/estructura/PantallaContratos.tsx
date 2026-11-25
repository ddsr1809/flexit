import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useState } from 'react'
import { mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Insignia } from '@/components/ui/insignia'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { PERMISOS, puede, useSesion } from '@/sesion/sesion'
import { consultas, vigencia } from './consultas'
import { FormularioContrato } from './FormularioContrato'

const FILTROS = [
  { valor: 'activo', texto: 'Activos' },
  { valor: 'terminado', texto: 'Terminados' },
  { valor: 'cancelado', texto: 'Cancelados' },
]

export const EstadoContrato = ({ estado }: { estado: string }) => (
  <Insignia tono={estado === 'activo' ? 'exito' : 'apagado'}>
    {estado.charAt(0).toUpperCase() + estado.slice(1)}
  </Insignia>
)

/** Contratos al alcance del usuario; administración da de alta desde aquí. */
export function PantallaContratos() {
  const sesion = useSesion()
  const navegar = useNavigate()
  const [estado, setEstado] = useState('activo')
  const contratos = useQuery(consultas.contratos(estado || undefined))
  const administra = puede(sesion, PERMISOS.administrar)
  const veTodo = puede(sesion, PERMISOS.estructuraVerTodo)

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">Contratos</h1>
          <p className="text-tenue">
            {veTodo
              ? 'Cada contrato es un centro de costo.'
              : 'Los contratos que tienes asignados.'}
          </p>
        </div>
        <div className="w-48">
          <Seleccion
            etiqueta="Mostrar"
            opciones={FILTROS}
            vacio="Todos"
            value={estado}
            onChange={(e) => setEstado(e.target.value)}
          />
        </div>
      </header>

      <Tarjeta className="p-0">
        {contratos.isError && (
          <div className="p-4">
            <Aviso>{mensajeDe(contratos.error)}</Aviso>
          </div>
        )}
        {contratos.isPending && <p className="p-4 text-sm text-tenue">Cargando…</p>}
        <Tabla
          filas={contratos.data}
          clave={(c) => c.id}
          vacio={
            veTodo
              ? 'No hay contratos con ese estado.'
              : 'No tienes contratos asignados. Pide a administración que te asigne.'
          }
          columnas={[
            {
              titulo: 'Código',
              celda: (c) => (
                <Link
                  to="/contratos/$contratoId"
                  params={{ contratoId: String(c.id) }}
                  className="font-mono font-medium text-marca underline-offset-2 hover:underline"
                >
                  {c.codigo}
                </Link>
              ),
            },
            { titulo: 'Cliente', celda: (c) => c.cliente },
            { titulo: 'Frente', celda: (c) => c.frente ?? '—' },
            {
              titulo: 'No. obra',
              celda: (c) => <span className="font-mono">{c.numeroObra ?? '—'}</span>,
            },
            { titulo: 'Vigencia', celda: vigencia, clase: 'whitespace-nowrap' },
            { titulo: 'Unidades', celda: (c) => c.unidades, clase: 'text-right' },
            { titulo: 'Estado', celda: (c) => <EstadoContrato estado={c.estado} /> },
          ]}
        />
      </Tarjeta>

      {administra && (
        <Tarjeta>
          <h2 className="text-lg font-medium">Nuevo contrato</h2>
          <p className="mt-1 text-sm text-tenue">
            Después de darlo de alta se agregan sus lotes, residentes y precios.
          </p>
          <div className="mt-4">
            <FormularioContrato
              alGuardar={(c) =>
                void navegar({ to: '/contratos/$contratoId', params: { contratoId: String(c.id) } })
              }
            />
          </div>
        </Tarjeta>
      )}
    </div>
  )
}

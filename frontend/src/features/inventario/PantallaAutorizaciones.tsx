import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check } from 'lucide-react'
import { useState } from 'react'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Tarjeta } from '@/components/ui/tarjeta'
import { consultasInventario, type ResultadoLote } from './consultas'
import { TablaMovimientos } from './PantallaMovimientos'

/**
 * Bandeja de autorizaciones: lo pendiente que el usuario puede autorizar (nunca lo que él mismo
 * capturó). El residente ve solo los vales de sus contratos. Se autoriza uno por uno desde el
 * documento o varios a la vez desde aquí.
 */
export function PantallaAutorizaciones() {
  const cliente = useQueryClient()
  const pendientes = useQuery(consultasInventario.porAutorizar)
  const [elegidos, setElegidos] = useState<Set<number>>(new Set())
  const [resultado, setResultado] = useState<ResultadoLote[]>()

  const autorizar = useMutation({
    mutationFn: async (ids: number[]) =>
      exigir(await api.POST('/api/v1/movimientos/autorizar', { body: { ids } })),
    onSuccess: (filas) => {
      setResultado(filas)
      setElegidos(new Set())
      void cliente.invalidateQueries({ queryKey: ['movimientos'] })
    },
  })

  const cambiar = (id: number, elegido: boolean) => {
    const nuevos = new Set(elegidos)
    if (elegido) nuevos.add(id)
    else nuevos.delete(id)
    setElegidos(nuevos)
  }
  const todos = pendientes.data ?? []
  const fallidos = resultado?.filter((r) => !r.ok) ?? []

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold">Autorizaciones</h1>
        <p className="text-tenue">
          Vales, traspasos, reasignaciones, mermas y ajustes de costo que esperan tu visto bueno.
        </p>
      </header>

      {resultado && (
        <Aviso tipo={fallidos.length === 0 ? 'exito' : 'error'}>
          {resultado.length - fallidos.length} autorizados.
          {fallidos.map((f) => (
            <span key={f.id} className="block">
              Documento {f.id}: {f.mensaje}
            </span>
          ))}
        </Aviso>
      )}
      {autorizar.isError && <Aviso>{mensajeDe(autorizar.error)}</Aviso>}
      {pendientes.isError && <Aviso>{mensajeDe(pendientes.error)}</Aviso>}

      <div className="flex flex-wrap items-center gap-3">
        <Boton
          variante="secundario"
          disabled={todos.length === 0}
          onClick={() =>
            setElegidos(
              elegidos.size === todos.length ? new Set() : new Set(todos.map((m) => m.id)),
            )
          }
        >
          {elegidos.size === todos.length && todos.length > 0 ? 'Quitar todos' : 'Elegir todos'}
        </Boton>
        <Boton
          disabled={elegidos.size === 0 || autorizar.isPending}
          onClick={() => autorizar.mutate([...elegidos])}
        >
          <Check className="size-4" /> Autorizar {elegidos.size > 0 ? elegidos.size : ''}
        </Boton>
      </div>

      <Tarjeta className="p-0">
        <TablaMovimientos filas={pendientes.data} seleccion={{ elegidos, cambiar }} />
      </Tarjeta>
    </div>
  )
}

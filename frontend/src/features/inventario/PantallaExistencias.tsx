import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { History } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { consultas } from '@/features/estructura/consultas'
import { useDiferido } from '@/lib/diferido'
import { formatearMoneda, formatearNumero } from '@/lib/formato'
import { PERMISOS, puede, puedeAlguno, useSesion } from '@/sesion/sesion'
import { consultasInventario, type Existencia } from './consultas'

/**
 * Existencias por almacén: lo que hay, lo prestado y lo disponible para vales. El costo promedio y
 * el valor solo los ve quien ve precios o costos. De cada material se llega a su kardex.
 */
export function PantallaExistencias() {
  const sesion = useSesion()
  const veCostos = puedeAlguno(sesion, PERMISOS.precioVer, PERMISOS.costoVerTodo)
  const almacenes = useQuery(consultasInventario.almacenes)
  const [elegido, setElegido] = useState('')
  const [busqueda, setBusqueda] = useState('')
  const [todos, setTodos] = useState(false)
  const q = useDiferido(busqueda.trim(), 250)
  const activos = (almacenes.data ?? []).filter((a) => a.activo)
  const almacenId = Number(elegido || activos[0]?.id || 0)
  const existencias = useQuery({
    ...consultasInventario.existencias(almacenId, q || undefined, !todos),
    enabled: almacenId > 0,
  })
  const valor = (existencias.data ?? []).reduce((suma, e) => suma + (e.valor ?? 0), 0)

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold">Existencias</h1>
        <p className="text-tenue">
          Lo prestado sigue en existencia pero no está disponible para vales.
        </p>
      </header>

      {almacenes.data && almacenes.data.length === 0 ? (
        <Aviso tipo="exito">
          Todavía no hay almacenes.{' '}
          {puede(sesion, PERMISOS.administrar)
            ? 'Da de alta el almacén de cada frente abajo.'
            : 'Pide a administración que dé de alta el almacén de cada frente.'}
        </Aviso>
      ) : (
        <>
          <div className="grid gap-4 md:grid-cols-[1fr_2fr_auto] md:items-end">
            <Seleccion
              etiqueta="Almacén"
              opciones={activos.map((a) => ({ valor: a.id, texto: `${a.clave} · ${a.nombre}` }))}
              value={almacenId || ''}
              onChange={(e) => setElegido(e.target.value)}
            />
            <Campo
              etiqueta="Buscar material"
              type="search"
              placeholder="Clave o palabras del nombre"
              value={busqueda}
              onChange={(e) => setBusqueda(e.target.value)}
            />
            <label className="flex min-h-11 items-center gap-2 text-sm">
              <input
                type="checkbox"
                className="size-4"
                checked={todos}
                onChange={(e) => setTodos(e.target.checked)}
              />
              Incluir en cero
            </label>
          </div>

          <Tarjeta className="p-0">
            {existencias.isError && (
              <div className="p-4">
                <Aviso>{mensajeDe(existencias.error)}</Aviso>
              </div>
            )}
            <Tabla
              filas={existencias.data}
              clave={(e) => e.materialId}
              vacio={q ? `Ningún material coincide con "${q}".` : 'El almacén está vacío.'}
              columnas={[
                { titulo: 'Clave', celda: (e) => <span className="font-mono">{e.clave}</span> },
                { titulo: 'Material', celda: (e) => e.material },
                {
                  titulo: 'Existencia',
                  celda: (e) => `${formatearNumero(e.existencia)} ${e.unidad}`,
                  clase: 'text-right whitespace-nowrap',
                },
                {
                  titulo: 'Prestado',
                  celda: (e) => (e.prestado > 0 ? formatearNumero(e.prestado) : '—'),
                  clase: 'text-right',
                },
                {
                  titulo: 'Disponible',
                  celda: (e) => <strong>{formatearNumero(e.disponible)}</strong>,
                  clase: 'text-right',
                },
                ...(veCostos
                  ? [
                      {
                        titulo: 'Costo prom.',
                        celda: (e: Existencia) =>
                          e.costoPromedio == null ? '—' : formatearMoneda(e.costoPromedio),
                        clase: 'text-right whitespace-nowrap',
                      },
                      {
                        titulo: 'Valor',
                        celda: (e: Existencia) =>
                          e.valor == null ? '—' : formatearMoneda(e.valor),
                        clase: 'text-right whitespace-nowrap',
                      },
                    ]
                  : []),
              ]}
              acciones={(e) => (
                <Link
                  to="/kardex/$materialId"
                  params={{ materialId: String(e.materialId) }}
                  search={{ almacen: almacenId }}
                  className="inline-flex min-h-11 items-center gap-1 rounded-md px-3 text-sm text-tenue hover:bg-fondo hover:text-texto"
                  aria-label={`Kardex de ${e.material}`}
                >
                  <History className="size-4" /> Kardex
                </Link>
              )}
            />
            {veCostos && existencias.data && existencias.data.length > 0 && (
              <p className="border-t border-borde px-4 py-2 text-right text-sm text-tenue">
                Valor del almacén (sin IVA): <strong>{formatearMoneda(valor)}</strong>
              </p>
            )}
          </Tarjeta>
        </>
      )}

      {puede(sesion, PERMISOS.administrar) && <AltaAlmacen />}
    </div>
  )
}

/** Alta del almacén de un frente (administración). */
function AltaAlmacen() {
  const cliente = useQueryClient()
  const frentes = useQuery(consultas.frentes)
  const almacenes = useQuery(consultasInventario.almacenes)
  const [frente, setFrente] = useState('')
  const [clave, setClave] = useState('')
  const [nombre, setNombre] = useState('')
  const conAlmacen = new Set((almacenes.data ?? []).map((a) => a.frenteId))
  const libres = (frentes.data ?? []).filter((f) => f.activo && !conAlmacen.has(f.id))

  const alta = useMutation({
    mutationFn: async () =>
      exigir(
        await api.POST('/api/v1/almacenes', {
          body: { frenteId: Number(frente), clave, nombre },
        }),
      ),
    onSuccess: () => {
      setFrente('')
      setClave('')
      setNombre('')
      void cliente.invalidateQueries({ queryKey: ['almacenes'] })
    },
  })

  const enviar = (evento: FormEvent) => {
    evento.preventDefault()
    alta.mutate()
  }

  return (
    <Tarjeta>
      <form aria-label="Alta de almacén" onSubmit={enviar} className="flex flex-col gap-4">
        <h2 className="text-lg font-medium">Almacenes</h2>
        <ul className="flex flex-wrap gap-2 text-sm">
          {(almacenes.data ?? []).map((a) => (
            <li key={a.id} className="rounded-full bg-fondo px-3 py-1">
              <span className="font-mono">{a.clave}</span> · {a.frente}
              {!a.activo && ' (inactivo)'}
            </li>
          ))}
        </ul>
        {libres.length === 0 ? (
          <p className="text-sm text-tenue">Todos los frentes activos ya tienen almacén.</p>
        ) : (
          <div className="grid gap-4 md:grid-cols-[1fr_8rem_1fr_auto] md:items-end">
            <Seleccion
              etiqueta="Frente"
              vacio="Elige"
              opciones={libres.map((f) => ({ valor: f.id, texto: f.nombre }))}
              value={frente}
              onChange={(e) => {
                setFrente(e.target.value)
                const f = libres.find((x) => String(x.id) === e.target.value)
                if (f) {
                  setClave(f.clave.slice(0, 12))
                  setNombre(`Almacén ${f.nombre}`)
                }
              }}
            />
            <Campo etiqueta="Clave" value={clave} onChange={(e) => setClave(e.target.value)} />
            <Campo
              etiqueta="Nombre del almacén"
              value={nombre}
              onChange={(e) => setNombre(e.target.value)}
            />
            <Boton type="submit" disabled={!frente || !clave || !nombre || alta.isPending}>
              Dar de alta
            </Boton>
          </div>
        )}
        {alta.isError && <Aviso>{mensajeDe(alta.error)}</Aviso>}
      </form>
    </Tarjeta>
  )
}

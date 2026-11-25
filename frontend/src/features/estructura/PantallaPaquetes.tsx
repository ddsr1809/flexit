import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Pencil } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { api } from '@/api/cliente'
import { ErrorApi, exigir, mensajeDe } from '@/api/errores'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { EstadoActivo } from '@/components/ui/insignia'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { cn } from '@/lib/clases'
import { aTexto, id, texto } from '@/lib/valores'
import { PERMISOS, puede, useSesion } from '@/sesion/sesion'
import { consultas, profundidades, siCoincide, type Paquete } from './consultas'

/** Catálogo elegido: el propio (destajo) o el de un cliente (cobro). */
type Catalogo = 'propio' | number

/**
 * Paquetes: el catálogo propio (destajo y material) y el de cada cliente (cobro), con la
 * equivalencia entre ellos. Se elige un paquete del cliente y se marcan los propios que lo forman.
 */
export function PantallaPaquetes() {
  const sesion = useSesion()
  const administra = puede(sesion, PERMISOS.administrar)
  const paquetes = useQuery(consultas.paquetes)
  const clientes = useQuery(consultas.clientes)
  const [catalogo, setCatalogo] = useState<Catalogo>('propio')
  const [seleccionado, setSeleccionado] = useState<Paquete>()
  const [editando, setEditando] = useState<Paquete>()

  const niveles = useMemo(() => profundidades(paquetes.data ?? []), [paquetes.data])
  const delCatalogo = (paquetes.data ?? []).filter((p) =>
    catalogo === 'propio' ? p.clienteId == null : p.clienteId === catalogo,
  )
  const propios = (paquetes.data ?? []).filter((p) => p.clienteId == null)

  const elegirCatalogo = (valor: string) => {
    setCatalogo(valor === 'propio' ? 'propio' : Number(valor))
    setSeleccionado(undefined)
    setEditando(undefined)
  }

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold">Paquetes</h1>
        <p className="text-tenue">
          El paquete es la unidad con la que se cobra al cliente, se paga destajo y se carga
          material.
        </p>
      </header>
      <div className="w-full max-w-sm">
        <Seleccion
          etiqueta="Catálogo"
          opciones={[
            { valor: 'propio', texto: 'Propio (destajo y material)' },
            ...(clientes.data ?? []).map((c) => ({ valor: c.id, texto: `De ${c.nombre} (cobro)` })),
          ]}
          value={String(catalogo)}
          onChange={(e) => elegirCatalogo(e.target.value)}
        />
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <Tarjeta className="self-start p-0">
          {paquetes.isError && (
            <div className="p-4">
              <Aviso>{mensajeDe(paquetes.error)}</Aviso>
            </div>
          )}
          {delCatalogo.length === 0 && paquetes.isSuccess && (
            <p className="p-6 text-sm text-tenue">Este catálogo todavía no tiene paquetes.</p>
          )}
          <ul aria-label="Paquetes del catálogo" className="divide-y divide-borde">
            {delCatalogo.map((p) => (
              <li
                key={p.id}
                className={cn(
                  'flex items-center gap-2 px-4 py-1.5',
                  seleccionado?.id === p.id && 'bg-marca/5',
                )}
                style={{ paddingLeft: `${1 + (niveles.get(p.id) ?? 0) * 1.5}rem` }}
              >
                <button
                  type="button"
                  className="flex min-h-10 flex-1 items-center gap-2 text-left text-sm"
                  aria-pressed={seleccionado?.id === p.id}
                  onClick={() => setSeleccionado(p)}
                >
                  <span className="font-mono text-tenue">{p.clave}</span>
                  <span className={cn((niveles.get(p.id) ?? 0) === 0 && 'font-medium')}>
                    {p.nombre}
                  </span>
                  {p.unidad && <span className="text-xs text-tenue">({p.unidad})</span>}
                  {!p.activo && <EstadoActivo activo={false} />}
                </button>
                {administra && (
                  <Boton
                    variante="fantasma"
                    aria-label={`Editar ${p.clave} ${p.nombre}`}
                    onClick={() => setEditando(p)}
                  >
                    <Pencil className="size-4" />
                  </Boton>
                )}
              </li>
            ))}
          </ul>
        </Tarjeta>

        <div className="flex flex-col gap-6">
          {seleccionado && seleccionado.clienteId != null && (
            <EquivalenciasDe
              key={seleccionado.id}
              paquete={seleccionado}
              propios={propios}
              niveles={niveles}
              administra={administra}
            />
          )}
          {seleccionado && seleccionado.clienteId == null && (
            <UsadoEn paquete={seleccionado} todos={paquetes.data ?? []} />
          )}
          {administra && (
            <FormularioPaquete
              key={editando?.id ?? `nuevo-${String(catalogo)}`}
              catalogo={catalogo}
              paquetes={delCatalogo}
              editando={editando}
              niveles={niveles}
              alTerminar={() => setEditando(undefined)}
            />
          )}
        </div>
      </div>
    </div>
  )
}

function FormularioPaquete({
  catalogo,
  paquetes,
  editando,
  niveles,
  alTerminar,
}: {
  catalogo: Catalogo
  paquetes: Paquete[]
  editando?: Paquete
  niveles: Map<number, number>
  alTerminar: () => void
}) {
  const cliente = useQueryClient()
  const [padre, setPadre] = useState(aTexto(editando?.padreId))
  const [clave, setClave] = useState(editando?.clave ?? '')
  const [nombre, setNombre] = useState(editando?.nombre ?? '')
  const [unidad, setUnidad] = useState(aTexto(editando?.unidad))
  const [orden, setOrden] = useState(aTexto(editando?.orden))
  const [activo, setActivo] = useState(editando?.activo ?? true)

  const guardar = useMutation({
    mutationFn: async () => {
      const body = {
        clienteId: catalogo === 'propio' ? undefined : catalogo,
        padreId: id(padre),
        clave: texto(clave) ?? '',
        nombre: texto(nombre) ?? '',
        unidad: texto(unidad),
        orden: texto(orden) ? Number(orden) : undefined,
        activo,
      }
      return editando
        ? exigir(
            await api.PATCH('/api/v1/paquetes/{id}', {
              params: { path: { id: editando.id }, header: siCoincide(editando.version) },
              body,
            }),
          )
        : exigir(await api.POST('/api/v1/paquetes', { body }))
    },
    onSuccess: () => {
      void cliente.invalidateQueries({ queryKey: consultas.paquetes.queryKey })
      if (editando) {
        alTerminar()
      } else {
        setClave('')
        setNombre('')
      }
    },
  })
  const errores = guardar.error instanceof ErrorApi ? guardar.error.campos : {}
  const posiblesPadres = paquetes.filter((p) => p.id !== editando?.id)

  const enviar = (evento: FormEvent) => {
    evento.preventDefault()
    guardar.mutate()
  }

  return (
    <Tarjeta>
      <h2 className="text-lg font-medium">
        {editando ? `Cambiar ${editando.clave}` : 'Nuevo paquete'}
      </h2>
      <form noValidate onSubmit={enviar} className="mt-4 grid gap-4 md:grid-cols-2">
        <div className="md:col-span-2">
          <Seleccion
            etiqueta="Dentro de"
            vacio="Ninguno (es una sección)"
            opciones={posiblesPadres.map((p) => ({
              valor: p.id,
              texto: `${'· '.repeat(niveles.get(p.id) ?? 0)}${p.clave} ${p.nombre}`,
            }))}
            value={padre}
            onChange={(e) => setPadre(e.target.value)}
          />
        </div>
        <Campo
          etiqueta="Clave"
          placeholder="2.1"
          value={clave}
          error={errores.clave}
          onChange={(e) => setClave(e.target.value)}
        />
        <Campo
          etiqueta="Unidad (opcional)"
          placeholder="Lote"
          value={unidad}
          onChange={(e) => setUnidad(e.target.value)}
        />
        <div className="md:col-span-2">
          <Campo
            etiqueta="Nombre"
            value={nombre}
            error={errores.nombre}
            onChange={(e) => setNombre(e.target.value)}
          />
        </div>
        <Campo
          etiqueta="Orden (opcional)"
          inputMode="numeric"
          ayuda="Para acomodar los paquetes; si no, van por clave"
          value={orden}
          onChange={(e) => setOrden(e.target.value)}
        />
        {editando && (
          <label className="flex min-h-11 items-center gap-2 self-end text-sm">
            <input
              type="checkbox"
              className="size-4"
              checked={activo}
              onChange={(e) => setActivo(e.target.checked)}
            />
            Activo
          </label>
        )}
        <div className="flex flex-col gap-3 md:col-span-2">
          {guardar.isError && <Aviso>{mensajeDe(guardar.error)}</Aviso>}
          {guardar.isSuccess && !editando && <Aviso tipo="exito">Paquete guardado.</Aviso>}
          <div className="flex gap-2">
            <Boton type="submit" disabled={guardar.isPending}>
              {editando ? 'Guardar cambios' : 'Dar de alta'}
            </Boton>
            {editando && (
              <Boton variante="secundario" onClick={alTerminar}>
                Cancelar
              </Boton>
            )}
          </div>
        </div>
      </form>
    </Tarjeta>
  )
}

function EquivalenciasDe({
  paquete,
  propios,
  niveles,
  administra,
}: {
  paquete: Paquete
  propios: Paquete[]
  niveles: Map<number, number>
  administra: boolean
}) {
  const cliente = useQueryClient()
  const equivalencias = useQuery(consultas.equivalencias)
  const [elegidos, setElegidos] = useState<Set<number>>()
  const actuales =
    elegidos ??
    new Set(
      (equivalencias.data ?? [])
        .filter((e) => e.paqueteClienteId === paquete.id)
        .map((e) => e.paquetePropioId),
    )

  const guardar = useMutation({
    mutationFn: async () =>
      exigir(
        await api.PUT('/api/v1/paquetes/{id}/equivalencias', {
          params: { path: { id: paquete.id } },
          body: { paquetePropioIds: [...actuales] },
        }),
      ),
    onSuccess: () => {
      setElegidos(undefined)
      void cliente.invalidateQueries({ queryKey: consultas.equivalencias.queryKey })
    },
  })

  const alternar = (propio: number) => {
    const siguiente = new Set(actuales)
    if (siguiente.has(propio)) siguiente.delete(propio)
    else siguiente.add(propio)
    setElegidos(siguiente)
  }

  return (
    <Tarjeta>
      <h2 className="text-lg font-medium">
        {paquete.clave} {paquete.nombre}
      </h2>
      <p className="mt-1 text-sm text-tenue">
        Paquetes propios que lo forman (destajo y material).
      </p>
      {propios.length === 0 && (
        <p className="mt-3 text-sm text-tenue">
          Primero da de alta paquetes en el catálogo propio.
        </p>
      )}
      <ul className="mt-3 flex max-h-96 flex-col overflow-y-auto">
        {propios.map((p) => (
          <li key={p.id} style={{ paddingLeft: `${(niveles.get(p.id) ?? 0) * 1.25}rem` }}>
            <label className="flex min-h-10 items-center gap-2 text-sm">
              <input
                type="checkbox"
                className="size-4"
                checked={actuales.has(p.id)}
                disabled={!administra}
                onChange={() => alternar(p.id)}
              />
              <span className="font-mono text-tenue">{p.clave}</span> {p.nombre}
            </label>
          </li>
        ))}
      </ul>
      {administra && (
        <div className="mt-3 flex flex-col gap-3">
          {guardar.isError && <Aviso>{mensajeDe(guardar.error)}</Aviso>}
          {guardar.isSuccess && <Aviso tipo="exito">Equivalencia guardada.</Aviso>}
          <Boton
            variante="secundario"
            className="self-start"
            disabled={elegidos === undefined || guardar.isPending}
            onClick={() => guardar.mutate()}
          >
            Guardar equivalencia
          </Boton>
        </div>
      )}
    </Tarjeta>
  )
}

function UsadoEn({ paquete, todos }: { paquete: Paquete; todos: Paquete[] }) {
  const equivalencias = useQuery(consultas.equivalencias)
  const delCliente = (equivalencias.data ?? [])
    .filter((e) => e.paquetePropioId === paquete.id)
    .map((e) => todos.find((p) => p.id === e.paqueteClienteId))
    .filter((p): p is Paquete => p !== undefined)
  return (
    <Tarjeta>
      <h2 className="text-lg font-medium">
        {paquete.clave} {paquete.nombre}
      </h2>
      <p className="mt-1 text-sm text-tenue">Forma parte de estos paquetes de cliente:</p>
      {delCliente.length === 0 ? (
        <p className="mt-3 text-sm text-tenue">Ninguno todavía.</p>
      ) : (
        <ul className="mt-3 list-disc pl-5 text-sm">
          {delCliente.map((p) => (
            <li key={p.id}>
              {p.cliente}: <span className="font-mono">{p.clave}</span> {p.nombre}
            </li>
          ))}
        </ul>
      )}
    </Tarjeta>
  )
}

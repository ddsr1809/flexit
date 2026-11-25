import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { ArrowLeft } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { EstadoActivo } from '@/components/ui/insignia'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { formatearMoneda } from '@/lib/formato'
import { clavesDeLotes } from '@/lib/lotes'
import { id, numero, texto } from '@/lib/valores'
import { PERMISOS, puede, puedeAlguno, useSesion } from '@/sesion/sesion'
import { consultas, siCoincide, vigencia, type Contrato, type UnidadObra } from './consultas'
import { FormularioContrato } from './FormularioContrato'
import { EstadoContrato } from './PantallaContratos'

/** Un contrato: datos, unidades de obra, residentes asignados y precios por paquete. */
export function PantallaContrato({ contratoId }: { contratoId: number }) {
  const sesion = useSesion()
  const contrato = useQuery(consultas.contrato(contratoId))
  const [editando, setEditando] = useState(false)
  const administra = puede(sesion, PERMISOS.administrar)
  const veCostos = puedeAlguno(sesion, PERMISOS.costoVerTodo, PERMISOS.costoVerPropio)

  if (contrato.isPending) return <p className="text-tenue">Cargando…</p>
  if (contrato.isError) {
    return (
      <div className="flex max-w-3xl flex-col gap-4">
        <Aviso>{mensajeDe(contrato.error)}</Aviso>
        <Link to="/contratos" className="text-marca underline">
          Volver a contratos
        </Link>
      </div>
    )
  }
  const c = contrato.data

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header className="flex flex-col gap-2">
        <Link
          to="/contratos"
          className="flex items-center gap-1 text-sm text-tenue hover:text-texto"
        >
          <ArrowLeft className="size-4" /> Contratos
        </Link>
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="font-mono text-2xl font-semibold">{c.codigo}</h1>
          <EstadoContrato estado={c.estado} />
        </div>
        <p className="text-tenue">
          {c.cliente}
          {c.frente ? ` · ${c.frente}` : ''}
          {c.numeroObra ? ` · No. obra ${c.numeroObra}` : ''} · {vigencia(c)}
          {c.monto != null ? ` · ${formatearMoneda(c.monto)}` : ''}
        </p>
      </header>

      {administra && (
        <Tarjeta>
          <div className="flex items-center justify-between gap-4">
            <h2 className="text-lg font-medium">Datos del contrato</h2>
            <Boton variante="secundario" onClick={() => setEditando(!editando)}>
              {editando ? 'Cerrar' : 'Cambiar datos'}
            </Boton>
          </div>
          {editando && (
            <div className="mt-4">
              <FormularioContrato contrato={c} />
            </div>
          )}
        </Tarjeta>
      )}

      <Unidades contrato={c} administra={administra} />
      {administra && <Residentes contratoId={c.id} />}
      {veCostos && <Precios contrato={c} administra={administra} />}
    </div>
  )
}

function Unidades({ contrato, administra }: { contrato: Contrato; administra: boolean }) {
  const cliente = useQueryClient()
  const unidades = useQuery(consultas.unidades(contrato.id))
  const prototipos = useQuery(consultas.prototipos)
  const tipos = useQuery(consultas.tiposUnidad)
  const [tipo, setTipo] = useState('LOTE')
  const [manzana, setManzana] = useState('')
  const [lotes, setLotes] = useState('')
  const [clave, setClave] = useState('')
  const [prototipo, setPrototipo] = useState('')

  const esLote = tipos.data?.find((t) => t.clave === tipo)?.usaManzanaLote ?? true
  const vista = esLote && manzana && lotes ? clavesDeLotes(manzana, lotes) : undefined
  const opcionesPrototipo = (prototipos.data ?? [])
    .filter((p) => p.activo)
    .map((p) => ({ valor: p.id, texto: `${p.clave} · ${p.nombre}` }))

  const crear = useMutation({
    mutationFn: async () =>
      exigir(
        await api.POST('/api/v1/contratos/{id}/unidades', {
          params: { path: { id: contrato.id } },
          body: esLote
            ? { tipo, manzana, lotes, prototipoId: id(prototipo) }
            : { tipo, clave, prototipoId: id(prototipo) },
        }),
      ),
    onSuccess: () => {
      setLotes('')
      setClave('')
      void cliente.invalidateQueries({ queryKey: ['contratos'] })
    },
  })

  const cambiar = useMutation({
    mutationFn: async ({
      unidad,
      prototipoId,
      activa,
    }: {
      unidad: UnidadObra
      prototipoId?: number
      activa: boolean
    }) =>
      exigir(
        await api.PATCH('/api/v1/unidades/{id}', {
          params: { path: { id: unidad.id }, header: siCoincide(unidad.version) },
          body: {
            prototipoId,
            presupuestoMaterial: unidad.presupuestoMaterial ?? undefined,
            activa,
          },
        }),
      ),
    onSettled: () =>
      void cliente.invalidateQueries({ queryKey: consultas.unidades(contrato.id).queryKey }),
  })

  const enviar = (evento: FormEvent) => {
    evento.preventDefault()
    crear.mutate()
  }

  return (
    <Tarjeta className="p-0">
      <div className="flex items-baseline justify-between gap-4 px-6 pt-5 pb-3">
        <h2 className="text-lg font-medium">Lotes y unidades de obra</h2>
        <p className="text-sm text-tenue">{unidades.data?.length ?? 0} unidades</p>
      </div>
      {unidades.isError && (
        <div className="px-6 pb-4">
          <Aviso>{mensajeDe(unidades.error)}</Aviso>
        </div>
      )}
      {cambiar.isError && (
        <div className="px-6 pb-4">
          <Aviso>{mensajeDe(cambiar.error)}</Aviso>
        </div>
      )}
      <Tabla
        filas={unidades.data}
        clave={(u) => u.id}
        vacio="Este contrato todavía no tiene lotes."
        columnas={[
          {
            titulo: 'Clave',
            celda: (u) => <span className="font-mono font-medium">{u.clave}</span>,
          },
          {
            titulo: 'Tipo',
            celda: (u) => tipos.data?.find((t) => t.clave === u.tipo)?.nombre ?? u.tipo,
          },
          {
            titulo: 'Prototipo',
            celda: (u) =>
              administra ? (
                <select
                  aria-label={`Prototipo de ${u.clave}`}
                  className="min-h-9 rounded-md border border-borde bg-white px-2"
                  value={u.prototipoId ?? ''}
                  disabled={cambiar.isPending}
                  onChange={(e) =>
                    cambiar.mutate({ unidad: u, prototipoId: id(e.target.value), activa: u.activa })
                  }
                >
                  <option value="">Sin prototipo</option>
                  {opcionesPrototipo.map((o) => (
                    <option key={o.valor} value={o.valor}>
                      {o.texto}
                    </option>
                  ))}
                </select>
              ) : (
                (u.prototipo ?? '—')
              ),
          },
          { titulo: 'Estado', celda: (u) => <EstadoActivo activo={u.activa} /> },
        ]}
        acciones={
          administra
            ? (u) => (
                <Boton
                  variante="fantasma"
                  disabled={cambiar.isPending}
                  onClick={() =>
                    cambiar.mutate({
                      unidad: u,
                      prototipoId: u.prototipoId ?? undefined,
                      activa: !u.activa,
                    })
                  }
                >
                  {u.activa ? 'Desactivar' : 'Activar'}
                </Boton>
              )
            : undefined
        }
      />
      {administra && contrato.estado === 'activo' && (
        <form
          aria-label="Agregar unidades"
          noValidate
          onSubmit={enviar}
          className="grid gap-4 border-t border-borde p-6 md:grid-cols-4"
        >
          <h3 className="font-medium md:col-span-4">Agregar</h3>
          <Seleccion
            etiqueta="Tipo"
            opciones={(tipos.data ?? []).map((t) => ({ valor: t.clave, texto: t.nombre }))}
            value={tipo}
            onChange={(e) => setTipo(e.target.value)}
          />
          {esLote ? (
            <>
              <Campo
                etiqueta="Manzana"
                inputMode="numeric"
                value={manzana}
                onChange={(e) => setManzana(e.target.value)}
              />
              <Campo
                etiqueta="Lotes"
                placeholder="29-31, 35"
                ayuda="Rangos con guion, separados por coma"
                value={lotes}
                onChange={(e) => setLotes(e.target.value)}
              />
            </>
          ) : (
            <div className="md:col-span-2">
              <Campo
                etiqueta="Clave"
                placeholder="Caseta de acceso"
                value={clave}
                onChange={(e) => setClave(e.target.value)}
              />
            </div>
          )}
          <Seleccion
            etiqueta="Prototipo"
            vacio="Sin prototipo"
            opciones={opcionesPrototipo}
            value={prototipo}
            onChange={(e) => setPrototipo(e.target.value)}
          />
          <div className="flex flex-col gap-3 md:col-span-4">
            {vista && 'lotes' in vista && (
              <p className="text-sm text-tenue" aria-live="polite">
                Se agregarán {vista.lotes.length} {vista.lotes.length === 1 ? 'lote' : 'lotes'}:{' '}
                <span className="font-mono">
                  {vista.lotes.slice(0, 8).join(', ')}
                  {vista.lotes.length > 8 ? '…' : ''}
                </span>
              </p>
            )}
            {vista && 'error' in vista && <p className="text-sm text-peligro">{vista.error}</p>}
            {crear.isError && <Aviso>{mensajeDe(crear.error)}</Aviso>}
            {crear.isSuccess && (
              <Aviso tipo="exito">
                Se agregaron {crear.data.length} {crear.data.length === 1 ? 'unidad' : 'unidades'}.
              </Aviso>
            )}
            <Boton
              type="submit"
              disabled={crear.isPending || (vista !== undefined && 'error' in vista)}
              className="self-start"
            >
              {crear.isPending ? 'Guardando…' : 'Agregar'}
            </Boton>
          </div>
        </form>
      )}
    </Tarjeta>
  )
}

function Residentes({ contratoId }: { contratoId: number }) {
  const cliente = useQueryClient()
  const asignados = useQuery(consultas.residentes(contratoId))
  const candidatos = useQuery(consultas.candidatosResidente)
  const [elegidos, setElegidos] = useState<Set<number>>()
  const actuales = elegidos ?? new Set(asignados.data?.map((r) => r.id))

  const guardar = useMutation({
    mutationFn: async () =>
      exigir(
        await api.PUT('/api/v1/contratos/{id}/residentes', {
          params: { path: { id: contratoId } },
          body: { usuarioIds: [...actuales] },
        }),
      ),
    onSuccess: () => {
      setElegidos(undefined)
      void cliente.invalidateQueries({ queryKey: consultas.residentes(contratoId).queryKey })
    },
  })

  const alternar = (usuario: number) => {
    const siguiente = new Set(actuales)
    if (siguiente.has(usuario)) siguiente.delete(usuario)
    else siguiente.add(usuario)
    setElegidos(siguiente)
  }

  // Los asignados que ya no tienen rol residente también se muestran, para poder quitarlos.
  const lista = [
    ...(candidatos.data ?? []),
    ...(asignados.data ?? []).filter((a) => !candidatos.data?.some((c) => c.id === a.id)),
  ]

  return (
    <Tarjeta>
      <h2 className="text-lg font-medium">Residentes</h2>
      <p className="mt-1 text-sm text-tenue">
        Un residente solo ve los contratos que tiene asignados, con sus lotes.
      </p>
      {lista.length === 0 && candidatos.isSuccess && (
        <p className="mt-3 text-sm text-tenue">
          No hay usuarios con rol Residente. Dalos de alta en Usuarios.
        </p>
      )}
      <div className="mt-3 flex flex-wrap gap-4">
        {lista.map((usuario) => (
          <label key={usuario.id} className="flex min-h-11 items-center gap-2 text-sm">
            <input
              type="checkbox"
              className="size-4"
              checked={actuales.has(usuario.id)}
              onChange={() => alternar(usuario.id)}
            />
            {usuario.nombre} <span className="text-tenue">({usuario.usuario})</span>
          </label>
        ))}
      </div>
      <div className="mt-3 flex flex-col gap-3">
        {guardar.isError && <Aviso>{mensajeDe(guardar.error)}</Aviso>}
        {guardar.isSuccess && <Aviso tipo="exito">Residentes guardados.</Aviso>}
        <Boton
          variante="secundario"
          className="self-start"
          disabled={elegidos === undefined || guardar.isPending}
          onClick={() => guardar.mutate()}
        >
          Guardar residentes
        </Boton>
      </div>
    </Tarjeta>
  )
}

function Precios({ contrato, administra }: { contrato: Contrato; administra: boolean }) {
  const cliente = useQueryClient()
  const precios = useQuery(consultas.precios(contrato.id))
  const paquetes = useQuery(consultas.paquetes)
  const prototipos = useQuery(consultas.prototipos)
  const [paquete, setPaquete] = useState('')
  const [prototipo, setPrototipo] = useState('')
  const [cobro, setCobro] = useState('')
  const [destajo, setDestajo] = useState('')

  const disponibles = (paquetes.data ?? []).filter(
    (p) => p.activo && (p.clienteId == null || p.clienteId === contrato.clienteId),
  )
  const cobroNumero = numero(cobro)
  const destajoNumero = numero(destajo)
  const invalido = Number.isNaN(cobroNumero) || Number.isNaN(destajoNumero)

  const fijar = useMutation({
    mutationFn: async () =>
      exigir(
        await api.PUT('/api/v1/contratos/{id}/precios', {
          params: { path: { id: contrato.id } },
          body: {
            paqueteId: id(paquete) ?? 0,
            prototipoId: id(prototipo),
            precioCobro: cobroNumero,
            precioDestajo: destajoNumero,
          },
        }),
      ),
    onSuccess: () => {
      setCobro('')
      setDestajo('')
      void cliente.invalidateQueries({ queryKey: consultas.precios(contrato.id).queryKey })
    },
  })

  const enviar = (evento: FormEvent) => {
    evento.preventDefault()
    if (!invalido) fijar.mutate()
  }

  const moneda = (valor?: number | null) => (valor == null ? '—' : formatearMoneda(valor))

  return (
    <Tarjeta className="p-0">
      <div className="px-6 pt-5 pb-3">
        <h2 className="text-lg font-medium">Precios por paquete</h2>
        <p className="mt-1 text-sm text-tenue">
          Cobro: lo que paga el cliente por paquete. Destajo: lo que se paga a la cuadrilla.
        </p>
      </div>
      {precios.isError && (
        <div className="px-6 pb-4">
          <Aviso>{mensajeDe(precios.error)}</Aviso>
        </div>
      )}
      <Tabla
        filas={precios.data}
        clave={(p) => p.id}
        vacio="Sin precios capturados."
        columnas={[
          {
            titulo: 'Paquete',
            celda: (p) => (
              <>
                <span className="font-mono">{p.paqueteClave}</span> {p.paquete}
              </>
            ),
          },
          { titulo: 'Catálogo', celda: (p) => (p.deCliente ? contrato.cliente : 'Propio') },
          { titulo: 'Prototipo', celda: (p) => p.prototipo ?? 'Todos' },
          { titulo: 'Cobro', celda: (p) => moneda(p.precioCobro), clase: 'text-right' },
          { titulo: 'Destajo', celda: (p) => moneda(p.precioDestajo), clase: 'text-right' },
        ]}
      />
      {administra && (
        <form
          aria-label="Fijar precio"
          noValidate
          onSubmit={enviar}
          className="grid gap-4 border-t border-borde p-6 md:grid-cols-4"
        >
          <h3 className="font-medium md:col-span-4">Fijar precio</h3>
          <div className="md:col-span-2">
            <Seleccion
              etiqueta="Paquete"
              vacio="Elige el paquete"
              opciones={disponibles.map((p) => ({
                valor: p.id,
                texto: `${p.clienteId == null ? 'Propio' : (p.cliente ?? 'Cliente')} · ${p.clave} ${p.nombre}`,
              }))}
              value={paquete}
              onChange={(e) => setPaquete(e.target.value)}
            />
          </div>
          <div className="md:col-span-2">
            <Seleccion
              etiqueta="Prototipo"
              vacio="Todos los prototipos"
              opciones={(prototipos.data ?? []).map((p) => ({ valor: p.id, texto: p.clave }))}
              value={prototipo}
              onChange={(e) => setPrototipo(e.target.value)}
            />
          </div>
          <Campo
            etiqueta="Precio de cobro"
            inputMode="decimal"
            value={cobro}
            error={Number.isNaN(cobroNumero) ? 'Solo el número' : undefined}
            onChange={(e) => setCobro(e.target.value)}
          />
          <Campo
            etiqueta="Precio de destajo"
            inputMode="decimal"
            value={destajo}
            error={Number.isNaN(destajoNumero) ? 'Solo el número' : undefined}
            onChange={(e) => setDestajo(e.target.value)}
          />
          <div className="flex flex-col gap-3 md:col-span-4">
            {fijar.isError && <Aviso>{mensajeDe(fijar.error)}</Aviso>}
            {fijar.isSuccess && <Aviso tipo="exito">Precio guardado.</Aviso>}
            <Boton
              type="submit"
              className="self-start"
              disabled={fijar.isPending || !texto(paquete)}
            >
              Guardar precio
            </Boton>
          </div>
        </form>
      )}
    </Tarjeta>
  )
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Plus, Trash2 } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Buscador } from '@/components/Buscador'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { consultasCatalogo, type Material } from '@/features/catalogo/consultas'
import { consultas, siCoincide } from '@/features/estructura/consultas'
import { buscarMateriales, hoy } from '@/features/inventario/consultas'
import { lotesDeRenglon } from '@/features/inventario/reparto'
import { formatearMoneda } from '@/lib/formato'
import type { LotePresupuesto } from '@/features/reportes/consultas'
import { numero } from '@/lib/valores'
import { PERMISOS, puedeAlguno, useSesion } from '@/sesion/sesion'
import { Semaforo } from './Comunes'
import {
  consultasAbastecimiento,
  type DatosRequisicion,
  type RenglonCaptura,
  type Requisicion,
} from './consultas'

interface Renglon {
  clave: number
  materialId?: number
  material?: string
  unidadControl?: string
  cantidad: string
  unidadId: string
  manzana: string
  lotes: string
  paqueteId: string
  clasificacionId: string
  observaciones: string
}

let siguiente = 1
const vacio = (parcial: Partial<Renglon> = {}): Renglon => ({
  clave: siguiente++,
  cantidad: '',
  unidadId: '',
  manzana: '',
  lotes: '',
  paqueteId: '',
  clasificacionId: '1',
  observaciones: '',
  ...parcial,
})

/** Nueva requisición o corrección de un borrador. */
export function PantallaCapturaRequisicion({ id }: { id?: number }) {
  const existente = useQuery({ ...consultasAbastecimiento.requisicion(id ?? 0), enabled: !!id })
  if (id && existente.isError) return <Aviso>{mensajeDe(existente.error)}</Aviso>
  if (id && !existente.data) return <p className="text-tenue">Cargando…</p>
  return <Formulario existente={existente.data} />
}

function Formulario({ existente }: { existente?: Requisicion }) {
  const sesion = useSesion()
  const cliente = useQueryClient()
  const navegar = useNavigate()
  const veCostos = puedeAlguno(sesion, PERMISOS.costoVerPropio, PERMISOS.costoVerTodo)

  const [contratoId, setContratoId] = useState(existente ? String(existente.contratoId) : '')
  const [fechaRequerida, setFechaRequerida] = useState(existente?.fechaRequerida ?? hoy())
  const [urgente, setUrgente] = useState(existente?.urgente ?? false)
  const [observaciones, setObservaciones] = useState(existente?.observaciones ?? '')
  const [renglones, setRenglones] = useState<Renglon[]>(() =>
    existente
      ? existente.renglones.map((r) => {
          const [, manzana = '', lote = ''] = /^M(\w+)-L(\w+)$/.exec(r.unidadObra) ?? []
          return vacio({
            materialId: r.materialId,
            material: r.material,
            unidadControl: r.unidadControl,
            cantidad: String(r.cantidadCaptura),
            unidadId: String(r.unidadCapturaId),
            manzana,
            lotes: lote,
            paqueteId: String(r.paqueteId),
            clasificacionId: String(r.clasificacionId),
            observaciones: r.observaciones ?? '',
          })
        })
      : [vacio()],
  )
  const [avisoValidacion, setAvisoValidacion] = useState<string>()

  const contratos = useQuery(consultas.contratos('activo'))
  const paquetes = useQuery(consultas.paquetes)
  const clasificaciones = useQuery(consultasCatalogo.clasificaciones)
  const unidades = useQuery(consultasCatalogo.unidades)
  const lotes = useQuery({
    ...consultas.unidades(Number(contratoId)),
    enabled: !!contratoId,
  })

  const cambiar = (clave: number, cambio: Partial<Renglon>) =>
    setRenglones((actual) => actual.map((r) => (r.clave === clave ? { ...r, ...cambio } : r)))

  // Lotes elegidos en todos los renglones, para avisar del presupuesto antes de guardar.
  const lotesElegidos = useMemo(() => {
    const ids = new Set<number>()
    for (const r of renglones) {
      if (!r.manzana.trim() || !r.lotes.trim()) continue
      const resultado = lotesDeRenglon(lotes.data ?? [], r.manzana, r.lotes)
      if ('ids' in resultado) resultado.ids.forEach((i) => ids.add(i))
    }
    return [...ids].sort((a, b) => a - b)
  }, [renglones, lotes.data])
  const presupuesto = useQuery({
    queryKey: ['reportes', 'presupuesto', { unidadIds: lotesElegidos }],
    queryFn: async () =>
      exigir(
        await api.GET('/api/v1/reportes/presupuesto', {
          params: { query: { unidadIds: lotesElegidos } },
        }),
      ) as unknown as LotePresupuesto[],
    enabled: veCostos && lotesElegidos.length > 0,
  })
  const enAviso = (presupuesto.data ?? []).filter(
    (l) => l.semaforo === 'amarillo' || l.semaforo === 'rojo',
  )

  const guardar = useMutation({
    mutationFn: async (datos: DatosRequisicion) =>
      existente
        ? exigir(
            await api.PUT('/api/v1/requisiciones/{id}', {
              params: { path: { id: existente.id }, header: siCoincide(existente.version) },
              body: datos,
            }),
          )
        : exigir(await api.POST('/api/v1/requisiciones', { body: datos })),
    onSuccess: (r) => {
      cliente.setQueryData(consultasAbastecimiento.requisicion(r.id).queryKey, r)
      void cliente.invalidateQueries({ queryKey: ['requisiciones'] })
      void navegar({ to: '/requisiciones/$id', params: { id: String(r.id) } })
    },
  })

  const armar = (enviar: boolean): DatosRequisicion | string => {
    if (!contratoId) return 'Elige el contrato.'
    const salida: RenglonCaptura[] = []
    for (const [indice, r] of renglones.entries()) {
      const donde = `Renglón ${indice + 1}: `
      const cantidad = numero(r.cantidad)
      if (!r.materialId) return `${donde}elige el material.`
      if (!cantidad || cantidad <= 0) return `${donde}escribe la cantidad.`
      if (!r.paqueteId) return `${donde}elige el paquete.`
      const resultado = lotesDeRenglon(lotes.data ?? [], r.manzana, r.lotes)
      if ('error' in resultado) return donde + resultado.error
      salida.push({
        materialId: r.materialId,
        cantidad,
        unidadId: r.unidadId ? Number(r.unidadId) : undefined,
        unidadObraIds: resultado.ids,
        paqueteId: Number(r.paqueteId),
        clasificacionId: Number(r.clasificacionId),
        observaciones: r.observaciones.trim() || undefined,
      })
    }
    return {
      contratoId: Number(contratoId),
      fechaRequerida,
      urgente,
      observaciones: observaciones.trim() || undefined,
      renglones: salida,
      enviar,
    }
  }

  const enviar = (e: FormEvent<HTMLFormElement>, conEnvio: boolean) => {
    e.preventDefault()
    const datos = armar(conEnvio)
    if (typeof datos === 'string') {
      setAvisoValidacion(datos)
      return
    }
    setAvisoValidacion(undefined)
    guardar.mutate(datos)
  }

  const opcionesPaquete = (paquetes.data ?? [])
    .filter((p) => p.activo)
    .map((p) => ({ valor: p.id, texto: `${p.clave} ${p.nombre}` }))
  const opcionesRazon = (clasificaciones.data ?? []).map((c) => ({ valor: c.id, texto: c.nombre }))
  const opcionesUnidad = (unidades.data ?? [])
    .filter((u) => u.activa)
    .map((u) => ({ valor: u.id, texto: u.clave }))

  return (
    <form
      className="flex max-w-6xl flex-col gap-6"
      onSubmit={(e) => enviar(e, (e.nativeEvent as SubmitEvent).submitter?.id === 'enviar')}
    >
      <header>
        <Link to="/requisiciones" className="text-sm text-marca hover:underline">
          ← Requisiciones
        </Link>
        <h1 className="text-2xl font-semibold">
          {existente ? `Corregir ${existente.folio}` : 'Nueva requisición'}
        </h1>
        <p className="text-tenue">
          Un renglón por material; con varios lotes la cantidad se reparte exacta entre ellos.
        </p>
      </header>

      {existente?.notaRechazo && <Aviso>Regresada para corregir: {existente.notaRechazo}</Aviso>}

      <Tarjeta className="grid gap-4 md:grid-cols-4 md:items-end">
        <Seleccion
          etiqueta="Contrato"
          vacio="Elige…"
          opciones={(contratos.data ?? []).map((c) => ({ valor: c.id, texto: c.codigo }))}
          value={contratoId}
          disabled={!!existente}
          onChange={(e) => setContratoId(e.target.value)}
        />
        <Campo
          etiqueta="Se necesita el"
          type="date"
          min={hoy()}
          value={fechaRequerida}
          onChange={(e) => setFechaRequerida(e.target.value)}
        />
        <label className="flex min-h-11 items-center gap-2 text-sm font-medium">
          <input
            type="checkbox"
            className="size-5"
            checked={urgente}
            onChange={(e) => setUrgente(e.target.checked)}
          />
          Urgente (se surte y se pide primero)
        </label>
        <Campo
          etiqueta="Observaciones"
          value={observaciones}
          onChange={(e) => setObservaciones(e.target.value)}
        />
      </Tarjeta>

      <Tarjeta className="flex flex-col gap-3">
        <h2 className="font-medium">Materiales</h2>
        {renglones.map((r, indice) => (
          <div
            key={r.clave}
            className="grid items-start gap-3 border-b border-borde pb-3 last:border-0 md:grid-cols-12"
          >
            <div className="md:col-span-3">
              <Buscador<Material>
                etiqueta={`Material ${indice + 1}`}
                placeholder="Clave o nombre"
                nombre="materiales"
                buscar={buscarMateriales}
                texto={(m) => m.nombre}
                detalle={(m) => `${m.clave} · ${m.unidadControl}`}
                clave={(m) => m.id}
                valor={r.material}
                alElegir={(m) =>
                  cambiar(r.clave, {
                    materialId: m.id,
                    material: m.nombre,
                    unidadControl: m.unidadControl,
                    unidadId: '',
                  })
                }
              />
            </div>
            <div className="md:col-span-1">
              <Campo
                etiqueta="Cantidad"
                inputMode="decimal"
                value={r.cantidad}
                onChange={(e) => cambiar(r.clave, { cantidad: e.target.value })}
              />
            </div>
            <div className="md:col-span-1">
              <Seleccion
                etiqueta="Unidad"
                opciones={opcionesUnidad}
                vacio={r.unidadControl ?? '—'}
                value={r.unidadId}
                onChange={(e) => cambiar(r.clave, { unidadId: e.target.value })}
              />
            </div>
            <div className="md:col-span-1">
              <Campo
                etiqueta="Mza"
                value={r.manzana}
                onChange={(e) => cambiar(r.clave, { manzana: e.target.value })}
              />
            </div>
            <div className="md:col-span-2">
              <Campo
                etiqueta="Lotes"
                placeholder="34 o 1-18, 20"
                value={r.lotes}
                ayuda={ayudaLotes(lotes.data ?? [], r)}
                onChange={(e) => cambiar(r.clave, { lotes: e.target.value })}
              />
            </div>
            <div className="md:col-span-2">
              <Seleccion
                etiqueta="Paquete"
                vacio="Elige…"
                opciones={opcionesPaquete}
                value={r.paqueteId}
                onChange={(e) => cambiar(r.clave, { paqueteId: e.target.value })}
              />
            </div>
            <div className="md:col-span-1">
              <Seleccion
                etiqueta="Razón"
                opciones={opcionesRazon}
                value={r.clasificacionId}
                onChange={(e) => cambiar(r.clave, { clasificacionId: e.target.value })}
              />
            </div>
            <div className="flex items-end md:col-span-1 md:h-full">
              <Boton
                variante="fantasma"
                aria-label={`Quitar el renglón ${indice + 1}`}
                disabled={renglones.length === 1}
                onClick={() => setRenglones(renglones.filter((x) => x.clave !== r.clave))}
              >
                <Trash2 className="size-4" />
              </Boton>
            </div>
          </div>
        ))}
        <div>
          <Boton
            variante="secundario"
            onClick={() => {
              const ultimo = renglones.at(-1)
              setRenglones([
                ...renglones,
                vacio({
                  manzana: ultimo?.manzana ?? '',
                  lotes: ultimo?.lotes ?? '',
                  paqueteId: ultimo?.paqueteId ?? '',
                  clasificacionId: ultimo?.clasificacionId ?? '1',
                }),
              ])
            }}
          >
            <Plus className="size-4" /> Otro material
          </Boton>
        </div>
      </Tarjeta>

      {enAviso.length > 0 && (
        <Tarjeta className="flex flex-col gap-2">
          <h2 className="font-medium">Presupuesto de los lotes</h2>
          <ul className="flex flex-col gap-1 text-sm">
            {enAviso.map((l) => (
              <li key={l.unidadObraId} className="flex flex-wrap items-center gap-2">
                <Semaforo semaforo={l.semaforo} porcentaje={l.porcentaje} />
                <span className="font-mono">{l.clave}</span>
                <span className="text-tenue">
                  lleva {formatearMoneda(l.consumido)}
                  {l.presupuesto != null && ` de ${formatearMoneda(l.presupuesto)}`}
                </span>
              </li>
            ))}
          </ul>
          <p className="text-sm text-tenue">
            Es un aviso: la requisición se puede guardar y coordinación decide al autorizar.
          </p>
        </Tarjeta>
      )}

      {avisoValidacion && <Aviso>{avisoValidacion}</Aviso>}
      {guardar.isError && <Aviso>{mensajeDe(guardar.error)}</Aviso>}

      <div className="flex flex-wrap gap-2">
        <Boton id="enviar" type="submit" disabled={guardar.isPending}>
          Guardar y enviar a autorizar
        </Boton>
        {!existente && (
          <Boton id="borrador" type="submit" variante="secundario" disabled={guardar.isPending}>
            Guardar borrador
          </Boton>
        )}
        {existente && (
          <Boton id="guardar" type="submit" variante="secundario" disabled={guardar.isPending}>
            Guardar sin enviar
          </Boton>
        )}
      </div>
    </form>
  )
}

function ayudaLotes(lotes: Parameters<typeof lotesDeRenglon>[0], r: Renglon): string | undefined {
  if (!r.manzana.trim() || !r.lotes.trim()) return undefined
  const resultado = lotesDeRenglon(lotes, r.manzana, r.lotes)
  if ('error' in resultado) return resultado.error
  return resultado.claves.length === 1 ? resultado.claves[0] : `${resultado.claves.length} lotes`
}

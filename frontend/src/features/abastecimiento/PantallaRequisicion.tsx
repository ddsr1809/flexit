import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Ban, Check, Lock, PackageCheck, Pencil, Send, Truck, X } from 'lucide-react'
import { useState } from 'react'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Insignia } from '@/components/ui/insignia'
import { Tarjeta } from '@/components/ui/tarjeta'
import { formatearDia, hoy } from '@/features/inventario/consultas'
import { TonoEstado } from '@/features/inventario/PantallaMovimientos'
import { formatearFecha, formatearNumero } from '@/lib/formato'
import { numero } from '@/lib/valores'
import { sugerirSurtido } from './calculos'
import { Avance, Dato, EstadoPedido, EstadoRequisicion } from './Comunes'
import { consultasAbastecimiento, type RenglonRequisicion, type Requisicion } from './consultas'

type Accion = 'enviar' | 'autorizar' | 'reservar'
type ConTexto = { que: 'rechazar' | 'cerrar' | 'cancelar'; texto: string }

const PREGUNTA: Record<ConTexto['que'], string> = {
  rechazar: '¿Qué hay que corregir?',
  cerrar: '¿Por qué se cierra? Lo apartado se libera.',
  cancelar: '¿Por qué se cancela?',
}

/** Una requisición con el avance de cada renglón, sus vales y sus pedidos. */
export function PantallaRequisicion({ id }: { id: number }) {
  const cliente = useQueryClient()
  const navegar = useNavigate()
  const consulta = useQuery(consultasAbastecimiento.requisicion(id))
  const [conTexto, setConTexto] = useState<ConTexto>()
  const [surtiendo, setSurtiendo] = useState(false)

  const despues = (r: Requisicion) => {
    cliente.setQueryData(consultasAbastecimiento.requisicion(id).queryKey, r)
    void cliente.invalidateQueries({ queryKey: ['requisiciones'] })
    void cliente.invalidateQueries({ queryKey: ['faltantes'] })
    void cliente.invalidateQueries({ queryKey: ['existencias'] })
    setConTexto(undefined)
  }
  const accion = useMutation({
    mutationFn: async (que: Accion) =>
      exigir(await api.POST(`/api/v1/requisiciones/{id}/${que}`, { params: { path: { id } } })),
    onSuccess: despues,
  })
  const conNota = useMutation({
    mutationFn: async ({ que, texto }: ConTexto) =>
      que === 'rechazar'
        ? exigir(
            await api.POST('/api/v1/requisiciones/{id}/rechazar', {
              params: { path: { id } },
              body: { nota: texto },
            }),
          )
        : exigir(
            await api.POST(`/api/v1/requisiciones/{id}/${que}`, {
              params: { path: { id } },
              body: { motivo: texto },
            }),
          ),
    onSuccess: despues,
  })

  if (consulta.isError) return <Aviso>{mensajeDe(consulta.error)}</Aviso>
  const r = consulta.data
  if (!r) return <p className="text-tenue">Cargando…</p>
  const ocupado = accion.isPending || conNota.isPending
  const error = accion.error ?? conNota.error

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <Link to="/requisiciones" className="text-sm text-marca hover:underline">
            ← Requisiciones
          </Link>
          <h1 className="text-2xl font-semibold">
            Requisición <span className="font-mono">{r.folio}</span>
          </h1>
          <p className="flex flex-wrap items-center gap-2 text-tenue">
            <EstadoRequisicion estado={r.estado} /> {r.contrato} · {r.almacen} · para el{' '}
            {formatearDia(r.fechaRequerida)}
            {r.urgente && <Insignia>Urgente</Insignia>}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {r.acciones.editar && (
            <Boton
              variante="secundario"
              onClick={() =>
                navegar({ to: '/requisiciones/$id/editar', params: { id: String(r.id) } })
              }
            >
              <Pencil className="size-4" /> Corregir
            </Boton>
          )}
          {r.acciones.enviar && (
            <Boton disabled={ocupado} onClick={() => accion.mutate('enviar')}>
              <Send className="size-4" /> Enviar a autorizar
            </Boton>
          )}
          {r.acciones.autorizar && (
            <>
              <Boton disabled={ocupado} onClick={() => accion.mutate('autorizar')}>
                <Check className="size-4" /> Autorizar y apartar
              </Boton>
              <Boton
                variante="secundario"
                disabled={ocupado}
                onClick={() => setConTexto({ que: 'rechazar', texto: '' })}
              >
                <X className="size-4" /> Rechazar
              </Boton>
            </>
          )}
          {r.acciones.surtir && (
            <Boton disabled={ocupado} onClick={() => setSurtiendo(true)}>
              <PackageCheck className="size-4" /> Surtir (vale)
            </Boton>
          )}
          {r.acciones.reservar && (
            <Boton
              variante="secundario"
              disabled={ocupado}
              title="Aparta lo que ya hay libre en el almacén para los renglones pendientes"
              onClick={() => accion.mutate('reservar')}
            >
              <Lock className="size-4" /> Apartar lo que llegó
            </Boton>
          )}
          {r.acciones.cerrar && (
            <Boton
              variante="fantasma"
              disabled={ocupado}
              onClick={() => setConTexto({ que: 'cerrar', texto: '' })}
            >
              Cerrar
            </Boton>
          )}
          {r.acciones.cancelar && (
            <Boton
              variante="fantasma"
              disabled={ocupado}
              onClick={() => setConTexto({ que: 'cancelar', texto: '' })}
            >
              <Ban className="size-4" /> Cancelar
            </Boton>
          )}
        </div>
      </header>

      {error && <Aviso>{mensajeDe(error)}</Aviso>}
      {r.notaRechazo && r.estado === 'borrador' && (
        <Aviso>Regresada para corregir: {r.notaRechazo}</Aviso>
      )}

      {conTexto && (
        <Tarjeta className="flex flex-col gap-3 md:flex-row md:items-end">
          <div className="flex-1">
            <Campo
              etiqueta={PREGUNTA[conTexto.que]}
              autoFocus
              value={conTexto.texto}
              onChange={(e) => setConTexto({ ...conTexto, texto: e.target.value })}
            />
          </div>
          <Boton
            disabled={!conTexto.texto.trim() || ocupado}
            onClick={() => conNota.mutate(conTexto)}
          >
            {conTexto.que === 'rechazar' ? 'Regresar a quien la pidió' : 'Confirmar'}
          </Boton>
          <Boton variante="fantasma" onClick={() => setConTexto(undefined)}>
            No
          </Boton>
        </Tarjeta>
      )}

      {surtiendo && (
        <FormularioSurtido
          requisicion={r}
          alTerminar={(movimientoId) => {
            void cliente.invalidateQueries({ queryKey: ['requisiciones'] })
            void cliente.invalidateQueries({ queryKey: ['movimientos'] })
            void navegar({ to: '/movimientos/$id', params: { id: String(movimientoId) } })
          }}
          alCancelar={() => setSurtiendo(false)}
        />
      )}

      <Tarjeta>
        <dl className="grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
          <Dato titulo="Pidió" valor={`${r.capturo} · ${formatearFecha(r.creado)}`} />
          <Dato
            titulo="Autorizó"
            valor={
              r.autorizo
                ? `${r.autorizo}${r.autorizadaEn ? ` · ${formatearFecha(r.autorizadaEn)}` : ''}`
                : undefined
            }
          />
          <Dato titulo="Cierre" valor={r.motivoCierre} />
          <Dato titulo="Observaciones" valor={r.observaciones} />
          <div>
            <dt className="text-tenue">Surtido</dt>
            <dd>
              <Avance valor={r.avance} />
            </dd>
          </div>
        </dl>
      </Tarjeta>

      <Tarjeta className="p-0">
        <Tabla
          filas={r.renglones}
          clave={(x) => x.id}
          columnas={[
            { titulo: '#', celda: (x) => x.renglon, clase: 'text-right' },
            { titulo: 'Lote', celda: (x) => x.unidadObra, clase: 'font-mono whitespace-nowrap' },
            {
              titulo: 'Material',
              celda: (x) => (
                <span>
                  {x.material}
                  <span className="block text-xs text-tenue">
                    {x.paquete} · {x.clasificacion}
                  </span>
                </span>
              ),
            },
            { titulo: 'Pedido', celda: (x) => cantidad(x.cantidad, x), clase: 'text-right' },
            { titulo: 'Surtido', celda: (x) => cantidad(x.surtido, x), clase: 'text-right' },
            { titulo: 'En vale', celda: (x) => cantidad(x.enVale, x), clase: 'text-right' },
            { titulo: 'Apartado', celda: (x) => cantidad(x.reservado, x), clase: 'text-right' },
            {
              titulo: 'Por llegar',
              celda: (x) => cantidad(x.porRecibir, x),
              clase: 'text-right',
            },
            {
              titulo: 'Falta comprar',
              celda: (x) =>
                x.faltante > 0 ? (
                  <span className="font-medium text-peligro">{cantidad(x.faltante, x)}</span>
                ) : (
                  '—'
                ),
              clase: 'text-right',
            },
            {
              titulo: 'Libre en almacén',
              celda: (x) => cantidad(x.disponible, x),
              clase: 'text-right text-tenue',
            },
          ]}
        />
      </Tarjeta>

      <div className="grid gap-6 md:grid-cols-2">
        <Tarjeta className="p-0">
          <h2 className="px-4 pt-4 font-medium">Vales</h2>
          <Tabla
            filas={r.vales}
            clave={(v) => v.id}
            vacio="Todavía no se surte."
            columnas={[
              {
                titulo: 'Folio',
                celda: (v) => (
                  <Link
                    to="/movimientos/$id"
                    params={{ id: String(v.id) }}
                    className="font-mono text-marca hover:underline"
                  >
                    {v.folio}
                  </Link>
                ),
              },
              { titulo: 'Fecha', celda: (v) => formatearDia(v.fecha) },
              { titulo: 'Renglones', celda: (v) => v.renglones, clase: 'text-right' },
              { titulo: 'Estado', celda: (v) => <TonoEstado estado={v.estado} /> },
            ]}
          />
        </Tarjeta>
        <Tarjeta className="p-0">
          <h2 className="flex items-center gap-2 px-4 pt-4 font-medium">
            <Truck className="size-4" /> Pedidos a proveedor
          </h2>
          <Tabla
            filas={r.pedidos}
            clave={(p) => p.id}
            vacio="No se ha pedido nada para esta requisición."
            columnas={[
              {
                titulo: 'Folio',
                celda: (p) => (
                  <Link
                    to="/pedidos/$id"
                    params={{ id: String(p.id) }}
                    className="font-mono text-marca hover:underline"
                  >
                    {p.folio}
                  </Link>
                ),
              },
              { titulo: 'Cubre', celda: (p) => formatearNumero(p.cantidad), clase: 'text-right' },
              {
                titulo: 'Recibido',
                celda: (p) => formatearNumero(p.recibido),
                clase: 'text-right',
              },
              { titulo: 'Estado', celda: (p) => <EstadoPedido estado={p.estado} /> },
            ]}
          />
        </Tarjeta>
      </div>
    </div>
  )
}

const cantidad = (valor: number, r: RenglonRequisicion) =>
  valor === 0 ? '—' : `${formatearNumero(valor)} ${r.unidadControl}`

/** El vale que surte la requisición: lo apartado primero y lo libre después. */
function FormularioSurtido({
  requisicion,
  alTerminar,
  alCancelar,
}: {
  requisicion: Requisicion
  alTerminar: (movimientoId: number) => void
  alCancelar: () => void
}) {
  const pendientes = requisicion.renglones.filter((x) => x.porSurtir > 0)
  const [fecha, setFecha] = useState(hoy())
  const [observaciones, setObservaciones] = useState('')
  const [cantidades, setCantidades] = useState<Record<number, string>>(() => {
    const sugerido = sugerirSurtido(pendientes)
    return Object.fromEntries(pendientes.map((x) => [x.id, String(sugerido.get(x.id) ?? 0)]))
  })
  const [aviso, setAviso] = useState<string>()
  const surtir = useMutation({
    mutationFn: async () => {
      const renglones = pendientes
        .map((x) => ({ requisicionLineaId: x.id, cantidad: numero(cantidades[x.id]) ?? 0 }))
        .filter((x) => x.cantidad > 0)
      return exigir(
        await api.POST('/api/v1/requisiciones/{id}/surtir', {
          params: { path: { id: requisicion.id } },
          body: { fecha, observaciones: observaciones.trim() || undefined, renglones },
        }),
      )
    },
    onSuccess: (vale) => alTerminar(vale.movimientoId),
  })
  const total = pendientes.reduce((suma, x) => suma + (numero(cantidades[x.id]) ?? 0), 0)

  return (
    <Tarjeta className="flex flex-col gap-4">
      <div>
        <h2 className="font-medium">Surtir con un vale</h2>
        <p className="text-sm text-tenue">
          El vale sale con el contrato, lote, paquete y razón de cada renglón y va a autorizar. Lo
          apartado para la requisición se consume al aplicarlo.
        </p>
      </div>
      <div className="grid gap-4 md:grid-cols-3">
        <Campo
          etiqueta="Fecha del vale"
          type="date"
          max={hoy()}
          value={fecha}
          onChange={(e) => setFecha(e.target.value)}
        />
        <div className="md:col-span-2">
          <Campo
            etiqueta="Observaciones"
            value={observaciones}
            onChange={(e) => setObservaciones(e.target.value)}
          />
        </div>
      </div>
      <div className="flex flex-col gap-2">
        {pendientes.map((x) => (
          <div key={x.id} className="grid items-end gap-3 md:grid-cols-12">
            <p className="text-sm md:col-span-7">
              <span className="font-mono">{x.unidadObra}</span> · {x.material}
              <span className="block text-xs text-tenue">
                Faltan {formatearNumero(x.porSurtir)} · apartado {formatearNumero(x.reservado)} ·
                libre {formatearNumero(x.disponible)} {x.unidadControl}
              </span>
            </p>
            <div className="md:col-span-3">
              <Campo
                etiqueta={`Cantidad (${x.unidadControl})`}
                inputMode="decimal"
                value={cantidades[x.id] ?? ''}
                onChange={(e) => setCantidades({ ...cantidades, [x.id]: e.target.value })}
              />
            </div>
          </div>
        ))}
      </div>
      {aviso && <Aviso>{aviso}</Aviso>}
      {surtir.isError && <Aviso>{mensajeDe(surtir.error)}</Aviso>}
      <div className="flex gap-2">
        <Boton
          disabled={surtir.isPending}
          onClick={() => {
            if (total <= 0) {
              setAviso('Escribe cuánto sale de al menos un renglón.')
              return
            }
            setAviso(undefined)
            surtir.mutate()
          }}
        >
          Crear el vale
        </Boton>
        <Boton variante="fantasma" onClick={alCancelar}>
          No
        </Boton>
      </div>
    </Tarjeta>
  )
}

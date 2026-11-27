import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Ban, PackagePlus, Pencil, Printer, Send } from 'lucide-react'
import { useState } from 'react'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Tarjeta } from '@/components/ui/tarjeta'
import { siCoincide } from '@/features/estructura/consultas'
import { formatearDia, hoy } from '@/features/inventario/consultas'
import { TonoEstado } from '@/features/inventario/PantallaMovimientos'
import { formatearFecha, formatearMoneda, formatearNumero } from '@/lib/formato'
import { numero } from '@/lib/valores'
import { pendienteEnCaptura } from './calculos'
import { Avance, Dato, EstadoPedido } from './Comunes'
import {
  consultasAbastecimiento,
  type DatosPedido,
  type Pedido,
  type ResultadoRecepcion,
} from './consultas'
import { EditorPedido } from './EditorPedido'

/** Un pedido con lo recibido, lo que cubre de cada requisición y sus recepciones. */
export function PantallaPedido({ id }: { id: number }) {
  const cliente = useQueryClient()
  const consulta = useQuery(consultasAbastecimiento.pedido(id))
  const [modo, setModo] = useState<'editar' | 'recibir' | 'cerrar' | 'cancelar'>()
  const [motivo, setMotivo] = useState('')
  const [recibido, setRecibido] = useState<ResultadoRecepcion>()

  const despues = (p: Pedido) => {
    cliente.setQueryData(consultasAbastecimiento.pedido(id).queryKey, p)
    void cliente.invalidateQueries({ queryKey: ['pedidos'] })
    void cliente.invalidateQueries({ queryKey: ['faltantes'] })
    void cliente.invalidateQueries({ queryKey: ['requisiciones'] })
    setModo(undefined)
    setMotivo('')
  }
  const enviar = useMutation({
    mutationFn: async () =>
      exigir(await api.POST('/api/v1/pedidos/{id}/enviar', { params: { path: { id } } })),
    onSuccess: despues,
  })
  const terminar = useMutation({
    mutationFn: async (que: 'cerrar' | 'cancelar') =>
      exigir(
        await api.POST(`/api/v1/pedidos/{id}/${que}`, {
          params: { path: { id } },
          body: { motivo },
        }),
      ),
    onSuccess: despues,
  })
  const guardar = useMutation({
    mutationFn: async ({ datos, version }: { datos: DatosPedido; version: number }) =>
      exigir(
        await api.PUT('/api/v1/pedidos/{id}', {
          params: { path: { id }, header: siCoincide(version) },
          body: datos,
        }),
      ),
    onSuccess: despues,
  })

  if (consulta.isError) return <Aviso>{mensajeDe(consulta.error)}</Aviso>
  const p = consulta.data
  if (!p) return <p className="text-tenue">Cargando…</p>
  const ocupado = enviar.isPending || terminar.isPending
  const error = enviar.error ?? terminar.error

  if (modo === 'editar') {
    return (
      <div className="flex max-w-6xl flex-col gap-6">
        <EditorPedido
          titulo={`Corregir ${p.folio}`}
          inicial={{
            proveedorId: p.proveedorId,
            almacenId: p.almacenId,
            fechaEntrega: p.fechaEntrega,
            observaciones: p.observaciones,
            renglones: p.renglones.map((r) => ({
              materialId: r.materialId,
              material: r.material,
              unidad: r.unidadControl,
              cantidad: r.cantidadCaptura,
              unidadId: r.unidadCapturaId,
              precio: r.precio ?? undefined,
              tasaIva: r.tasaIva,
              requisicionLineaIds: r.requisiciones.map((c) => c.requisicionLineaId),
              cubre: r.requisiciones.map((c) => `${c.folio} ${c.unidadObra}`),
            })),
          }}
          guardando={guardar.isPending}
          error={guardar.isError ? mensajeDe(guardar.error) : undefined}
          alGuardar={(datos) => guardar.mutate({ datos, version: p.version })}
          alCancelar={() => setModo(undefined)}
        />
      </div>
    )
  }

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <Link to="/pedidos" className="text-sm text-marca hover:underline print:hidden">
            ← Pedidos
          </Link>
          <h1 className="text-2xl font-semibold">
            Pedido <span className="font-mono">{p.folio}</span>
          </h1>
          <p className="flex flex-wrap items-center gap-2 text-tenue">
            <EstadoPedido estado={p.estado} /> {p.proveedor} · llega a {p.almacen}
            {p.fechaEntrega && ` · entrega ${formatearDia(p.fechaEntrega)}`}
          </p>
        </div>
        <div className="flex flex-wrap gap-2 print:hidden">
          {p.acciones.editar && (
            <Boton variante="secundario" onClick={() => setModo('editar')}>
              <Pencil className="size-4" /> Corregir
            </Boton>
          )}
          {p.acciones.enviar && (
            <Boton disabled={ocupado} onClick={() => enviar.mutate()}>
              <Send className="size-4" /> Marcar enviado
            </Boton>
          )}
          {p.acciones.recibir && (
            <Boton disabled={ocupado} onClick={() => setModo('recibir')}>
              <PackagePlus className="size-4" /> Recibir
            </Boton>
          )}
          {p.acciones.cerrar && (
            <Boton variante="fantasma" disabled={ocupado} onClick={() => setModo('cerrar')}>
              Cerrar con lo recibido
            </Boton>
          )}
          {p.acciones.cancelar && (
            <Boton variante="fantasma" disabled={ocupado} onClick={() => setModo('cancelar')}>
              <Ban className="size-4" /> Cancelar
            </Boton>
          )}
          <Boton variante="fantasma" onClick={() => window.print()}>
            <Printer className="size-4" /> Imprimir
          </Boton>
        </div>
      </header>

      {error && <Aviso>{mensajeDe(error)}</Aviso>}
      {recibido && (
        <Aviso tipo="exito">
          Se registró la recepción{' '}
          <Link
            to="/movimientos/$id"
            params={{ id: String(recibido.movimientoId) }}
            className="font-mono underline"
          >
            {recibido.folio}
          </Link>
          .
          {recibido.apartados.length > 0 &&
            ` Quedó apartado: ${recibido.apartados
              .map(
                (a) =>
                  `${formatearNumero(a.cantidad)} ${a.material} para ${a.folio} ${a.unidadObra}`,
              )
              .join('; ')}.`}
        </Aviso>
      )}

      {(modo === 'cerrar' || modo === 'cancelar') && (
        <Tarjeta className="flex flex-col gap-3 md:flex-row md:items-end">
          <div className="flex-1">
            <Campo
              etiqueta={
                modo === 'cerrar'
                  ? '¿Por qué se cierra? Lo que no llegó vuelve a faltantes.'
                  : '¿Por qué se cancela?'
              }
              autoFocus
              value={motivo}
              onChange={(e) => setMotivo(e.target.value)}
            />
          </div>
          <Boton disabled={!motivo.trim() || ocupado} onClick={() => terminar.mutate(modo)}>
            Confirmar
          </Boton>
          <Boton variante="fantasma" onClick={() => setModo(undefined)}>
            No
          </Boton>
        </Tarjeta>
      )}

      {modo === 'recibir' && (
        <FormularioRecepcion
          pedido={p}
          alTerminar={(resultado) => {
            setRecibido(resultado)
            despues(resultado.pedido)
            void cliente.invalidateQueries({ queryKey: ['movimientos'] })
            void cliente.invalidateQueries({ queryKey: ['existencias'] })
          }}
          alCancelar={() => setModo(undefined)}
        />
      )}

      <Tarjeta>
        <dl className="grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
          <Dato titulo="Capturó" valor={`${p.capturo} · ${formatearFecha(p.creado)}`} />
          <Dato
            titulo="Envió"
            valor={
              p.envio ? `${p.envio}${p.enviadoEn ? ` · ${formatearFecha(p.enviadoEn)}` : ''}` : null
            }
          />
          <Dato titulo="Subtotal" valor={p.subtotal == null ? null : formatearMoneda(p.subtotal)} />
          <Dato titulo="IVA" valor={p.iva == null ? null : formatearMoneda(p.iva)} />
          <Dato titulo="Total" valor={p.total == null ? null : formatearMoneda(p.total)} />
          <Dato titulo="Cierre" valor={p.motivoCierre} />
          <Dato titulo="Observaciones" valor={p.observaciones} />
          <div>
            <dt className="text-tenue">Recibido</dt>
            <dd>
              <Avance valor={p.avance} />
            </dd>
          </div>
        </dl>
      </Tarjeta>

      <Tarjeta className="p-0">
        <Tabla
          filas={p.renglones}
          clave={(r) => r.id}
          columnas={[
            { titulo: '#', celda: (r) => r.renglon, clase: 'text-right' },
            {
              titulo: 'Material',
              celda: (r) => (
                <span>
                  {r.material}
                  {r.requisiciones.length > 0 && (
                    <span className="block text-xs text-tenue">
                      Para{' '}
                      {r.requisiciones
                        .map((c) => `${c.folio} ${c.unidadObra} (${formatearNumero(c.cantidad)})`)
                        .join(', ')}
                    </span>
                  )}
                </span>
              ),
            },
            {
              titulo: 'Pedido',
              celda: (r) => `${formatearNumero(r.cantidadCaptura)} ${r.unidadCaptura}`,
              clase: 'text-right whitespace-nowrap',
            },
            {
              titulo: 'Precio',
              celda: (r) => (r.precio == null ? '—' : formatearMoneda(r.precio)),
              clase: 'text-right',
            },
            {
              titulo: 'IVA',
              celda: (r) => `${formatearNumero(r.tasaIva * 100)}%`,
              clase: 'text-right',
            },
            {
              titulo: 'Importe',
              celda: (r) => (r.importe == null ? '—' : formatearMoneda(r.importe)),
              clase: 'text-right',
            },
            {
              titulo: 'Recibido',
              celda: (r) => `${formatearNumero(r.recibido)} ${r.unidadControl}`,
              clase: 'text-right whitespace-nowrap',
            },
            {
              titulo: 'Falta',
              celda: (r) =>
                r.pendiente > 0 ? `${formatearNumero(r.pendiente)} ${r.unidadControl}` : '—',
              clase: 'text-right whitespace-nowrap',
            },
          ]}
        />
      </Tarjeta>

      <Tarjeta className="p-0">
        <h2 className="px-4 pt-4 font-medium">Recepciones</h2>
        <Tabla
          filas={p.recepciones}
          clave={(r) => r.id}
          vacio="Todavía no llega nada."
          columnas={[
            {
              titulo: 'Folio',
              celda: (r) => (
                <Link
                  to="/movimientos/$id"
                  params={{ id: String(r.id) }}
                  className="font-mono text-marca hover:underline"
                >
                  {r.folio}
                </Link>
              ),
            },
            { titulo: 'Fecha', celda: (r) => formatearDia(r.fecha) },
            { titulo: 'Estado', celda: (r) => <TonoEstado estado={r.estado} /> },
          ]}
        />
      </Tarjeta>
    </div>
  )
}

/** Lo que llegó, renglón por renglón, en la unidad en que se pidió. Se aplica de una vez. */
function FormularioRecepcion({
  pedido,
  alTerminar,
  alCancelar,
}: {
  pedido: Pedido
  alTerminar: (resultado: ResultadoRecepcion) => void
  alCancelar: () => void
}) {
  const pendientes = pedido.renglones.filter((r) => r.pendiente > 0)
  const [fecha, setFecha] = useState(hoy())
  const [folioFactura, setFolioFactura] = useState('')
  const [flete, setFlete] = useState('')
  const [cantidades, setCantidades] = useState<Record<number, string>>(() =>
    Object.fromEntries(pendientes.map((r) => [r.id, String(pendienteEnCaptura(r))])),
  )
  const [aviso, setAviso] = useState<string>()
  const recibir = useMutation({
    mutationFn: async () =>
      exigir(
        await api.POST('/api/v1/pedidos/{id}/recibir', {
          params: { path: { id: pedido.id } },
          body: {
            fecha,
            folioFactura: folioFactura.trim() || undefined,
            flete: numero(flete),
            renglones: pendientes
              .map((r) => ({ pedidoLineaId: r.id, cantidad: numero(cantidades[r.id]) ?? 0 }))
              .filter((r) => r.cantidad > 0),
          },
        }),
      ),
    onSuccess: alTerminar,
  })
  const algo = pendientes.some((r) => (numero(cantidades[r.id]) ?? 0) > 0)

  return (
    <Tarjeta className="flex flex-col gap-4">
      <div>
        <h2 className="font-medium">Recibir contra el pedido</h2>
        <p className="text-sm text-tenue">
          Se registra y aplica la recepción con el precio del pedido; lo que llega se aparta para
          las requisiciones que cubre, la más urgente primero.
        </p>
      </div>
      <div className="grid gap-4 md:grid-cols-3">
        <Campo
          etiqueta="Fecha"
          type="date"
          max={hoy()}
          value={fecha}
          onChange={(e) => setFecha(e.target.value)}
        />
        <Campo
          etiqueta="Folio de la factura"
          value={folioFactura}
          onChange={(e) => setFolioFactura(e.target.value)}
        />
        <Campo
          etiqueta="Flete ($, sin IVA)"
          inputMode="decimal"
          value={flete}
          onChange={(e) => setFlete(e.target.value)}
        />
      </div>
      <div className="flex flex-col gap-2">
        {pendientes.map((r) => (
          <div key={r.id} className="grid items-end gap-3 md:grid-cols-12">
            <p className="text-sm md:col-span-7">
              {r.material}
              <span className="block text-xs text-tenue">
                Faltan {formatearNumero(pendienteEnCaptura(r))} {r.unidadCaptura}
              </span>
            </p>
            <div className="md:col-span-3">
              <Campo
                etiqueta={`Llegó (${r.unidadCaptura})`}
                inputMode="decimal"
                value={cantidades[r.id] ?? ''}
                onChange={(e) => setCantidades({ ...cantidades, [r.id]: e.target.value })}
              />
            </div>
          </div>
        ))}
      </div>
      {aviso && <Aviso>{aviso}</Aviso>}
      {recibir.isError && <Aviso>{mensajeDe(recibir.error)}</Aviso>}
      <div className="flex gap-2">
        <Boton
          disabled={recibir.isPending}
          onClick={() => {
            if (!algo) {
              setAviso('Escribe lo que llegó de al menos un renglón.')
              return
            }
            setAviso(undefined)
            recibir.mutate()
          }}
        >
          Registrar recepción
        </Boton>
        <Boton variante="fantasma" onClick={alCancelar}>
          No
        </Boton>
      </div>
    </Tarjeta>
  )
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Ban, Check, Pencil, Printer, Send, Undo2, X } from 'lucide-react'
import { useState } from 'react'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Tarjeta } from '@/components/ui/tarjeta'
import { formatearFecha, formatearMoneda, formatearNumero } from '@/lib/formato'
import {
  consultasInventario,
  formatearDia,
  nombreTipo,
  type LineaMovimiento,
  type Movimiento,
} from './consultas'
import { TonoEstado } from './PantallaMovimientos'

type Accion = 'enviar' | 'autorizar' | 'aplicar'

/**
 * Un documento con sus líneas y lo que el usuario puede hacer con él: corregir, enviar,
 * autorizar o rechazar, aplicar, cancelar e imprimir el vale para firma.
 */
export function PantallaMovimiento({ id }: { id: number }) {
  const cliente = useQueryClient()
  const navegar = useNavigate()
  const movimiento = useQuery(consultasInventario.movimiento(id))
  const [nota, setNota] = useState<string>()
  const [motivo, setMotivo] = useState<string>()

  const despues = (m: Movimiento) => {
    cliente.setQueryData(consultasInventario.movimiento(id).queryKey, m)
    void cliente.invalidateQueries({ queryKey: ['movimientos'] })
    void cliente.invalidateQueries({ queryKey: ['existencias'] })
    void cliente.invalidateQueries({ queryKey: ['kardex'] })
    void cliente.invalidateQueries({ queryKey: ['prestamos'] })
    setNota(undefined)
    setMotivo(undefined)
  }

  const accion = useMutation({
    mutationFn: async (que: Accion) =>
      exigir(await api.POST(`/api/v1/movimientos/{id}/${que}`, { params: { path: { id } } })),
    onSuccess: despues,
  })
  const rechazar = useMutation({
    mutationFn: async (texto: string) =>
      exigir(
        await api.POST('/api/v1/movimientos/{id}/rechazar', {
          params: { path: { id } },
          body: { nota: texto },
        }),
      ),
    onSuccess: despues,
  })
  const cancelar = useMutation({
    mutationFn: async (texto: string) =>
      exigir(
        await api.POST('/api/v1/movimientos/{id}/cancelar', {
          params: { path: { id } },
          body: { motivo: texto },
        }),
      ),
    onSuccess: despues,
  })

  if (movimiento.isError) return <Aviso>{mensajeDe(movimiento.error)}</Aviso>
  const m = movimiento.data
  if (!m) return <p className="text-tenue">Cargando…</p>
  const ocupado = accion.isPending || rechazar.isPending || cancelar.isPending
  const error = accion.error ?? rechazar.error ?? cancelar.error
  const conObra = m.lineas.some((l) => l.contratoId != null)
  const conCosto = m.lineas.some((l) => l.importe != null)
  const conPrecio = m.lineas.some((l) => l.precio != null)

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <Link to="/movimientos" className="text-sm text-marca hover:underline print:hidden">
            ← Documentos
          </Link>
          <h1 className="text-2xl font-semibold">
            {nombreTipo(m.tipo)} <span className="font-mono">{m.folio}</span>
          </h1>
          <p className="flex items-center gap-2 text-tenue">
            <TonoEstado estado={m.estado} /> {formatearDia(m.fecha)} · {m.almacen}
            {m.almacenDestino && ` → ${m.almacenDestino}`}
          </p>
        </div>
        <div className="flex flex-wrap gap-2 print:hidden">
          {m.acciones.editar && (
            <Boton
              variante="secundario"
              onClick={() =>
                navegar({ to: '/movimientos/$id/editar', params: { id: String(m.id) } })
              }
            >
              <Pencil className="size-4" /> Corregir
            </Boton>
          )}
          {m.acciones.enviar && (
            <Boton disabled={ocupado} onClick={() => accion.mutate('enviar')}>
              <Send className="size-4" /> Enviar a autorizar
            </Boton>
          )}
          {m.acciones.autorizar && (
            <>
              <Boton disabled={ocupado} onClick={() => accion.mutate('autorizar')}>
                <Check className="size-4" /> Autorizar
              </Boton>
              <Boton variante="secundario" disabled={ocupado} onClick={() => setNota('')}>
                <X className="size-4" /> Rechazar
              </Boton>
            </>
          )}
          {m.acciones.aplicar && (
            <Boton disabled={ocupado} onClick={() => accion.mutate('aplicar')}>
              <Check className="size-4" /> {m.tipo === 'salida' ? 'Entregar y aplicar' : 'Aplicar'}
            </Boton>
          )}
          {m.estado === 'aplicado' && (m.tipo === 'salida' || m.tipo === 'reasignacion') && (
            <Link
              to="/movimientos/nuevo/$tipo"
              params={{ tipo: 'devolucion' }}
              search={{ origen: m.id }}
              className="inline-flex min-h-11 items-center gap-2 rounded-md border border-borde bg-white px-4 text-sm font-medium hover:bg-fondo"
            >
              <Undo2 className="size-4" /> Devolución
            </Link>
          )}
          {m.acciones.cancelar && (
            <Boton variante="fantasma" disabled={ocupado} onClick={() => setMotivo('')}>
              <Ban className="size-4" /> Cancelar
            </Boton>
          )}
          <Boton variante="fantasma" onClick={() => window.print()}>
            <Printer className="size-4" /> Imprimir
          </Boton>
        </div>
      </header>

      {error && <Aviso>{mensajeDe(error)}</Aviso>}
      {m.notaRechazo && m.estado === 'borrador' && (
        <Aviso>Regresado para corregir: {m.notaRechazo}</Aviso>
      )}

      {nota !== undefined && (
        <Tarjeta className="flex flex-col gap-3 md:flex-row md:items-end">
          <div className="flex-1">
            <Campo
              etiqueta="¿Qué hay que corregir?"
              autoFocus
              value={nota}
              onChange={(e) => setNota(e.target.value)}
            />
          </div>
          <Boton disabled={!nota.trim() || ocupado} onClick={() => rechazar.mutate(nota)}>
            Regresar a quien capturó
          </Boton>
          <Boton variante="fantasma" onClick={() => setNota(undefined)}>
            No
          </Boton>
        </Tarjeta>
      )}
      {motivo !== undefined && (
        <Tarjeta className="flex flex-col gap-3 md:flex-row md:items-end">
          <div className="flex-1">
            <Campo
              etiqueta="¿Por qué se cancela?"
              autoFocus
              ayuda={
                m.estado === 'aplicado'
                  ? m.tipo === 'salida'
                    ? 'Se registra la devolución de lo que queda en obra.'
                    : 'Se registra la devolución al proveedor de lo que queda.'
                  : undefined
              }
              value={motivo}
              onChange={(e) => setMotivo(e.target.value)}
            />
          </div>
          <Boton disabled={!motivo.trim() || ocupado} onClick={() => cancelar.mutate(motivo)}>
            Cancelar el documento
          </Boton>
          <Boton variante="fantasma" onClick={() => setMotivo(undefined)}>
            No
          </Boton>
        </Tarjeta>
      )}

      <Tarjeta>
        <dl className="grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
          <Dato titulo="Capturó" valor={m.capturo} />
          <Dato
            titulo="Autorizó"
            valor={
              m.autorizo
                ? `${m.autorizo}${m.autorizadoEn ? ` · ${formatearFecha(m.autorizadoEn)}` : ''}`
                : undefined
            }
          />
          <Dato
            titulo="Aplicó"
            valor={
              m.aplico
                ? `${m.aplico}${m.aplicadoEn ? ` · ${formatearFecha(m.aplicadoEn)}` : ''}`
                : undefined
            }
          />
          <Dato titulo="Solicitó" valor={m.solicitante} />
          <Dato titulo="Proveedor" valor={m.proveedor} />
          <Dato titulo="Factura" valor={m.folioFactura} />
          <Dato titulo="Flete" valor={m.flete == null ? undefined : formatearMoneda(m.flete)} />
          <Dato titulo="Motivo" valor={m.motivo} />
          <Dato titulo="Cancelación" valor={m.motivoCancelacion} />
          <Dato titulo="Observaciones" valor={m.observaciones} />
          <Dato
            titulo="Importe (sin IVA)"
            valor={m.importe == null ? undefined : formatearMoneda(m.importe)}
          />
          {m.archivoId != null && (
            <div>
              <dt className="text-tenue">Foto</dt>
              <dd>
                <a
                  href={`/api/v1/archivos/${m.archivoId}`}
                  target="_blank"
                  rel="noreferrer"
                  className="text-marca hover:underline"
                >
                  Ver evidencia
                </a>
              </dd>
            </div>
          )}
        </dl>
      </Tarjeta>

      <Tarjeta className="p-0">
        <Tabla
          filas={m.lineas}
          clave={(l) => l.id}
          columnas={[
            { titulo: '#', celda: (l) => l.renglon, clase: 'text-tenue' },
            {
              titulo: 'Material',
              celda: (l) => (
                <>
                  <span className="font-mono text-tenue">{l.materialClave}</span> {l.material}
                </>
              ),
            },
            {
              titulo: 'Capturado',
              celda: (l) => `${formatearNumero(l.cantidadCaptura)} ${l.unidadCaptura}`,
              clase: 'text-right whitespace-nowrap',
            },
            {
              titulo: 'Cantidad',
              celda: (l) => `${formatearNumero(l.cantidad)} ${l.unidadControl}`,
              clase: 'text-right whitespace-nowrap font-medium',
            },
            ...(conObra
              ? [
                  { titulo: 'Contrato', celda: (l: LineaMovimiento) => l.contrato ?? '—' },
                  {
                    titulo: 'Lote',
                    celda: (l: LineaMovimiento) => (
                      <span className="font-mono">{l.unidadObra}</span>
                    ),
                  },
                  { titulo: 'Paquete', celda: (l: LineaMovimiento) => l.paquete ?? '—' },
                  { titulo: 'Razón', celda: (l: LineaMovimiento) => l.clasificacion ?? '—' },
                ]
              : []),
            ...(conPrecio
              ? [
                  {
                    titulo: 'Precio',
                    celda: (l: LineaMovimiento) =>
                      l.precio == null ? '—' : formatearMoneda(l.precio),
                    clase: 'text-right',
                  },
                ]
              : []),
            ...(m.tipo === 'ajuste_costo'
              ? [
                  {
                    titulo: 'Costo nuevo',
                    celda: (l: LineaMovimiento) =>
                      l.costoNuevo == null ? '—' : formatearMoneda(l.costoNuevo),
                    clase: 'text-right',
                  },
                ]
              : []),
            ...(conCosto
              ? [
                  {
                    titulo: 'Costo unit.',
                    celda: (l: LineaMovimiento) =>
                      l.costoUnitario == null ? '—' : formatearMoneda(l.costoUnitario),
                    clase: 'text-right whitespace-nowrap',
                  },
                  {
                    titulo: m.tipo === 'ajuste_costo' ? 'Diferencia' : 'Importe',
                    celda: (l: LineaMovimiento) =>
                      l.importe == null ? '—' : formatearMoneda(l.importe),
                    clase: 'text-right whitespace-nowrap',
                  },
                ]
              : []),
          ]}
        />
      </Tarjeta>

      {m.tipo === 'salida' && (
        <div className="hidden grid-cols-3 gap-8 pt-16 text-center text-sm print:grid">
          <p className="border-t border-texto pt-2">Entregó (almacén)</p>
          <p className="border-t border-texto pt-2">
            Recibió{m.solicitante ? `: ${m.solicitante}` : ''}
          </p>
          <p className="border-t border-texto pt-2">
            Autorizó{m.autorizo ? `: ${m.autorizo}` : ''}
          </p>
        </div>
      )}
    </div>
  )
}

function Dato({ titulo, valor }: { titulo: string; valor: string | null | undefined }) {
  if (!valor) return null
  return (
    <div>
      <dt className="text-tenue">{titulo}</dt>
      <dd>{valor}</dd>
    </div>
  )
}

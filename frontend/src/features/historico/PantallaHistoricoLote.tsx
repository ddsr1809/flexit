import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { ArrowLeft } from 'lucide-react'
import { mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Insignia } from '@/components/ui/insignia'
import { Tarjeta } from '@/components/ui/tarjeta'
import { formatearFecha, formatearMoneda, formatearNumero } from '@/lib/formato'
import { consultasHistorico, semanas, TIPOS_SALIDA } from './consultas'

const moneda = (valor: number | string | undefined | null) =>
  valor === undefined || valor === null ? '—' : formatearMoneda(Number(valor))

/** "2026-10-01" → "octubre de 2026". */
const nombreMes = (valor: string) =>
  new Intl.DateTimeFormat('es-MX', { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(
    new Date(valor),
  )

/**
 * Lo que dice el Excel de un lote (demo de H5): sus vales, sus pagos de destajo y quién estuvo dado
 * de alta en el IMSS en su contrato. Importes y nombres solo para quien tiene permiso.
 */
export function PantallaHistoricoLote({ unidadId }: { unidadId: number }) {
  const historia = useQuery(consultasHistorico.unidad(unidadId))

  if (historia.isPending) return <p className="text-tenue">Cargando…</p>
  if (historia.isError) {
    return (
      <div className="flex max-w-3xl flex-col gap-4">
        <Aviso>{mensajeDe(historia.error)}</Aviso>
        <Link to="/contratos" className="text-marca underline">
          Volver a contratos
        </Link>
      </div>
    )
  }
  const h = historia.data

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header className="flex flex-col gap-2">
        <Link
          to="/contratos/$contratoId"
          params={{ contratoId: String(h.contratoId) }}
          className="flex items-center gap-1 text-sm text-tenue hover:text-texto"
        >
          <ArrowLeft className="size-4" /> {h.contrato}
        </Link>
        <h1 className="text-2xl font-semibold">
          Lote <span className="font-mono">{h.clave}</span> en el Excel
        </h1>
        <p className="text-tenue">
          Lo capturado en los Excel de almacén, destajo y SUA antes de arrancar el sistema.
        </p>
      </header>

      {h.veCostos && (
        <div className="grid gap-4 sm:grid-cols-2">
          <Tarjeta>
            <p className="text-sm text-tenue">Material entregado</p>
            <p className="text-2xl font-semibold">{moneda(h.costoMaterial)}</p>
            <p className="text-sm text-tenue">
              {formatearNumero(h.salidas.length)} renglones · costo promedio sin IVA
            </p>
          </Tarjeta>
          <Tarjeta>
            <p className="text-sm text-tenue">Destajo pagado</p>
            <p className="text-2xl font-semibold">{moneda(h.destajo)}</p>
            <p className="text-sm text-tenue">{formatearNumero(h.pagos.length)} pagos</p>
          </Tarjeta>
        </div>
      )}

      <Tarjeta className="min-w-0 p-0">
        <h2 className="px-4 pt-4 text-lg font-medium">Vales y movimientos de material</h2>
        <Tabla
          filas={h.salidas}
          clave={(s) => s.id}
          vacio="El Excel no tiene salidas para este lote."
          columnas={[
            { titulo: 'Fecha', celda: (s) => formatearFecha(s.fecha) },
            {
              titulo: 'Material',
              celda: (s) => (
                <span className="flex flex-col">
                  <span>{s.material}</span>
                  <span className="text-xs text-tenue">
                    {[TIPOS_SALIDA[s.tipo] ?? s.tipo, s.folio && `folio ${s.folio}`, s.origen]
                      .filter(Boolean)
                      .join(' · ')}
                  </span>
                </span>
              ),
            },
            {
              titulo: 'Cantidad',
              celda: (s) => `${formatearNumero(Number(s.cantidad))} ${s.unidad}`,
              clase: 'text-right whitespace-nowrap',
            },
            {
              titulo: 'Paquete · razón',
              celda: (s) => [s.paquete, s.clasificacion].filter(Boolean).join(' · ') || '—',
            },
            ...(h.vePersonas
              ? [
                  {
                    titulo: 'Solicitante',
                    celda: (s: (typeof h.salidas)[number]) => s.solicitante ?? '—',
                  },
                ]
              : []),
            ...(h.veCostos
              ? [
                  {
                    titulo: 'Importe',
                    celda: (s: (typeof h.salidas)[number]) => (
                      <span className="flex flex-col items-end">
                        <span>{moneda(s.importe)}</span>
                        {s.costoEstimado && <Insignia tono="apagado">estimado</Insignia>}
                      </span>
                    ),
                    clase: 'text-right',
                  },
                ]
              : []),
            {
              titulo: 'Entrega',
              celda: (s) =>
                s.entregado ? (
                  <Insignia tono="exito">Entregado</Insignia>
                ) : (
                  <Insignia tono="apagado">No entregado</Insignia>
                ),
            },
          ]}
        />
      </Tarjeta>

      <Tarjeta className="min-w-0 p-0">
        <h2 className="px-4 pt-4 text-lg font-medium">Pagos de destajo</h2>
        <Tabla
          filas={h.pagos}
          clave={(p) => p.id}
          vacio="El Excel no tiene pagos de destajo para este lote."
          columnas={[
            { titulo: 'Semana', celda: (p) => semanas(p) },
            {
              titulo: 'Concepto',
              celda: (p) => (
                <span className="flex flex-col">
                  <span>{p.concepto ?? p.paquete ?? '—'}</span>
                  <span className="text-xs text-tenue">{p.origen}</span>
                </span>
              ),
            },
            ...(h.vePersonas
              ? [
                  {
                    titulo: 'Trabajador',
                    celda: (p: (typeof h.pagos)[number]) => p.trabajadores.join(', ') || '—',
                  },
                ]
              : []),
            ...(h.veCostos
              ? [
                  {
                    titulo: 'Monto',
                    celda: (p: (typeof h.pagos)[number]) => (
                      <span className="flex flex-col items-end gap-1">
                        <span>{moneda(p.monto)}</span>
                        {p.parcial > 1 && (
                          <span className="text-xs text-tenue">parcial {p.parcial}</span>
                        )}
                      </span>
                    ),
                    clase: 'text-right',
                  },
                ]
              : []),
            {
              titulo: 'Revisar',
              celda: (p) => (
                <span className="flex flex-wrap gap-1">
                  {p.sobreImporte && <Insignia>Sobre importe</Insignia>}
                  {p.montoCompartido && <Insignia tono="apagado">Monto compartido</Insignia>}
                </span>
              ),
            },
          ]}
        />
      </Tarjeta>

      {h.vePersonas && (
        <Tarjeta className="min-w-0 p-0">
          <h2 className="px-4 pt-4 text-lg font-medium">Dados de alta en el IMSS en su contrato</h2>
          <Tabla
            filas={h.altas}
            clave={(a) => `${a.mes}|${a.origen}|${a.persona}`}
            vacio="No hay altas del SUA cargadas para este contrato."
            columnas={[
              {
                titulo: 'Mes',
                celda: (a) => nombreMes(a.mes) + (a.origen === 'anterior' ? ' (SUA anterior)' : ''),
              },
              { titulo: 'Persona', celda: (a) => a.persona },
              { titulo: 'Estatus', celda: (a) => a.estatus ?? '—' },
              {
                titulo: 'Días IMSS',
                celda: (a) => (a.dias === undefined || a.dias === null ? '—' : a.dias),
                clase: 'text-right',
              },
            ]}
          />
        </Tarjeta>
      )}
    </div>
  )
}

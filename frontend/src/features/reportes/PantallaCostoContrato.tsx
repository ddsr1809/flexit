import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Scale } from 'lucide-react'
import { useState } from 'react'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Insignia } from '@/components/ui/insignia'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { DescargaExcel, Semaforo } from '@/features/abastecimiento/Comunes'
import { consultas } from '@/features/estructura/consultas'
import { lotesDeRenglon } from '@/features/inventario/reparto'
import { formatearMoneda, formatearNumero } from '@/lib/formato'
import { numero } from '@/lib/valores'
import { PERMISOS, puedeAlguno, useSesion } from '@/sesion/sesion'
import { consultasReportes, rutaExcel, type CostoContrato, type LoteCosto } from './consultas'

/**
 * Lo que era la hoja del frente: el costo de cada lote por razón, el destajo y el presupuesto, con
 * la conciliación contra la hoja del Excel migrado.
 */
export function PantallaCostoContrato({ id }: { id: number }) {
  const sesion = useSesion()
  const asignaPresupuesto = puedeAlguno(sesion, PERMISOS.presupuestoEditar, PERMISOS.administrar)
  const concilia = puedeAlguno(sesion, PERMISOS.costoVerTodo, PERMISOS.administrar)
  const reporte = useQuery(consultasReportes.costoPorLote(id))

  if (reporte.isError) return <Aviso>{mensajeDe(reporte.error)}</Aviso>
  const c = reporte.data
  if (!c) return <p className="text-tenue">Cargando…</p>

  return (
    <div className="flex max-w-7xl flex-col gap-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <Link to="/reportes" className="text-sm text-marca hover:underline">
            ← Reportes
          </Link>
          <h1 className="text-2xl font-semibold">Costo por lote · {c.contrato}</h1>
          <p className="text-tenue">
            Material del histórico más el del sistema, por razón; el destajo pagado y el presupuesto
            de cada lote.
          </p>
        </div>
        <DescargaExcel ruta={rutaExcel(`/api/v1/reportes/contratos/${id}/lotes`)} />
      </header>

      <TablaLotes costo={c} />

      {asignaPresupuesto && <AsignarPresupuesto contratoId={id} />}
      {concilia && <Conciliacion contratoId={id} />}
    </div>
  )
}

function TablaLotes({ costo }: { costo: CostoContrato }) {
  const columnasRazon = costo.clasificaciones.map((cl, indice) => ({
    titulo: cl.nombre,
    celda: (l: LoteCosto) => {
      const valor = l.porClasificacion[indice] ?? 0
      return valor === 0 ? '—' : formatearMoneda(valor)
    },
    clase: 'text-right whitespace-nowrap',
  }))
  const filas = [...costo.lotes, costo.totales]
  return (
    <Tarjeta className="p-0">
      <Tabla
        filas={filas}
        clave={(l) => l.unidadObraId ?? `total-${l.clave}`}
        vacio="El contrato no tiene lotes."
        columnas={[
          {
            titulo: 'Lote',
            celda: (l) =>
              l.unidadObraId == null ? (
                <strong>{l.clave}</strong>
              ) : (
                <Link
                  to="/historico/lote/$unidadId"
                  params={{ unidadId: String(l.unidadObraId) }}
                  className="font-mono text-marca hover:underline"
                >
                  {l.clave}
                </Link>
              ),
            clase: 'whitespace-nowrap',
          },
          { titulo: 'Prototipo', celda: (l) => l.prototipo ?? '' },
          ...columnasRazon,
          {
            titulo: 'Material',
            celda: (l) => <strong>{formatearMoneda(l.material)}</strong>,
            clase: 'text-right whitespace-nowrap',
          },
          {
            titulo: 'Destajo',
            celda: (l) => (l.destajo === 0 ? '—' : formatearMoneda(l.destajo)),
            clase: 'text-right whitespace-nowrap',
          },
          {
            titulo: 'Total',
            celda: (l) => (
              <span className="inline-flex items-center gap-2">
                {l.estimado && <Insignia tono="apagado">Estimado</Insignia>}
                {formatearMoneda(l.total)}
              </span>
            ),
            clase: 'text-right whitespace-nowrap',
          },
          {
            titulo: 'Presupuesto',
            celda: (l) => (l.presupuesto == null ? '—' : formatearMoneda(l.presupuesto)),
            clase: 'text-right whitespace-nowrap',
          },
          {
            titulo: 'Consumido',
            celda: (l) => <Semaforo semaforo={l.semaforo} porcentaje={l.porcentaje} />,
          },
        ]}
      />
    </Tarjeta>
  )
}

function AsignarPresupuesto({ contratoId }: { contratoId: number }) {
  const cliente = useQueryClient()
  const prototipos = useQuery(consultas.prototipos)
  const lotes = useQuery(consultas.unidades(contratoId))
  const [por, setPor] = useState<'prototipo' | 'lotes'>('prototipo')
  const [prototipoId, setPrototipoId] = useState('')
  const [manzana, setManzana] = useState('')
  const [expresion, setExpresion] = useState('')
  const [monto, setMonto] = useState('')
  const [aviso, setAviso] = useState<string>()
  const delContrato = new Set((lotes.data ?? []).map((l) => l.prototipoId).filter(Boolean))
  const asignar = useMutation({
    mutationFn: async (datos: { prototipoId?: number; unidadIds?: number[]; monto: number }) =>
      exigir(
        await api.PUT('/api/v1/contratos/{id}/presupuesto', {
          params: { path: { id: contratoId } },
          body: datos,
        }),
      ),
    onSuccess: (unidades) => {
      setAviso(undefined)
      setMonto('')
      void cliente.invalidateQueries({ queryKey: ['reportes'] })
      void cliente.invalidateQueries({ queryKey: ['contratos', contratoId, 'unidades'] })
      setResultado(`Presupuesto asignado a ${unidades.length} lote(s).`)
    },
  })
  const [resultado, setResultado] = useState<string>()

  const guardar = () => {
    setResultado(undefined)
    const valor = numero(monto)
    if (valor === undefined || valor < 0) return setAviso('Escribe el monto de material por lote.')
    if (por === 'prototipo') {
      if (!prototipoId) return setAviso('Elige el prototipo.')
      setAviso(undefined)
      asignar.mutate({ prototipoId: Number(prototipoId), monto: valor })
    } else {
      const elegidos = lotesDeRenglon(lotes.data ?? [], manzana, expresion)
      if ('error' in elegidos) return setAviso(elegidos.error)
      setAviso(undefined)
      asignar.mutate({ unidadIds: elegidos.ids, monto: valor })
    }
  }

  return (
    <Tarjeta className="flex flex-col gap-4">
      <div>
        <h2 className="font-medium">Presupuesto de material</h2>
        <p className="text-sm text-tenue">
          Monto por lote, sin IVA. Avisa al 80% y marca rebasado al 100% (solo cuenta la razón
          Programa).
        </p>
      </div>
      <div className="grid gap-4 md:grid-cols-5 md:items-end">
        <Seleccion
          etiqueta="Asignar a"
          opciones={[
            { valor: 'prototipo', texto: 'Todos los lotes de un prototipo' },
            { valor: 'lotes', texto: 'Lotes elegidos' },
          ]}
          value={por}
          onChange={(e) => setPor(e.target.value as 'prototipo' | 'lotes')}
        />
        {por === 'prototipo' ? (
          <Seleccion
            etiqueta="Prototipo"
            vacio="Elige…"
            opciones={(prototipos.data ?? [])
              .filter((p) => delContrato.has(p.id))
              .map((p) => ({ valor: p.id, texto: p.nombre }))}
            value={prototipoId}
            onChange={(e) => setPrototipoId(e.target.value)}
          />
        ) : (
          <>
            <Campo etiqueta="Mza" value={manzana} onChange={(e) => setManzana(e.target.value)} />
            <Campo
              etiqueta="Lotes"
              placeholder="1-18, 20"
              value={expresion}
              onChange={(e) => setExpresion(e.target.value)}
            />
          </>
        )}
        <Campo
          etiqueta="Monto por lote"
          inputMode="decimal"
          value={monto}
          onChange={(e) => setMonto(e.target.value)}
        />
        <Boton disabled={asignar.isPending} onClick={guardar}>
          Asignar
        </Boton>
      </div>
      {(aviso ?? (asignar.isError ? mensajeDe(asignar.error) : undefined)) && (
        <Aviso>{aviso ?? mensajeDe(asignar.error)}</Aviso>
      )}
      {resultado && <Aviso tipo="exito">{resultado}</Aviso>}
    </Tarjeta>
  )
}

function Conciliacion({ contratoId }: { contratoId: number }) {
  const [pedida, setPedida] = useState(false)
  const conciliacion = useQuery({
    ...consultasReportes.conciliacion(contratoId),
    enabled: pedida,
    staleTime: 5 * 60 * 1000,
  })
  const c = conciliacion.data
  const diferencia = c ? c.hojaTotal - c.sistema : 0
  const descripcion = new Map<string, string>(
    (c?.efectos ?? []).map((e) => [e.motivo, e.descripcion]),
  )

  return (
    <Tarjeta className="flex flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="font-medium">Conciliación contra la hoja del frente</h2>
          <p className="text-sm text-tenue">
            Recalcula la hoja del frente con las celdas capturadas del Excel migrado y explica cada
            diferencia con el sistema (IVA, ponderación, salidas sin columna, lote corregido…).
          </p>
        </div>
        <div className="flex gap-2">
          <Boton
            variante="secundario"
            disabled={conciliacion.isFetching}
            onClick={() => (pedida ? void conciliacion.refetch() : setPedida(true))}
          >
            <Scale className="size-4" /> {conciliacion.isFetching ? 'Conciliando…' : 'Conciliar'}
          </Boton>
          {c && <DescargaExcel ruta={rutaExcel(`/api/v1/conciliacion/contratos/${contratoId}`)} />}
        </div>
      </div>
      {conciliacion.isError && <Aviso>{mensajeDe(conciliacion.error)}</Aviso>}
      {c && (
        <>
          <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2 lg:grid-cols-4">
            <div>
              <dt className="text-tenue">Hoja</dt>
              <dd>
                {c.hoja} · {c.razon} · {c.columnasDeLote} columnas de lote
              </dd>
            </div>
            <div>
              <dt className="text-tenue">Sistema (Programa)</dt>
              <dd>{formatearMoneda(c.sistema)}</dd>
            </div>
            <div>
              <dt className="text-tenue">Hoja del Excel</dt>
              <dd>{formatearMoneda(c.hojaTotal)}</dd>
            </div>
            <div>
              <dt className="text-tenue">Diferencia</dt>
              <dd>{formatearMoneda(diferencia)}</dd>
            </div>
          </dl>
          <Tabla
            filas={c.efectos}
            clave={(e) => e.motivo}
            vacio="El sistema y la hoja coinciden."
            columnas={[
              { titulo: 'Motivo', celda: (e) => e.descripcion },
              {
                titulo: 'Efecto en la hoja',
                celda: (e) => formatearMoneda(e.importe),
                clase: 'text-right whitespace-nowrap',
              },
            ]}
          />
          {c.avisos.length > 0 && (
            <ul className="list-disc pl-5 text-sm text-tenue">
              {c.avisos.map((a) => (
                <li key={a}>{a}</li>
              ))}
            </ul>
          )}
          <details>
            <summary className="cursor-pointer text-sm font-medium text-marca">
              Lotes ({c.lotes.length}) y renglones con diferencia ({c.renglones.length}
              {c.recortada ? '+' : ''})
            </summary>
            <div className="mt-3 flex flex-col gap-4">
              <Tabla
                filas={c.lotes.filter((l) => l.efectos.length > 0 || l.redondeo !== 0)}
                clave={(l) => l.clave}
                vacio="Todos los lotes coinciden."
                columnas={[
                  { titulo: 'Lote', celda: (l) => l.clave, clase: 'font-mono' },
                  {
                    titulo: 'Sistema',
                    celda: (l) => formatearMoneda(l.sistema),
                    clase: 'text-right',
                  },
                  {
                    titulo: 'Diferencias',
                    celda: (l) =>
                      l.efectos
                        .map((e) => `${e.descripcion} ${formatearMoneda(e.importe)}`)
                        .join('; '),
                    clase: 'text-tenue',
                  },
                  { titulo: 'Hoja', celda: (l) => formatearMoneda(l.hoja), clase: 'text-right' },
                ]}
              />
              <Tabla
                filas={c.renglones}
                clave={(r) => `${r.filaExcel}-${r.lote}-${r.motivo}`}
                columnas={[
                  { titulo: 'Fila', celda: (r) => r.filaExcel, clase: 'text-right' },
                  { titulo: 'Lote', celda: (r) => r.lote, clase: 'font-mono' },
                  { titulo: 'Material', celda: (r) => r.material ?? '—' },
                  { titulo: 'Motivo', celda: (r) => descripcion.get(r.motivo) ?? r.motivo },
                  {
                    titulo: 'Cantidad',
                    celda: (r) => (r.cantidad == null ? '—' : formatearNumero(r.cantidad)),
                    clase: 'text-right',
                  },
                  {
                    titulo: 'Importe',
                    celda: (r) => formatearMoneda(r.importe),
                    clase: 'text-right',
                  },
                  { titulo: 'Nota', celda: (r) => r.nota ?? '', clase: 'text-tenue' },
                ]}
              />
              {c.recortada && (
                <p className="text-sm text-tenue">
                  Se muestran los primeros renglones; el Excel lleva todos.
                </p>
              )}
            </div>
          </details>
        </>
      )}
    </Tarjeta>
  )
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { ArrowLeft, ChevronLeft, ChevronRight, RefreshCw } from 'lucide-react'
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
import { useDiferido } from '@/lib/diferido'
import { formatearFecha, formatearNumero } from '@/lib/formato'
import {
  ACCIONES,
  consultasMigracion,
  decisionesEnGrupo,
  esHistorico,
  ESTADOS,
  lotesPorManzana,
  origen,
  TAMANO_PAGINA,
  textoCrudo,
  type DecisionCarga,
  type DetalleCarga,
  type FiltroFilas,
  type FilaCargada,
  type IncidenciaCarga,
} from './consultas'
import { BuscarDestino, Decidir } from './Decidir'
import { EstadoCarga } from './PantallaMigracion'
import { Totales } from './Totales'

/**
 * Una carga: conciliación por hoja y por regla, renglones con su cuarentena, y los botones de
 * confirmar y revertir. Cada decisión vuelve a validar la carga completa.
 */
export function PantallaCarga({ cargaId }: { cargaId: number }) {
  const cliente = useQueryClient()
  const carga = useQuery(consultasMigracion.carga(cargaId))
  const [filtro, setFiltro] = useState<FiltroFilas>({ pendientes: false, pagina: 0 })

  const actualizar = (detalle: DetalleCarga) => {
    cliente.setQueryData(consultasMigracion.carga(cargaId).queryKey, detalle)
    void cliente.invalidateQueries({ queryKey: ['importaciones', cargaId, 'filas'] })
    void cliente.invalidateQueries({ queryKey: ['importaciones', cargaId, 'conciliacion'] })
    void cliente.invalidateQueries({ queryKey: ['importaciones', 'lista'] })
    void cliente.invalidateQueries({ queryKey: ['importaciones', 'tipos'] })
    void cliente.invalidateQueries({ queryKey: ['alias'] })
  }

  const decidir = useMutation({
    mutationFn: async (decisiones: DecisionCarga[]) =>
      exigir(
        await api.POST('/api/v1/importaciones/{id}/decisiones', {
          params: { path: { id: cargaId } },
          body: { decisiones },
        }),
      ),
    onSuccess: actualizar,
  })

  if (carga.isPending) return <p className="text-tenue">Cargando…</p>
  if (carga.isError) {
    return (
      <div className="flex max-w-3xl flex-col gap-4">
        <Aviso>{mensajeDe(carga.error)}</Aviso>
        <Link to="/migracion" className="text-marca underline">
          Volver a migración
        </Link>
      </div>
    )
  }
  const detalle = carga.data
  const r = detalle.resumen
  const abierta = r.estado === 'validada'

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header className="flex flex-col gap-2">
        <Link
          to="/migracion"
          className="flex items-center gap-1 text-sm text-tenue hover:text-texto"
        >
          <ArrowLeft className="size-4" /> Migración
        </Link>
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-semibold">
            #{r.id} {r.tipoNombre}
          </h1>
          <EstadoCarga estado={r.estado} />
        </div>
        <p className="text-tenue">
          {r.archivo} · {r.hojas} · subida por {r.usuario} el {formatearFecha(r.creada)} · reglas{' '}
          {r.versionReglas}
          {detalle.confirmadaPor && r.confirmada
            ? ` · confirmada por ${detalle.confirmadaPor} el ${formatearFecha(r.confirmada)}`
            : ''}
          {detalle.revertidaPor && r.revertida
            ? ` · revertida por ${detalle.revertidaPor} el ${formatearFecha(r.revertida)}`
            : ''}
        </p>
        {r.repetida && (
          <Aviso tipo="exito">Este archivo ya estaba cargado: no se creó otra carga.</Aviso>
        )}
      </header>

      <Acciones detalle={detalle} actualizar={actualizar} />
      <Conciliacion
        detalle={detalle}
        alElegirRegla={(regla) => setFiltro({ ...filtro, regla, pagina: 0 })}
      />
      {esHistorico(r.tipo) && <Totales cargaId={cargaId} />}
      <Filas
        cargaId={cargaId}
        abierta={abierta}
        detalle={detalle}
        filtro={filtro}
        setFiltro={setFiltro}
        decidir={(decisiones) => decidir.mutate(decisiones)}
        ocupado={decidir.isPending}
        error={decidir.isError ? mensajeDe(decidir.error) : undefined}
      />
    </div>
  )
}

function Acciones({
  detalle,
  actualizar,
}: {
  detalle: DetalleCarga
  actualizar: (d: DetalleCarga) => void
}) {
  const r = detalle.resumen
  const [revertir, setRevertir] = useState(false)
  const accion = useMutation({
    mutationFn: async (que: 'revalidar' | 'confirmar' | 'revertir') => {
      const opciones = { params: { path: { id: r.id } } }
      switch (que) {
        case 'revalidar':
          return exigir(await api.POST('/api/v1/importaciones/{id}/revalidar', opciones))
        case 'confirmar':
          return exigir(await api.POST('/api/v1/importaciones/{id}/confirmar', opciones))
        case 'revertir':
          return exigir(await api.POST('/api/v1/importaciones/{id}/revertir', opciones))
      }
    },
    onSuccess: (d) => {
      setRevertir(false)
      actualizar(d)
    },
  })
  const creados = Object.entries(detalle.creados)

  return (
    <Tarjeta className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <Resumen detalle={detalle} />
        <div className="ml-auto flex flex-wrap gap-2">
          {r.estado === 'validada' && (
            <>
              <Boton
                variante="secundario"
                disabled={accion.isPending}
                onClick={() => accion.mutate('revalidar')}
                title="Volver a validar con los catálogos y alias de ahora"
              >
                <RefreshCw className="size-4" /> Revalidar
              </Boton>
              <Boton
                disabled={accion.isPending || r.enCuarentena > 0}
                onClick={() => accion.mutate('confirmar')}
                title={r.enCuarentena > 0 ? 'Resuelve primero la cuarentena' : undefined}
              >
                Confirmar carga
              </Boton>
            </>
          )}
          {r.estado !== 'revertida' && !revertir && (
            <Boton variante="secundario" onClick={() => setRevertir(true)}>
              Revertir
            </Boton>
          )}
        </div>
      </div>
      {revertir && (
        <div role="alertdialog" aria-label="Revertir carga" className="flex flex-col gap-3">
          <Aviso>
            {r.estado === 'confirmada'
              ? 'Se borra todo lo que creó esta carga y los alias que se decidieron en ella. Si algo ya lo usa, no se borra nada.'
              : 'La carga se cierra sin escribir nada. El archivo se podrá volver a subir.'}
          </Aviso>
          <div className="flex gap-2">
            <Boton disabled={accion.isPending} onClick={() => accion.mutate('revertir')}>
              Sí, revertir la carga
            </Boton>
            <Boton variante="secundario" onClick={() => setRevertir(false)}>
              Cancelar
            </Boton>
          </div>
        </div>
      )}
      {accion.isError && <Aviso>{mensajeDe(accion.error)}</Aviso>}
      {creados.length > 0 && (
        <p className="text-sm text-tenue">
          Creó:{' '}
          {creados
            .map(([entidad, n]) => `${formatearNumero(n)} ${entidad.replace('_', ' ')}`)
            .join(', ')}
        </p>
      )}
    </Tarjeta>
  )
}

function Resumen({ detalle }: { detalle: DetalleCarga }) {
  const r = detalle.resumen
  const datos: [string, number, string?][] = [
    ['Renglones', r.renglones],
    ['Válidos', r.validos],
    ['Corregidos', r.corregidos],
    ['En cuarentena', r.enCuarentena, r.enCuarentena > 0 ? 'text-peligro' : undefined],
    ['Descartados', r.descartados],
    ['Se crean', r.porCrear],
    ['Ya existen', r.existentes],
  ]
  return (
    <dl className="flex flex-wrap gap-x-6 gap-y-2">
      {datos.map(([titulo, valor, clase]) => (
        <div key={titulo}>
          <dt className="text-xs text-tenue">{titulo}</dt>
          <dd className={`text-lg font-semibold ${clase ?? ''}`}>{formatearNumero(valor)}</dd>
        </div>
      ))}
    </dl>
  )
}

const TONO: Record<string, 'neutro' | 'exito' | 'apagado'> = {
  info: 'apagado',
  advertencia: 'neutro',
  cuarentena: 'neutro',
  bloqueo: 'neutro',
}

function Conciliacion({
  detalle,
  alElegirRegla,
}: {
  detalle: DetalleCarga
  alElegirRegla: (regla: string) => void
}) {
  return (
    <div className="flex flex-col gap-6">
      <Tarjeta className="min-w-0 p-0">
        <h2 className="px-4 pt-4 text-lg font-medium">Conciliación por hoja</h2>
        <Tabla
          filas={detalle.conciliacion}
          clave={(h) => h.hoja}
          columnas={[
            { titulo: 'Hoja', celda: (h) => h.hoja },
            { titulo: 'Leídos', celda: (h) => formatearNumero(h.leidos), clase: 'text-right' },
            {
              titulo: 'Válidos + corregidos',
              celda: (h) => formatearNumero(h.validos + h.corregidos),
              clase: 'text-right',
            },
            {
              titulo: 'Cuarentena',
              celda: (h) => formatearNumero(h.enCuarentena),
              clase: 'text-right',
            },
            {
              titulo: 'Descartados',
              celda: (h) => formatearNumero(h.descartados),
              clase: 'text-right',
            },
            {
              titulo: 'Diferencia',
              celda: (h) => (
                <span className={h.diferencia === 0 ? 'text-exito' : 'font-medium text-peligro'}>
                  {formatearNumero(h.diferencia)}
                </span>
              ),
              clase: 'text-right',
            },
          ]}
        />
      </Tarjeta>
      <Tarjeta className="min-w-0 p-0">
        <h2 className="px-4 pt-4 text-lg font-medium">Reglas aplicadas</h2>
        <Tabla
          filas={detalle.reglas}
          clave={(r) => `${r.regla}|${r.severidad}`}
          vacio="Ninguna regla tuvo que intervenir."
          columnas={[
            {
              titulo: 'Regla',
              celda: (r) => (
                <button
                  type="button"
                  className="text-left font-mono text-xs text-marca underline"
                  title={r.ejemplo}
                  onClick={() => alElegirRegla(r.regla)}
                >
                  {r.regla}
                </button>
              ),
            },
            {
              titulo: 'Tipo',
              celda: (r) => <Insignia tono={TONO[r.severidad]}>{r.severidad}</Insignia>,
            },
            {
              titulo: 'Renglones',
              celda: (r) => formatearNumero(r.renglones),
              clase: 'text-right',
            },
            {
              titulo: 'Pendientes',
              celda: (r) =>
                r.pendientes > 0 ? (
                  <span className="font-medium text-peligro">{formatearNumero(r.pendientes)}</span>
                ) : (
                  '—'
                ),
              clase: 'text-right',
            },
          ]}
        />
      </Tarjeta>
    </div>
  )
}

function Filas({
  cargaId,
  abierta,
  detalle,
  filtro,
  setFiltro,
  decidir,
  ocupado,
  error,
}: {
  cargaId: number
  abierta: boolean
  detalle: DetalleCarga
  filtro: FiltroFilas
  setFiltro: (f: FiltroFilas) => void
  decidir: (decisiones: DecisionCarga[]) => void
  ocupado: boolean
  error?: string
}) {
  const [busqueda, setBusqueda] = useState('')
  const q = useDiferido(busqueda.trim(), 300)
  const consulta = useQuery(consultasMigracion.filas(cargaId, { ...filtro, q: q || undefined }))
  const total = consulta.data?.total ?? 0
  const paginas = Math.max(1, Math.ceil(total / TAMANO_PAGINA))
  const filas = consulta.data?.elementos ?? []
  const reglas = [...new Set(detalle.reglas.map((r) => r.regla))]
  const cambiar = (cambio: Partial<FiltroFilas>) => setFiltro({ ...filtro, ...cambio, pagina: 0 })

  return (
    <Tarjeta className="flex flex-col gap-4">
      <h2 className="text-lg font-medium">Renglones</h2>
      <div className="grid gap-4 md:grid-cols-[1fr_1fr_2fr_auto] md:items-end">
        <Seleccion
          etiqueta="Estado"
          vacio="Todos"
          opciones={['valida', 'corregida', 'cuarentena', 'descartada'].map((e) => ({
            valor: e,
            texto: ESTADOS[e] ?? e,
          }))}
          value={filtro.estado ?? ''}
          onChange={(e) => cambiar({ estado: e.target.value || undefined })}
        />
        <Seleccion
          etiqueta="Regla"
          vacio="Todas"
          opciones={reglas.map((r) => ({ valor: r, texto: r }))}
          value={filtro.regla ?? ''}
          onChange={(e) => cambiar({ regla: e.target.value || undefined })}
        />
        <Campo
          etiqueta="Buscar en el renglón"
          type="search"
          value={busqueda}
          onChange={(e) => {
            setBusqueda(e.target.value)
            setFiltro({ ...filtro, pagina: 0 })
          }}
        />
        <label className="flex min-h-11 items-center gap-2 text-sm">
          <input
            type="checkbox"
            className="size-4"
            checked={filtro.pendientes}
            onChange={(e) => cambiar({ pendientes: e.target.checked })}
          />
          Solo pendientes
        </label>
      </div>

      {abierta && filtro.regla && (
        <EnGrupo regla={filtro.regla} filas={filas} decidir={decidir} ocupado={ocupado} />
      )}
      {error && <Aviso>{error}</Aviso>}
      {consulta.isError && <Aviso>{mensajeDe(consulta.error)}</Aviso>}

      <ol className="flex flex-col gap-3">
        {filas.map((fila) => (
          <Renglon
            key={fila.numero}
            fila={fila}
            abierta={abierta}
            decidir={decidir}
            ocupado={ocupado}
          />
        ))}
        {consulta.data && filas.length === 0 && (
          <li className="py-6 text-center text-tenue">No hay renglones con estos filtros.</li>
        )}
      </ol>

      <div className="flex items-center justify-between text-sm text-tenue">
        <span>
          {formatearNumero(total)} renglones · página {filtro.pagina + 1} de {paginas}
        </span>
        <div className="flex gap-2">
          <Boton
            variante="secundario"
            aria-label="Página anterior"
            disabled={filtro.pagina === 0}
            onClick={() => setFiltro({ ...filtro, pagina: filtro.pagina - 1 })}
          >
            <ChevronLeft className="size-4" />
          </Boton>
          <Boton
            variante="secundario"
            aria-label="Página siguiente"
            disabled={filtro.pagina + 1 >= paginas}
            onClick={() => setFiltro({ ...filtro, pagina: filtro.pagina + 1 })}
          >
            <ChevronRight className="size-4" />
          </Boton>
        </div>
      </div>
    </Tarjeta>
  )
}

/** Decisiones para todos los renglones visibles de una regla. */
function EnGrupo({
  regla,
  filas,
  decidir,
  ocupado,
}: {
  regla: string
  filas: FilaCargada[]
  decidir: (decisiones: DecisionCarga[]) => void
  ocupado: boolean
}) {
  const [motivo, setMotivo] = useState('')
  const aceptables = decisionesEnGrupo(filas, regla, 'ACEPTAR')
  const descartables = decisionesEnGrupo(filas, regla, 'DESCARTAR', { motivo: motivo.trim() })
  const manzanas = regla === 'LOTE_SIN_CONTRATO' ? lotesPorManzana(filas) : []
  if (aceptables.length === 0 && descartables.length === 0 && manzanas.length === 0) return null

  return (
    <section aria-label="Decidir en grupo" className="flex flex-col gap-3 rounded-md bg-fondo p-4">
      <h3 className="text-sm font-medium">En grupo: renglones de esta página con {regla}</h3>
      {aceptables.length > 0 && (
        <div>
          <Boton disabled={ocupado} onClick={() => decidir(aceptables)}>
            Aceptar las {aceptables.length} sugerencias
          </Boton>
        </div>
      )}
      {manzanas.map((grupo) => (
        <div key={`${grupo.frenteId}|${grupo.manzana}`} className="flex flex-col gap-1">
          <p className="text-sm">
            Manzana {grupo.manzana}: {grupo.filas.map((f) => f.normal['clave']).join(', ')}
          </p>
          <BuscarDestino
            entidad="contrato"
            frenteId={grupo.frenteId}
            ocupado={ocupado}
            textoBoton={`Asignar a ${grupo.filas.length} lotes`}
            alElegir={(destinoId) =>
              decidir(decisionesEnGrupo(grupo.filas, regla, 'ELEGIR', { destinoId }))
            }
          />
        </div>
      ))}
      {descartables.length > 0 && (
        <form
          className="grid gap-2 md:grid-cols-[2fr_auto] md:items-end"
          onSubmit={(e) => {
            e.preventDefault()
            decidir(descartables)
          }}
        >
          <Campo
            etiqueta={`Motivo para descartar ${descartables.length} renglones`}
            value={motivo}
            onChange={(e) => setMotivo(e.target.value)}
          />
          <Boton type="submit" variante="secundario" disabled={!motivo.trim() || ocupado}>
            Descartar
          </Boton>
        </form>
      )}
    </section>
  )
}

function Renglon({
  fila,
  abierta,
  decidir,
  ocupado,
}: {
  fila: FilaCargada
  abierta: boolean
  decidir: (decisiones: DecisionCarga[]) => void
  ocupado: boolean
}) {
  const tono =
    fila.estado === 'cuarentena'
      ? 'border-peligro/40'
      : fila.estado === 'descartada'
        ? 'opacity-70'
        : ''
  return (
    <li className={`flex flex-col gap-2 rounded-md border border-borde p-3 ${tono}`}>
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="font-mono text-tenue">#{fila.numero}</span>
        <Insignia
          tono={
            fila.estado === 'cuarentena'
              ? 'neutro'
              : fila.estado === 'descartada'
                ? 'apagado'
                : 'exito'
          }
        >
          {ESTADOS[fila.estado] ?? fila.estado}
        </Insignia>
        <span className="text-tenue">{ACCIONES[fila.accion] ?? fila.accion}</span>
        <span className="text-xs text-tenue">{origen(fila)}</span>
      </div>
      <p className="text-sm break-words">{textoCrudo(fila.crudo)}</p>
      {fila.incidencias.length > 0 && (
        <ul className="flex flex-col gap-2">
          {fila.incidencias.map((incidencia) => (
            <Incidencia
              key={incidencia.regla}
              numero={fila.numero}
              incidencia={incidencia}
              abierta={abierta}
              decidir={decidir}
              ocupado={ocupado}
            />
          ))}
        </ul>
      )}
    </li>
  )
}

function Incidencia({
  numero,
  incidencia,
  abierta,
  decidir,
  ocupado,
}: {
  numero: number
  incidencia: IncidenciaCarga
  abierta: boolean
  decidir: (decisiones: DecisionCarga[]) => void
  ocupado: boolean
}) {
  const resolucion = incidencia.resolucion as
    { opcion?: string; motivo?: string; valor?: string } | undefined
  const puedeDecidir =
    abierta &&
    incidencia.opciones.length > 0 &&
    (incidencia.pendiente || incidencia.severidad === 'advertencia')
  return (
    <li className="flex flex-col gap-2 border-l-2 border-borde pl-3">
      <p className="text-sm">
        <span className="mr-2 font-mono text-xs text-tenue">{incidencia.regla}</span>
        {incidencia.detalle}
      </p>
      {resolucion?.opcion && (
        <p className="text-xs text-exito">
          Decidido: {resolucion.opcion.toLowerCase()}
          {resolucion.valor ? ` (${resolucion.valor})` : ''}
          {resolucion.motivo ? ` — ${resolucion.motivo}` : ''}
          {incidencia.resueltaPor ? ` · ${incidencia.resueltaPor}` : ''}
        </p>
      )}
      {puedeDecidir && !resolucion?.opcion && (
        <Decidir
          numero={numero}
          incidencia={incidencia}
          ocupado={ocupado}
          decidir={(decision) => decidir([decision])}
        />
      )}
    </li>
  )
}

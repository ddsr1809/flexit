import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { AreaTexto } from '@/components/ui/area'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Insignia } from '@/components/ui/insignia'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { siCoincide } from '@/features/estructura/consultas'
import { cn } from '@/lib/clases'
import { formatearFecha } from '@/lib/formato'
import { PERMISOS, puede, useSesion } from '@/sesion/sesion'
import {
  consultasSoporte,
  ESTADOS,
  nombreSeveridad,
  SEVERIDADES,
  TIPOS_NOTA,
  type EstadoIncidente,
  type FiltroIncidentes,
  type Incidente,
  type Severidad,
} from './consultas'

export function InsigniaSeveridad({ severidad }: { severidad: Severidad }) {
  return (
    <span
      className={cn(
        'inline-flex rounded-full px-2 py-0.5 text-xs font-medium',
        severidad === 'critica' && 'bg-peligro/10 text-peligro',
        severidad === 'mayor' && 'bg-marca/10 text-marca',
        severidad === 'menor' && 'bg-fondo text-tenue',
      )}
    >
      {nombreSeveridad(severidad)}
    </span>
  )
}

/**
 * Incidentes del piloto (H9). Quien atiende (administración) ve todos, los clasifica y los cierra;
 * los demás ven los que reportaron, agregan notas y los reabren si sigue pasando. El criterio del
 * hito pide cero críticos abiertos.
 */
export function PantallaIncidentes() {
  const sesion = useSesion()
  const atiende = puede(sesion, PERMISOS.incidenteAtender)
  const [filtro, setFiltro] = useState<FiltroIncidentes>({ estado: 'abierto' })
  const [seleccionado, setSeleccionado] = useState<number>()
  const lista = useQuery(consultasSoporte.incidentes(filtro))

  return (
    <div className="flex max-w-7xl flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold">Incidentes</h1>
        <p className="text-tenue">
          {atiende
            ? 'Lo que se reporta desde el piloto. Los críticos detienen el cierre de H9.'
            : 'Los problemas que reportaste. Para uno nuevo, usa «Reportar un problema».'}
        </p>
      </header>

      <div className="flex flex-wrap gap-4">
        <Seleccion
          etiqueta="Estado"
          vacio="Todos"
          value={filtro.estado ?? ''}
          onChange={(e) =>
            setFiltro({
              ...filtro,
              estado: (e.target.value || undefined) as EstadoIncidente | undefined,
            })
          }
          opciones={Object.entries(ESTADOS).map(([valor, texto]) => ({ valor, texto }))}
        />
        <Seleccion
          etiqueta="Severidad"
          vacio="Todas"
          value={filtro.severidad ?? ''}
          onChange={(e) =>
            setFiltro({
              ...filtro,
              severidad: (e.target.value || undefined) as Severidad | undefined,
            })
          }
          opciones={SEVERIDADES.map((s) => ({ valor: s.clave, texto: s.nombre }))}
        />
      </div>

      {lista.isError && <Aviso>{mensajeDe(lista.error)}</Aviso>}

      <div className="grid gap-6 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <Tarjeta className="p-0">
          <Tabla
            filas={lista.data}
            clave={(i) => i.id}
            seleccionada={seleccionado}
            vacio="No hay incidentes con ese filtro."
            columnas={[
              {
                titulo: 'Folio',
                celda: (i) => (
                  <button
                    type="button"
                    className="text-marca underline"
                    onClick={() => setSeleccionado(i.id)}
                  >
                    {i.folio}
                  </button>
                ),
              },
              { titulo: 'Severidad', celda: (i) => <InsigniaSeveridad severidad={i.severidad} /> },
              { titulo: 'Título', celda: (i) => i.titulo },
              { titulo: 'Almacén', celda: (i) => i.almacen ?? '—' },
              {
                titulo: 'Reportó',
                celda: (i) => `${i.reporto} · ${formatearFecha(i.reportadoEn)}`,
              },
              {
                titulo: 'Estado',
                celda: (i) => (
                  <Insignia tono={i.estado === 'abierto' ? 'neutro' : 'apagado'}>
                    {ESTADOS[i.estado]}
                    {i.reaperturas > 0 ? ` · reabierto ${i.reaperturas}` : ''}
                  </Insignia>
                ),
              },
            ]}
          />
        </Tarjeta>
        {seleccionado ? (
          <Detalle key={seleccionado} id={seleccionado} />
        ) : (
          <p className="text-tenue">Elige un incidente para ver su seguimiento.</p>
        )}
      </div>
    </div>
  )
}

function Detalle({ id }: { id: number }) {
  const cliente = useQueryClient()
  const consulta = useQuery(consultasSoporte.incidente(id))
  const [texto, setTexto] = useState('')
  const [severidad, setSeveridad] = useState<Severidad>()
  const [issue, setIssue] = useState<string>()

  const listo = (datos: Incidente) => {
    cliente.setQueryData(consultasSoporte.incidente(id).queryKey, datos)
    void cliente.invalidateQueries({ queryKey: ['incidentes'] })
    setTexto('')
    setSeveridad(undefined)
    setIssue(undefined)
  }
  const accion = useMutation({
    mutationFn: async (tipo: 'resolver' | 'descartar' | 'reabrir' | 'notas') => {
      const opciones = { params: { path: { id } }, body: { texto: texto.trim() } }
      switch (tipo) {
        case 'resolver':
          return exigir(await api.POST('/api/v1/incidentes/{id}/resolver', opciones))
        case 'descartar':
          return exigir(await api.POST('/api/v1/incidentes/{id}/descartar', opciones))
        case 'reabrir':
          return exigir(await api.POST('/api/v1/incidentes/{id}/reabrir', opciones))
        default:
          return exigir(await api.POST('/api/v1/incidentes/{id}/notas', opciones))
      }
    },
    onSuccess: listo,
  })
  const clasificar = useMutation({
    mutationFn: async (i: Incidente) =>
      exigir(
        await api.PATCH('/api/v1/incidentes/{id}', {
          params: { path: { id }, header: siCoincide(i.version) },
          body: {
            severidad: severidad ?? i.severidad,
            issue: issue === undefined ? i.issue : issue ? Number(issue) : undefined,
            nota: texto.trim() || undefined,
          },
        }),
      ),
    onSuccess: listo,
    onError: () => void consulta.refetch(),
  })

  if (consulta.isError) return <Aviso>{mensajeDe(consulta.error)}</Aviso>
  const i = consulta.data
  if (!i) return <p className="text-tenue">Cargando…</p>
  const abierto = i.estado === 'abierto'
  const conTexto = texto.trim().length > 0
  const ocupado = accion.isPending || clasificar.isPending
  const enviar = (e: FormEvent) => {
    e.preventDefault()
    if (conTexto) accion.mutate('notas')
  }

  return (
    <Tarjeta className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-lg font-semibold">{i.folio}</h2>
        <InsigniaSeveridad severidad={i.severidad} />
        <Insignia tono={abierto ? 'neutro' : 'apagado'}>{ESTADOS[i.estado]}</Insignia>
      </div>
      <div>
        <p className="font-medium">{i.titulo}</p>
        <p className="whitespace-pre-line text-sm">{i.descripcion}</p>
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-tenue">Reportó</dt>
        <dd>
          {i.reporto} · {formatearFecha(i.reportadoEn)}
        </dd>
        {i.almacen && (
          <>
            <dt className="text-tenue">Almacén</dt>
            <dd>{i.almacen}</dd>
          </>
        )}
        {i.documento && (
          <>
            <dt className="text-tenue">Documento</dt>
            <dd>{i.documento}</dd>
          </>
        )}
        {i.pantalla && (
          <>
            <dt className="text-tenue">Pantalla</dt>
            <dd className="break-all">{i.pantalla}</dd>
          </>
        )}
        {i.peticion && (
          <>
            <dt className="text-tenue">Petición</dt>
            <dd className="font-mono text-xs">{i.peticion}</dd>
          </>
        )}
        {i.issue && (
          <>
            <dt className="text-tenue">GitHub</dt>
            <dd>#{i.issue}</dd>
          </>
        )}
        {i.resolucion && (
          <>
            <dt className="text-tenue">{i.estado === 'resuelto' ? 'Resolución' : 'Motivo'}</dt>
            <dd>
              {i.resolucion} · {i.cerro}
            </dd>
          </>
        )}
      </dl>

      {i.puedeAtender && abierto && (
        <div className="grid gap-4 sm:grid-cols-2">
          <Seleccion
            etiqueta="Severidad"
            value={severidad ?? i.severidad}
            onChange={(e) => setSeveridad(e.target.value as Severidad)}
            opciones={SEVERIDADES.map((s) => ({ valor: s.clave, texto: s.nombre }))}
          />
          <Campo
            etiqueta="Issue en GitHub"
            inputMode="numeric"
            pattern="[0-9]*"
            value={issue ?? (i.issue ? String(i.issue) : '')}
            onChange={(e) => setIssue(e.target.value)}
          />
        </div>
      )}

      <form onSubmit={enviar} className="flex flex-col gap-3">
        <AreaTexto
          etiqueta={abierto ? 'Nota, resolución o motivo' : 'Por qué se reabre, o una nota'}
          maxLength={1000}
          value={texto}
          onChange={(e) => setTexto(e.target.value)}
        />
        {(accion.isError || clasificar.isError) && (
          <Aviso>{mensajeDe(accion.error ?? clasificar.error)}</Aviso>
        )}
        <div className="flex flex-wrap gap-2">
          <Boton type="submit" variante="secundario" disabled={!conTexto || ocupado}>
            Agregar nota
          </Boton>
          {i.puedeAtender && abierto && (
            <>
              <Boton
                variante="secundario"
                disabled={ocupado || (severidad === undefined && issue === undefined)}
                onClick={() => clasificar.mutate(i)}
              >
                Guardar severidad
              </Boton>
              <Boton disabled={!conTexto || ocupado} onClick={() => accion.mutate('resolver')}>
                Resolver
              </Boton>
              <Boton
                variante="secundario"
                disabled={!conTexto || ocupado}
                onClick={() => accion.mutate('descartar')}
              >
                Descartar
              </Boton>
            </>
          )}
          {!abierto && (
            <Boton disabled={!conTexto || ocupado} onClick={() => accion.mutate('reabrir')}>
              Reabrir
            </Boton>
          )}
        </div>
      </form>

      <ol className="flex flex-col gap-2 border-t border-borde pt-3 text-sm">
        {i.notas.map((n) => (
          <li key={n.id}>
            <span className="font-medium">{TIPOS_NOTA[n.tipo] ?? n.tipo}</span>
            <span className="text-tenue">
              {' '}
              · {n.usuario} · {formatearFecha(n.creado)}
            </span>
            <p className="whitespace-pre-line">{n.texto}</p>
          </li>
        ))}
        {i.notas.length === 0 && <li className="text-tenue">Sin seguimiento todavía.</li>}
      </ol>
    </Tarjeta>
  )
}

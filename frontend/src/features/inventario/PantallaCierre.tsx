import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Tarjeta } from '@/components/ui/tarjeta'
import type { components } from '@/api/esquema'
import { formatearFecha, formatearMoneda, formatearNumero } from '@/lib/formato'
import { consultasInventario, formatearDia, TIPOS } from './consultas'

type PuntoRevision = components['schemas']['PuntoRevision']

const MESES = [
  'enero',
  'febrero',
  'marzo',
  'abril',
  'mayo',
  'junio',
  'julio',
  'agosto',
  'septiembre',
  'octubre',
  'noviembre',
  'diciembre',
]

const nombreMes = (anio: number, mes: number) => `${MESES[mes - 1]} ${anio}`

const nombreDocumento = (tipo: string) =>
  ({ conteo: 'Conteo', apertura: 'Apertura', prestamo: 'Préstamo', requisicion: 'Requisición' })[
    tipo
  ] ??
  TIPOS[tipo]?.nombre ??
  tipo

/**
 * Cierre de mes (administración). Los meses se cierran en orden, cuando ya terminaron y no les
 * quedan documentos sin aplicar o cancelar, ni conteos o aperturas abiertos (después ya no se
 * podrían cerrar). Antes de cerrar se revisa el mes: lo que lo impide y los avisos que no.
 * Cerrado, no acepta movimientos, queda la foto del inventario valuado y se descarga el acta.
 */
export function PantallaCierre() {
  const cliente = useQueryClient()
  const periodos = useQuery(consultasInventario.periodos)
  const cerrar = useMutation({
    mutationFn: async ({ anio, mes }: { anio: number; mes: number }) =>
      exigir(
        await api.POST('/api/v1/periodos/{anio}/{mes}/cerrar', { params: { path: { anio, mes } } }),
      ),
    onSuccess: (datos) => {
      cliente.setQueryData(consultasInventario.periodos.queryKey, datos)
      void cliente.invalidateQueries({ queryKey: ['revision-cierre'] })
    },
  })

  const datos = periodos.data
  const [anio, mes] = (datos?.siguiente ?? '').split('-').map(Number)
  const revision = useQuery({
    queryKey: ['revision-cierre', anio, mes],
    queryFn: async () =>
      exigir(
        await api.GET('/api/v1/periodos/{anio}/{mes}/revision', {
          params: { path: { anio: anio ?? 0, mes: mes ?? 0 } },
        }),
      ),
    enabled: Boolean(anio && mes && datos?.siguienteTerminado),
  })
  const r = revision.data

  return (
    <div className="flex max-w-4xl flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold">Cierre de mes</h1>
        <p className="text-tenue">Un mes cerrado ya no acepta movimientos y no se reabre.</p>
      </header>

      {periodos.isError && <Aviso>{mensajeDe(periodos.error)}</Aviso>}
      {cerrar.isError && <Aviso>{mensajeDe(cerrar.error)}</Aviso>}

      {datos && anio && mes ? (
        <Tarjeta className="flex flex-col gap-3">
          <h2 className="font-medium">Siguiente por cerrar: {nombreMes(anio, mes)}</h2>
          {!datos.siguienteTerminado ? (
            <p className="text-sm text-tenue">El mes todavía no termina.</p>
          ) : revision.isError ? (
            <Aviso>{mensajeDe(revision.error)}</Aviso>
          ) : !r ? (
            <p className="text-sm text-tenue">Revisando el mes…</p>
          ) : (
            <>
              {r.bloqueos.length > 0 && (
                <Puntos
                  titulo="Lo que impide cerrar"
                  puntos={r.bloqueos}
                  className="border-peligro/30 bg-peligro/5"
                />
              )}
              {r.avisos.length > 0 && (
                <Puntos
                  titulo="Avisos (no impiden cerrar)"
                  puntos={r.avisos}
                  className="border-borde bg-fondo"
                />
              )}
              {r.bloqueos.length > 0 && datos.pendientesSiguiente > 0 && (
                <p className="text-sm">
                  <Link to="/movimientos" className="text-marca underline">
                    Revisa los documentos en Documentos
                  </Link>
                  .
                </p>
              )}
              {r.sePuedeCerrar && (
                <div>
                  <Boton disabled={cerrar.isPending} onClick={() => cerrar.mutate({ anio, mes })}>
                    Cerrar {nombreMes(anio, mes)}
                  </Boton>
                </div>
              )}
            </>
          )}
        </Tarjeta>
      ) : (
        datos && <p className="text-tenue">Todavía no hay movimientos.</p>
      )}

      <Tarjeta className="p-0">
        <Tabla
          filas={datos?.cerrados}
          clave={(p) => `${p.anio}-${p.mes}`}
          vacio="Todavía no se ha cerrado ningún mes."
          columnas={[
            { titulo: 'Mes', celda: (p) => nombreMes(p.anio, p.mes) },
            { titulo: 'Cerró', celda: (p) => `${p.cerro} · ${formatearFecha(p.cerradoEn)}` },
            {
              titulo: 'Valor al cierre',
              celda: (p) =>
                p.valor.length === 0
                  ? '—'
                  : p.valor
                      .map(
                        (v) =>
                          `${v.almacen}: ${v.valor == null ? `${formatearNumero(v.materiales)} materiales` : formatearMoneda(v.valor)}`,
                      )
                      .join(' · '),
            },
            {
              titulo: 'Acta',
              celda: (p) => (
                <a
                  href={`/api/v1/periodos/${p.anio}/${p.mes}/acta.xlsx`}
                  download
                  className="text-marca underline"
                >
                  Excel
                </a>
              ),
            },
          ]}
        />
      </Tarjeta>
    </div>
  )
}

function Puntos({
  titulo,
  puntos,
  className,
}: {
  titulo: string
  puntos: PuntoRevision[]
  className: string
}) {
  return (
    <section className={`rounded-md border p-3 text-sm ${className}`}>
      <h3 className="mb-2 font-medium">{titulo}</h3>
      <ul className="flex flex-col gap-2">
        {puntos.map((p) => (
          <li key={p.codigo}>
            <p>{p.mensaje}</p>
            {p.documentos.length > 0 && (
              <p className="text-tenue">
                {p.documentos
                  .map(
                    (d) =>
                      `${d.folio} (${nombreDocumento(d.tipo)}${d.fecha ? `, ${formatearDia(d.fecha)}` : ''}${d.almacen ? `, ${d.almacen}` : ''})`,
                  )
                  .join(' · ')}
                {p.cuantos > p.documentos.length && ` · y ${p.cuantos - p.documentos.length} más`}
              </p>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}

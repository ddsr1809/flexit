import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { CircleCheck, CircleX } from 'lucide-react'
import { useState } from 'react'
import { mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Campo } from '@/components/ui/campo'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { DescargaExcel } from '@/features/abastecimiento/Comunes'
import { consultasInventario, formatearDia, TIPOS } from '@/features/inventario/consultas'
import { cn } from '@/lib/clases'
import { formatearFecha, formatearNumero } from '@/lib/formato'
import { consultasReportes, rutaExcel, type Estabilizacion } from './consultas'

const nombreTipo = (tipo: string) => TIPOS[tipo]?.nombre ?? tipo
const horas = (valor: number | undefined) =>
  valor === undefined || valor === null ? '—' : `${formatearNumero(valor)} h`

/**
 * Estabilización del frente piloto (H9): si el sistema ya reemplazó al Excel. El criterio pide dos
 * semanas seguidas capturando a tiempo (nada capturado más de un día después de su fecha), cero
 * incidentes críticos abiertos y el primer cierre de mes. Que el Excel no se tocó lo prueba
 * `ensayo.py verificar` con el sha256 congelado.
 */
export function PantallaEstabilizacion() {
  const almacenes = useQuery(consultasInventario.almacenes)
  const [almacen, setAlmacen] = useState<number>()
  const [desde, setDesde] = useState('')
  const [hasta, setHasta] = useState('')
  const elegido = almacen ?? almacenes.data?.find((a) => a.activo)?.id
  const reporte = useQuery({
    ...consultasReportes.estabilizacion(elegido ?? 0, desde || undefined, hasta || undefined),
    enabled: elegido !== undefined,
  })
  const r = reporte.data

  return (
    <div className="flex max-w-7xl flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold">Estabilización del piloto</h1>
        <p className="text-tenue">
          Cómo se usa el almacén desde su apertura y si ya se cumple el criterio para dejar el
          Excel.
        </p>
      </header>

      <div className="flex flex-wrap items-end gap-4">
        <Seleccion
          etiqueta="Almacén"
          value={elegido ?? ''}
          onChange={(e) => setAlmacen(Number(e.target.value))}
          opciones={(almacenes.data ?? []).map((a) => ({ valor: a.id, texto: a.clave }))}
        />
        <Campo
          etiqueta="Desde"
          type="date"
          value={desde}
          onChange={(e) => setDesde(e.target.value)}
          ayuda="Vacío: la apertura"
        />
        <Campo
          etiqueta="Hasta"
          type="date"
          value={hasta}
          onChange={(e) => setHasta(e.target.value)}
          ayuda="Vacío: hoy"
        />
        {elegido !== undefined && (
          <DescargaExcel
            ruta={rutaExcel('/api/v1/reportes/estabilizacion', {
              almacenId: elegido,
              desde: desde || undefined,
              hasta: hasta || undefined,
            })}
          />
        )}
      </div>

      {(almacenes.isError || reporte.isError) && (
        <Aviso>{mensajeDe(almacenes.error ?? reporte.error)}</Aviso>
      )}
      {r && <Reporte r={r} />}
    </div>
  )
}

export function Reporte({ r }: { r: Estabilizacion }) {
  const c = r.criterio
  const i = r.indicadores
  return (
    <>
      <p className="text-sm text-tenue">
        {r.almacen} ·{' '}
        {r.apertura ? `abrió el ${formatearDia(r.apertura)}` : 'todavía no tiene saldo de apertura'}{' '}
        · del {formatearDia(r.desde)} al {formatearDia(r.hasta)}
      </p>

      <section aria-labelledby="criterio" className="flex flex-col gap-3">
        <h2 id="criterio" className="text-lg font-semibold">
          Criterio de H9: {c.cumple ? 'se cumple' : 'todavía no'}
        </h2>
        <div className="grid gap-4 md:grid-cols-3">
          <Punto
            ok={c.dosSemanasATiempo}
            titulo="Dos semanas capturando a tiempo"
            detalle={`${c.racha} de ${c.diasRequeridos} días seguidos sin capturas tardías · ${c.valesEnRacha} vales`}
          />
          <Punto
            ok={c.sinCriticos}
            titulo="Cero incidentes críticos abiertos"
            detalle={c.sinCriticos ? 'Ninguno' : `${c.criticosAbiertos} abiertos`}
          />
          <Punto
            ok={c.primerCierre}
            titulo="Primer cierre de mes"
            detalle={
              c.ultimoMesCerrado
                ? `Último mes cerrado: ${c.ultimoMesCerrado}`
                : 'Sin meses cerrados'
            }
          />
        </div>
        <p className="text-sm text-tenue">
          Tardío: capturado más de {r.toleranciaDias} día después de su fecha. Un día sin documentos
          no rompe la racha. Que el Excel no cambió se comprueba aparte con{' '}
          <code>ensayo.py verificar</code>.
        </p>
      </section>

      <section aria-labelledby="indicadores" className="flex flex-col gap-3">
        <h2 id="indicadores" className="text-lg font-semibold">
          Indicadores del periodo
        </h2>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Dato titulo="Vales aplicados" valor={formatearNumero(i.salidas)} />
          <Dato titulo="Recepciones aplicadas" valor={formatearNumero(i.entradas)} />
          <Dato
            titulo="Capturas tardías"
            valor={`${formatearNumero(i.tardios)} · ${formatearNumero(i.porcentajeTardios)}%`}
            alerta={i.tardios > 0}
          />
          <Dato
            titulo="Cancelados"
            valor={`${formatearNumero(i.cancelados)} · ${formatearNumero(i.porcentajeCancelados)}%`}
          />
          <Dato
            titulo="Sin aplicar (hoy)"
            valor={`${formatearNumero(i.pendientes)} · ${formatearNumero(i.pendientesViejos)} de más de 2 días`}
            alerta={i.pendientesViejos > 0}
          />
          <Dato titulo="Captura a aplicación del vale" valor={horas(i.medianaHorasVale)} />
          <Dato
            titulo="Préstamos abiertos"
            valor={`${formatearNumero(i.prestamosAbiertos)} · ${formatearNumero(i.prestamosVencidos)} vencidos`}
            alerta={i.prestamosVencidos > 0}
          />
          <Dato
            titulo="Incidentes"
            valor={`${formatearNumero(i.incidentesReportados)} reportados · ${formatearNumero(i.incidentesResueltos)} resueltos`}
            detalle={`Abiertos: ${c.criticosAbiertos} críticos, ${i.mayoresAbiertos} mayores, ${i.menoresAbiertos} menores · mediana ${horas(i.medianaHorasResolucion)}`}
          />
        </div>
      </section>

      {r.criticos.length > 0 && (
        <Tarjeta className="flex flex-col gap-2">
          <h2 className="font-semibold">Incidentes críticos abiertos</h2>
          <ul className="text-sm">
            {r.criticos.map((x) => (
              <li key={x.id}>
                {x.folio} · {x.titulo} · {formatearFecha(x.reportadoEn)}
              </li>
            ))}
          </ul>
          <Link to="/incidentes" className="text-sm text-marca underline">
            Ir a Incidentes
          </Link>
        </Tarjeta>
      )}

      <section aria-labelledby="por-dia" className="flex flex-col gap-3">
        <h2 id="por-dia" className="text-lg font-semibold">
          Por día
        </h2>
        <Tarjeta className="p-0">
          <Tabla
            filas={[...r.dias].reverse()}
            clave={(d) => d.fecha}
            columnas={[
              { titulo: 'Fecha', celda: (d) => formatearDia(d.fecha) },
              { titulo: 'Documentos', celda: (d) => formatearNumero(d.documentos) },
              { titulo: 'Vales', celda: (d) => formatearNumero(d.salidas) },
              { titulo: 'Recepciones', celda: (d) => formatearNumero(d.entradas) },
              {
                titulo: 'Tardíos',
                celda: (d) => (
                  <span className={cn(d.tardios > 0 && 'font-medium text-peligro')}>
                    {formatearNumero(d.tardios)}
                  </span>
                ),
              },
              { titulo: 'Cancelados', celda: (d) => formatearNumero(d.cancelados) },
            ]}
          />
        </Tarjeta>
      </section>

      <div className="grid gap-6 lg:grid-cols-2">
        <section aria-labelledby="tardios" className="flex flex-col gap-3">
          <h2 id="tardios" className="text-lg font-semibold">
            Capturas tardías
          </h2>
          <Tarjeta className="p-0">
            <Tabla
              filas={r.tardios}
              clave={(d) => d.folio}
              vacio="Nada se capturó tarde."
              columnas={[
                { titulo: 'Folio', celda: (d) => d.folio },
                { titulo: 'Tipo', celda: (d) => nombreTipo(d.tipo) },
                { titulo: 'Fecha', celda: (d) => formatearDia(d.fecha) },
                { titulo: 'Capturado', celda: (d) => formatearDia(d.capturado) },
                { titulo: 'Días', celda: (d) => formatearNumero(d.dias) },
              ]}
            />
          </Tarjeta>
        </section>
        <section aria-labelledby="pendientes" className="flex flex-col gap-3">
          <h2 id="pendientes" className="text-lg font-semibold">
            Sin aplicar ni cancelar
          </h2>
          <Tarjeta className="p-0">
            <Tabla
              filas={r.pendientes}
              clave={(d) => d.folio}
              vacio="No hay documentos pendientes."
              columnas={[
                { titulo: 'Folio', celda: (d) => d.folio },
                { titulo: 'Tipo', celda: (d) => nombreTipo(d.tipo) },
                { titulo: 'Estado', celda: (d) => d.estado },
                { titulo: 'Fecha', celda: (d) => formatearDia(d.fecha) },
                { titulo: 'Días', celda: (d) => formatearNumero(d.dias) },
              ]}
            />
          </Tarjeta>
        </section>
      </div>
    </>
  )
}

function Punto({ ok, titulo, detalle }: { ok: boolean; titulo: string; detalle: string }) {
  const Icono = ok ? CircleCheck : CircleX
  return (
    <Tarjeta className="flex items-start gap-3 p-4">
      <Icono
        aria-hidden="true"
        className={cn('mt-0.5 size-5 shrink-0', ok ? 'text-exito' : 'text-peligro')}
      />
      <div>
        <p className="font-medium">
          {titulo}
          <span className="sr-only">: {ok ? 'cumple' : 'no cumple'}</span>
        </p>
        <p className="text-sm text-tenue">{detalle}</p>
      </div>
    </Tarjeta>
  )
}

function Dato({
  titulo,
  valor,
  detalle,
  alerta,
}: {
  titulo: string
  valor: string
  detalle?: string
  alerta?: boolean
}) {
  return (
    <Tarjeta className="p-4">
      <p className="text-sm text-tenue">{titulo}</p>
      <p className={cn('text-lg font-semibold', alerta && 'text-peligro')}>{valor}</p>
      {detalle && <p className="text-xs text-tenue">{detalle}</p>}
    </Tarjeta>
  )
}

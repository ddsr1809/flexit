import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useState } from 'react'
import { mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Campo } from '@/components/ui/campo'
import { Insignia } from '@/components/ui/insignia'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { Avance, DescargaExcel, Semaforo } from '@/features/abastecimiento/Comunes'
import { consultasInventario, formatearDia } from '@/features/inventario/consultas'
import { cn } from '@/lib/clases'
import { formatearMoneda, formatearNumero } from '@/lib/formato'
import { PERMISOS, puedeAlguno, useSesion } from '@/sesion/sesion'
import {
  AGRUPACIONES,
  consultasReportes,
  rutaExcel,
  type Agrupacion,
  type FiltroCosto,
} from './consultas'

type Pestana = 'contratos' | 'agrupado' | 'avisos' | 'surtido' | 'inventario'

const PESTANAS: { clave: Pestana; texto: string }[] = [
  { clave: 'contratos', texto: 'Por contrato' },
  { clave: 'agrupado', texto: 'Costo agrupado' },
  { clave: 'avisos', texto: 'Avisos de presupuesto' },
  { clave: 'surtido', texto: 'Requisición vs surtido' },
  { clave: 'inventario', texto: 'Valor del inventario' },
]

/**
 * Los reportes de la junta: costo de material (histórico del Excel + lo capturado en el sistema)
 * y destajo por contrato, lote, paquete y razón, el presupuesto con sus avisos, lo pedido contra
 * lo surtido y el valor del inventario. Todos se descargan en Excel.
 */
export function PantallaReportes() {
  const sesion = useSesion()
  const veCostos = puedeAlguno(sesion, PERMISOS.costoVerPropio, PERMISOS.costoVerTodo)
  const veInventario = puedeAlguno(sesion, PERMISOS.costoVerTodo, PERMISOS.precioVer)
  const disponibles = PESTANAS.filter((p) =>
    p.clave === 'inventario' ? veInventario : p.clave === 'surtido' ? true : veCostos,
  )
  const [pestana, setPestana] = useState<Pestana>(disponibles[0]?.clave ?? 'surtido')

  return (
    <div className="flex max-w-7xl flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold">Reportes</h1>
        <p className="text-tenue">
          El costo suma el histórico migrado del Excel y lo que ya se captura en el sistema; cada
          reporte se descarga en Excel.
        </p>
      </header>
      <nav className="flex flex-wrap gap-1 border-b border-borde" aria-label="Reportes">
        {disponibles.map((p) => (
          <button
            key={p.clave}
            type="button"
            aria-current={pestana === p.clave ? 'page' : undefined}
            onClick={() => setPestana(p.clave)}
            className={cn(
              'min-h-11 border-b-2 px-4 text-sm font-medium',
              pestana === p.clave
                ? 'border-marca text-marca'
                : 'border-transparent text-tenue hover:text-texto',
            )}
          >
            {p.texto}
          </button>
        ))}
      </nav>
      {pestana === 'contratos' && <PorContrato />}
      {pestana === 'agrupado' && <Agrupado />}
      {pestana === 'avisos' && <Avisos />}
      {pestana === 'surtido' && <Surtido />}
      {pestana === 'inventario' && <Inventario />}
    </div>
  )
}

function PorContrato() {
  const reporte = useQuery(consultasReportes.contratos)
  return (
    <section className="flex flex-col gap-4">
      <div className="flex justify-end">
        <DescargaExcel ruta={rutaExcel('/api/v1/reportes/contratos')} />
      </div>
      {reporte.isError && <Aviso>{mensajeDe(reporte.error)}</Aviso>}
      <Tarjeta className="p-0">
        <Tabla
          filas={reporte.data}
          clave={(c) => c.contratoId}
          vacio="No hay contratos a tu alcance."
          columnas={[
            {
              titulo: 'Contrato',
              celda: (c) => (
                <Link
                  to="/reportes/contratos/$id"
                  params={{ id: String(c.contratoId) }}
                  className="text-marca hover:underline"
                >
                  {c.contrato}
                </Link>
              ),
              clase: 'whitespace-nowrap',
            },
            { titulo: 'Frente', celda: (c) => c.frente ?? '—' },
            { titulo: 'Lotes', celda: (c) => c.lotes, clase: 'text-right' },
            {
              titulo: 'Histórico',
              celda: (c) => formatearMoneda(c.historico),
              clase: 'text-right',
            },
            { titulo: 'Sistema', celda: (c) => formatearMoneda(c.sistema), clase: 'text-right' },
            { titulo: 'Material', celda: (c) => formatearMoneda(c.material), clase: 'text-right' },
            { titulo: 'Destajo', celda: (c) => formatearMoneda(c.destajo), clase: 'text-right' },
            {
              titulo: 'Total',
              celda: (c) => <strong>{formatearMoneda(c.total)}</strong>,
              clase: 'text-right',
            },
            {
              titulo: 'Presupuesto',
              celda: (c) => (c.presupuesto == null ? '—' : formatearMoneda(c.presupuesto)),
              clase: 'text-right',
            },
            {
              titulo: 'Consumido',
              celda: (c) => <Semaforo semaforo={c.semaforo} porcentaje={c.porcentaje} />,
            },
            {
              titulo: 'Lotes en aviso',
              celda: (c) =>
                c.lotesEnAviso + c.lotesRebasados === 0
                  ? '—'
                  : `${c.lotesEnAviso} en aviso · ${c.lotesRebasados} rebasados`,
              clase: 'whitespace-nowrap text-tenue',
            },
          ]}
        />
      </Tarjeta>
    </section>
  )
}

function Agrupado() {
  const [filtro, setFiltro] = useState<FiltroCosto>({ agrupar: 'paquete' })
  const contratos = useQuery(consultasReportes.contratos)
  const reporte = useQuery(consultasReportes.costo(filtro))
  const cambiar = (cambio: Partial<FiltroCosto>) => setFiltro({ ...filtro, ...cambio })
  const r = reporte.data
  const conCantidad = filtro.agrupar === 'material'
  return (
    <section className="flex flex-col gap-4">
      <div className="grid gap-4 md:grid-cols-6 md:items-end">
        <Seleccion
          etiqueta="Agrupar por"
          opciones={Object.entries(AGRUPACIONES).map(([valor, texto]) => ({ valor, texto }))}
          value={filtro.agrupar}
          onChange={(e) => cambiar({ agrupar: e.target.value as Agrupacion })}
        />
        <Seleccion
          etiqueta="Contrato"
          vacio="Todos"
          opciones={(contratos.data ?? []).map((c) => ({ valor: c.contratoId, texto: c.contrato }))}
          value={filtro.contratoId ?? ''}
          onChange={(e) =>
            cambiar({ contratoId: e.target.value ? Number(e.target.value) : undefined })
          }
        />
        <Seleccion
          etiqueta="Origen"
          vacio="Histórico y sistema"
          opciones={[
            { valor: 'historico', texto: 'Solo histórico (Excel)' },
            { valor: 'sistema', texto: 'Solo sistema' },
          ]}
          value={filtro.fuente ?? ''}
          onChange={(e) =>
            cambiar({ fuente: (e.target.value || undefined) as FiltroCosto['fuente'] })
          }
        />
        <Campo
          etiqueta="Desde"
          type="date"
          value={filtro.desde ?? ''}
          onChange={(e) => cambiar({ desde: e.target.value || undefined })}
        />
        <Campo
          etiqueta="Hasta"
          type="date"
          value={filtro.hasta ?? ''}
          onChange={(e) => cambiar({ hasta: e.target.value || undefined })}
        />
        <DescargaExcel ruta={rutaExcel('/api/v1/reportes/costo', { ...filtro })} />
      </div>
      {reporte.isError && <Aviso>{mensajeDe(reporte.error)}</Aviso>}
      {r && (
        <p className="text-sm text-tenue">
          Histórico {formatearMoneda(r.historico)} + sistema {formatearMoneda(r.sistema)} ={' '}
          <strong className="text-texto">{formatearMoneda(r.total)}</strong>
        </p>
      )}
      <Tarjeta className="p-0">
        <Tabla
          filas={r?.filas}
          clave={(f) => f.llave ?? `sin-${f.clave}`}
          vacio="No hay costo con esos filtros."
          columnas={[
            { titulo: AGRUPACIONES[filtro.agrupar] ?? 'Grupo', celda: (f) => f.clave },
            { titulo: 'Nombre', celda: (f) => f.nombre },
            ...(conCantidad
              ? [
                  {
                    titulo: 'Cantidad',
                    celda: (f: NonNullable<typeof r>['filas'][number]) =>
                      f.cantidad == null ? '—' : `${formatearNumero(f.cantidad)} ${f.unidad ?? ''}`,
                    clase: 'text-right whitespace-nowrap',
                  },
                ]
              : []),
            {
              titulo: 'Histórico',
              celda: (f) => formatearMoneda(f.historico),
              clase: 'text-right',
            },
            { titulo: 'Sistema', celda: (f) => formatearMoneda(f.sistema), clase: 'text-right' },
            {
              titulo: 'Total',
              celda: (f) => (
                <span className="inline-flex items-center gap-2">
                  {f.estimado && <Insignia tono="apagado">Estimado</Insignia>}
                  <strong>{formatearMoneda(f.total)}</strong>
                </span>
              ),
              clase: 'text-right',
            },
            { titulo: 'Salidas', celda: (f) => f.movimientos, clase: 'text-right text-tenue' },
          ]}
        />
      </Tarjeta>
    </section>
  )
}

function Avisos() {
  const reporte = useQuery(consultasReportes.avisos)
  return (
    <section className="flex flex-col gap-4">
      <div className="flex justify-end">
        <DescargaExcel ruta={rutaExcel('/api/v1/reportes/presupuesto', { avisos: true })} />
      </div>
      {reporte.isError && <Aviso>{mensajeDe(reporte.error)}</Aviso>}
      <Tarjeta className="p-0">
        <Tabla
          filas={reporte.data}
          clave={(l) => l.unidadObraId}
          vacio="Ningún lote llega al 80% de su presupuesto."
          columnas={[
            {
              titulo: 'Contrato',
              celda: (l) => (
                <Link
                  to="/reportes/contratos/$id"
                  params={{ id: String(l.contratoId) }}
                  className="text-marca hover:underline"
                >
                  {l.contrato}
                </Link>
              ),
            },
            { titulo: 'Lote', celda: (l) => l.clave, clase: 'font-mono' },
            { titulo: 'Prototipo', celda: (l) => l.prototipo ?? '—' },
            {
              titulo: 'Presupuesto',
              celda: (l) => (l.presupuesto == null ? '—' : formatearMoneda(l.presupuesto)),
              clase: 'text-right',
            },
            {
              titulo: 'Consumido',
              celda: (l) => formatearMoneda(l.consumido),
              clase: 'text-right',
            },
            {
              titulo: 'Restante',
              celda: (l) => (l.restante == null ? '—' : formatearMoneda(l.restante)),
              clase: 'text-right',
            },
            {
              titulo: '',
              celda: (l) => <Semaforo semaforo={l.semaforo} porcentaje={l.porcentaje} />,
            },
          ]}
        />
      </Tarjeta>
    </section>
  )
}

function Surtido() {
  const [contrato, setContrato] = useState('')
  const [desde, setDesde] = useState('')
  const [hasta, setHasta] = useState('')
  const sesion = useSesion()
  const veCostos = puedeAlguno(sesion, PERMISOS.costoVerPropio, PERMISOS.costoVerTodo)
  const contratos = useQuery({ ...consultasReportes.contratos, enabled: veCostos })
  const contratoId = contrato ? Number(contrato) : undefined
  const reporte = useQuery(
    consultasReportes.surtido(contratoId, desde || undefined, hasta || undefined),
  )
  return (
    <section className="flex flex-col gap-4">
      <div className="grid gap-4 md:grid-cols-4 md:items-end">
        {veCostos && (
          <Seleccion
            etiqueta="Contrato"
            vacio="Todos"
            opciones={(contratos.data ?? []).map((c) => ({
              valor: c.contratoId,
              texto: c.contrato,
            }))}
            value={contrato}
            onChange={(e) => setContrato(e.target.value)}
          />
        )}
        <Campo
          etiqueta="Desde"
          type="date"
          value={desde}
          onChange={(e) => setDesde(e.target.value)}
        />
        <Campo
          etiqueta="Hasta"
          type="date"
          value={hasta}
          onChange={(e) => setHasta(e.target.value)}
        />
        <DescargaExcel
          ruta={rutaExcel('/api/v1/reportes/requisicion-vs-surtido', { contratoId, desde, hasta })}
        />
      </div>
      {reporte.isError && <Aviso>{mensajeDe(reporte.error)}</Aviso>}
      <Tarjeta className="p-0">
        <Tabla
          filas={reporte.data}
          clave={(r) => `${r.requisicionId}-${r.unidadObraId}-${r.materialClave}`}
          vacio="No hay requisiciones en ese periodo."
          columnas={[
            {
              titulo: 'Requisición',
              celda: (r) => (
                <Link
                  to="/requisiciones/$id"
                  params={{ id: String(r.requisicionId) }}
                  className="font-mono text-marca hover:underline"
                >
                  {r.folio}
                </Link>
              ),
            },
            { titulo: 'Fecha', celda: (r) => formatearDia(r.fecha), clase: 'whitespace-nowrap' },
            { titulo: 'Lote', celda: (r) => `${r.contrato ?? ''} ${r.unidadObra ?? ''}` },
            { titulo: 'Material', celda: (r) => r.material },
            { titulo: 'Pedido', celda: (r) => numeroCon(r.pedido, r.unidad), clase: 'text-right' },
            {
              titulo: 'Surtido',
              celda: (r) => numeroCon(r.surtido, r.unidad),
              clase: 'text-right',
            },
            {
              titulo: 'Apartado',
              celda: (r) => numeroCon(r.reservado, r.unidad),
              clase: 'text-right',
            },
            {
              titulo: 'Falta comprar',
              celda: (r) => numeroCon(r.faltante, r.unidad),
              clase: 'text-right',
            },
            { titulo: 'Avance', celda: (r) => <Avance valor={r.porcentaje ?? 0} /> },
          ]}
        />
      </Tarjeta>
    </section>
  )
}

const numeroCon = (valor: number | undefined, unidad: string | undefined) =>
  !valor ? '—' : `${formatearNumero(valor)} ${unidad ?? ''}`

function Inventario() {
  const [almacen, setAlmacen] = useState('')
  const almacenes = useQuery(consultasInventario.almacenes)
  const almacenId = almacen ? Number(almacen) : undefined
  const reporte = useQuery(consultasReportes.valorInventario(almacenId))
  return (
    <section className="flex flex-col gap-4">
      <div className="grid gap-4 md:grid-cols-4 md:items-end">
        <Seleccion
          etiqueta="Almacén"
          vacio="Todos"
          opciones={(almacenes.data ?? []).map((a) => ({ valor: a.id, texto: a.clave }))}
          value={almacen}
          onChange={(e) => setAlmacen(e.target.value)}
        />
        <p className="text-sm text-tenue md:col-span-2">
          Valor al costo promedio:{' '}
          <strong className="text-texto">{formatearMoneda(reporte.data?.valor ?? 0)}</strong>
        </p>
        <DescargaExcel ruta={rutaExcel('/api/v1/reportes/valor-inventario', { almacenId })} />
      </div>
      {reporte.isError && <Aviso>{mensajeDe(reporte.error)}</Aviso>}
      <Tarjeta className="p-0">
        <Tabla
          filas={reporte.data?.materiales}
          clave={(m) => `${m.almacenId}-${m.materialId}`}
          vacio="No hay existencias."
          columnas={[
            { titulo: 'Almacén', celda: (m) => m.almacen },
            { titulo: 'Clave', celda: (m) => m.clave, clase: 'font-mono' },
            { titulo: 'Material', celda: (m) => m.material },
            {
              titulo: 'Existencia',
              celda: (m) => `${formatearNumero(m.existencia)} ${m.unidad}`,
              clase: 'text-right whitespace-nowrap',
            },
            {
              titulo: 'Apartado',
              celda: (m) => (m.reservado ? formatearNumero(m.reservado) : '—'),
              clase: 'text-right',
            },
            {
              titulo: 'Libre',
              celda: (m) => formatearNumero(m.disponible),
              clase: 'text-right',
            },
            {
              titulo: 'Costo promedio',
              celda: (m) => formatearMoneda(m.costoPromedio),
              clase: 'text-right',
            },
            { titulo: 'Valor', celda: (m) => formatearMoneda(m.valor), clase: 'text-right' },
          ]}
        />
      </Tarjeta>
    </section>
  )
}

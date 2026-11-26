import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useState } from 'react'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Campo } from '@/components/ui/campo'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { formatearMoneda, formatearNumero } from '@/lib/formato'
import { PERMISOS, puedeAlguno, useSesion } from '@/sesion/sesion'
import { consultasInventario, formatearDia, nombreTipo, type RenglonKardex } from './consultas'

/** Kardex de un material: cada entrada y salida, con la existencia y el costo que dejó. */
export function PantallaKardex({ materialId, almacen }: { materialId: number; almacen?: number }) {
  const sesion = useSesion()
  const veCostos = puedeAlguno(sesion, PERMISOS.precioVer, PERMISOS.costoVerTodo)
  const [almacenId, setAlmacenId] = useState(almacen ? String(almacen) : '')
  const [desde, setDesde] = useState('')
  const [hasta, setHasta] = useState('')
  const almacenes = useQuery(consultasInventario.almacenes)
  const material = useQuery({
    queryKey: ['materiales', materialId],
    queryFn: async () =>
      exigir(await api.GET('/api/v1/materiales/{id}', { params: { path: { id: materialId } } })),
  })
  const kardex = useQuery(
    consultasInventario.kardex(
      materialId,
      almacenId ? Number(almacenId) : undefined,
      desde || undefined,
      hasta || undefined,
    ),
  )

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header>
        <Link to="/existencias" className="text-sm text-marca hover:underline">
          ← Existencias
        </Link>
        <h1 className="text-2xl font-semibold">
          Kardex · {material.data ? `${material.data.clave} ${material.data.nombre}` : '…'}
        </h1>
        <p className="text-tenue">
          En {material.data?.unidadControl ?? 'unidad de control'}; del más reciente al más antiguo.
        </p>
      </header>

      <div className="grid gap-4 md:grid-cols-3 md:items-end">
        <Seleccion
          etiqueta="Almacén"
          vacio="Todos"
          opciones={(almacenes.data ?? []).map((a) => ({ valor: a.id, texto: a.clave }))}
          value={almacenId}
          onChange={(e) => setAlmacenId(e.target.value)}
        />
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
      </div>

      <Tarjeta className="p-0">
        {kardex.isError && (
          <div className="p-4">
            <Aviso>{mensajeDe(kardex.error)}</Aviso>
          </div>
        )}
        <Tabla
          filas={kardex.data}
          clave={(k) => k.id}
          vacio="Sin movimientos en ese periodo."
          columnas={[
            { titulo: 'Fecha', celda: (k) => formatearDia(k.fecha), clase: 'whitespace-nowrap' },
            { titulo: 'Almacén', celda: (k) => k.almacen },
            {
              titulo: 'Documento',
              celda: (k) => (
                <Link
                  to="/movimientos/$id"
                  params={{ id: String(k.movimientoId) }}
                  className="font-mono text-marca hover:underline"
                >
                  {k.folio}
                </Link>
              ),
            },
            { titulo: 'Tipo', celda: (k) => nombreTipo(k.tipo) },
            {
              titulo: 'Cantidad',
              celda: (k) => (
                <span className={k.cantidad < 0 ? 'text-peligro' : 'text-exito'}>
                  {k.cantidad > 0 ? '+' : ''}
                  {formatearNumero(k.cantidad)}
                </span>
              ),
              clase: 'text-right',
            },
            ...(veCostos
              ? [
                  {
                    titulo: 'Costo unit.',
                    celda: (k: RenglonKardex) =>
                      k.costoUnitario == null ? '—' : formatearMoneda(k.costoUnitario),
                    clase: 'text-right whitespace-nowrap',
                  },
                  {
                    titulo: 'Importe',
                    celda: (k: RenglonKardex) =>
                      k.importe == null ? '—' : formatearMoneda(k.importe),
                    clase: 'text-right whitespace-nowrap',
                  },
                ]
              : []),
            {
              titulo: 'Existencia',
              celda: (k) => formatearNumero(k.existencia),
              clase: 'text-right font-medium',
            },
            ...(veCostos
              ? [
                  {
                    titulo: 'Promedio',
                    celda: (k: RenglonKardex) =>
                      k.costoPromedio == null ? '—' : formatearMoneda(k.costoPromedio),
                    clase: 'text-right whitespace-nowrap',
                  },
                ]
              : []),
          ]}
        />
      </Tarjeta>
    </div>
  )
}

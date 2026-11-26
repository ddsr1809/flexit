import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Tarjeta } from '@/components/ui/tarjeta'
import { formatearFecha, formatearMoneda, formatearNumero } from '@/lib/formato'
import { consultasInventario } from './consultas'

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

/**
 * Cierre de mes (administración). Los meses se cierran en orden, cuando ya terminaron y no les
 * quedan documentos sin aplicar o cancelar. Cerrado, no acepta movimientos y queda la foto del
 * inventario valuado.
 */
export function PantallaCierre() {
  const cliente = useQueryClient()
  const periodos = useQuery(consultasInventario.periodos)
  const cerrar = useMutation({
    mutationFn: async ({ anio, mes }: { anio: number; mes: number }) =>
      exigir(
        await api.POST('/api/v1/periodos/{anio}/{mes}/cerrar', { params: { path: { anio, mes } } }),
      ),
    onSuccess: (datos) => cliente.setQueryData(consultasInventario.periodos.queryKey, datos),
  })

  const datos = periodos.data
  const [anio, mes] = (datos?.siguiente ?? '').split('-').map(Number)

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
          ) : datos.pendientesSiguiente > 0 ? (
            <p className="text-sm">
              Hay {datos.pendientesSiguiente} documentos sin aplicar ni cancelar.{' '}
              <Link to="/movimientos" className="text-marca underline">
                Revísalos en Documentos
              </Link>
              .
            </p>
          ) : (
            <div>
              <Boton disabled={cerrar.isPending} onClick={() => cerrar.mutate({ anio, mes })}>
                Cerrar {nombreMes(anio, mes)}
              </Boton>
            </div>
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
          ]}
        />
      </Tarjeta>
    </div>
  )
}

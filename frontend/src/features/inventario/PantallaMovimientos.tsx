import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { ChevronLeft, ChevronRight, Plus } from 'lucide-react'
import { useState } from 'react'
import { mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Insignia } from '@/components/ui/insignia'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { useDiferido } from '@/lib/diferido'
import { formatearMoneda } from '@/lib/formato'
import { PERMISOS, puede, useSesion } from '@/sesion/sesion'
import {
  consultasInventario,
  ESTADOS,
  formatearDia,
  nombreEstado,
  nombreTipo,
  TAMANO_PAGINA,
  TIPOS,
  type ResumenMovimiento,
} from './consultas'

export const TonoEstado = ({ estado }: { estado: string }) => (
  <Insignia tono={estado === 'aplicado' ? 'exito' : estado === 'cancelado' ? 'apagado' : 'neutro'}>
    {nombreEstado(estado)}
  </Insignia>
)

/** Documentos del almacén con filtros, y el acceso a capturar uno nuevo de cada tipo. */
export function PantallaMovimientos() {
  const sesion = useSesion()
  const captura = puede(sesion, PERMISOS.movimientoCapturar)
  const [tipo, setTipo] = useState('')
  const [estado, setEstado] = useState('')
  const [almacen, setAlmacen] = useState('')
  const [folio, setFolio] = useState('')
  const [pagina, setPagina] = useState(0)
  const folioDiferido = useDiferido(folio.trim(), 250)
  const almacenes = useQuery(consultasInventario.almacenes)
  const movimientos = useQuery(
    consultasInventario.movimientos({
      tipo: tipo || undefined,
      estados: estado || undefined,
      almacenId: almacen ? Number(almacen) : undefined,
      folio: folioDiferido || undefined,
      pagina,
    }),
  )
  const filtrar = (cambio: () => void) => {
    cambio()
    setPagina(0)
  }

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold">Documentos del almacén</h1>
        <p className="text-tenue">
          Se capturan en borrador, se autorizan si hace falta y se aplican: aplicar es lo único que
          mueve la existencia.
        </p>
      </header>

      {captura && (
        <Tarjeta>
          <h2 className="font-medium">Capturar</h2>
          <div className="mt-3 flex flex-wrap gap-2">
            {Object.entries(TIPOS)
              .filter(([, info]) => info.capturable)
              .map(([clave, info]) => (
                <Link
                  key={clave}
                  to="/movimientos/nuevo/$tipo"
                  params={{ tipo: clave }}
                  search={{}}
                  title={info.descripcion}
                  className="inline-flex min-h-11 items-center gap-2 rounded-md border border-borde bg-white px-4 text-sm font-medium hover:bg-fondo"
                >
                  <Plus className="size-4" /> {info.nuevo}
                </Link>
              ))}
          </div>
        </Tarjeta>
      )}

      <div className="grid gap-4 md:grid-cols-4 md:items-end">
        <Seleccion
          etiqueta="Tipo"
          vacio="Todos"
          opciones={Object.entries(TIPOS).map(([valor, info]) => ({ valor, texto: info.nombre }))}
          value={tipo}
          onChange={(e) => filtrar(() => setTipo(e.target.value))}
        />
        <Seleccion
          etiqueta="Estado"
          vacio="Todos"
          opciones={Object.entries(ESTADOS).map(([valor, texto]) => ({ valor, texto }))}
          value={estado}
          onChange={(e) => filtrar(() => setEstado(e.target.value))}
        />
        <Seleccion
          etiqueta="Almacén"
          vacio="Todos"
          opciones={(almacenes.data ?? []).map((a) => ({ valor: a.id, texto: a.clave }))}
          value={almacen}
          onChange={(e) => filtrar(() => setAlmacen(e.target.value))}
        />
        <Campo
          etiqueta="Folio"
          type="search"
          placeholder="SAL-2026-000123"
          value={folio}
          onChange={(e) => filtrar(() => setFolio(e.target.value))}
        />
      </div>

      <Tarjeta className="p-0">
        {movimientos.isError && (
          <div className="p-4">
            <Aviso>{mensajeDe(movimientos.error)}</Aviso>
          </div>
        )}
        <TablaMovimientos filas={movimientos.data} />
        <div className="flex items-center justify-end gap-2 border-t border-borde px-4 py-2 text-sm text-tenue">
          <Boton
            variante="fantasma"
            aria-label="Página anterior"
            disabled={pagina === 0}
            onClick={() => setPagina(pagina - 1)}
          >
            <ChevronLeft className="size-4" />
          </Boton>
          <span>Página {pagina + 1}</span>
          <Boton
            variante="fantasma"
            aria-label="Página siguiente"
            disabled={(movimientos.data?.length ?? 0) < TAMANO_PAGINA}
            onClick={() => setPagina(pagina + 1)}
          >
            <ChevronRight className="size-4" />
          </Boton>
        </div>
      </Tarjeta>
    </div>
  )
}

export function TablaMovimientos({
  filas,
  seleccion,
}: {
  filas: ResumenMovimiento[] | undefined
  seleccion?: { elegidos: Set<number>; cambiar: (id: number, elegido: boolean) => void }
}) {
  return (
    <Tabla
      filas={filas}
      clave={(m) => m.id}
      vacio="No hay documentos con esos filtros."
      columnas={[
        ...(seleccion
          ? [
              {
                titulo: 'Elegir',
                celda: (m: ResumenMovimiento) => (
                  <input
                    type="checkbox"
                    className="size-5"
                    aria-label={`Elegir ${m.folio}`}
                    checked={seleccion.elegidos.has(m.id)}
                    onChange={(e) => seleccion.cambiar(m.id, e.target.checked)}
                  />
                ),
              },
            ]
          : []),
        {
          titulo: 'Folio',
          celda: (m) => (
            <Link
              to="/movimientos/$id"
              params={{ id: String(m.id) }}
              className="font-mono text-marca hover:underline"
            >
              {m.folio}
            </Link>
          ),
          clase: 'whitespace-nowrap',
        },
        { titulo: 'Tipo', celda: (m) => nombreTipo(m.tipo) },
        { titulo: 'Fecha', celda: (m) => formatearDia(m.fecha), clase: 'whitespace-nowrap' },
        {
          titulo: 'Almacén',
          celda: (m) => (m.almacenDestino ? `${m.almacen} → ${m.almacenDestino}` : m.almacen),
        },
        {
          titulo: 'Contratos',
          celda: (m) => (m.contratos.length > 0 ? m.contratos.join(', ') : '—'),
          clase: 'max-w-56 truncate',
        },
        { titulo: 'Líneas', celda: (m) => m.lineas, clase: 'text-right' },
        {
          titulo: 'Importe',
          celda: (m) => (m.importe == null ? '—' : formatearMoneda(m.importe)),
          clase: 'text-right whitespace-nowrap',
        },
        { titulo: 'Capturó', celda: (m) => m.capturo },
        { titulo: 'Estado', celda: (m) => <TonoEstado estado={m.estado} /> },
      ]}
    />
  )
}

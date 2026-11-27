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
import { consultas } from '@/features/estructura/consultas'
import { formatearDia } from '@/features/inventario/consultas'
import { useDiferido } from '@/lib/diferido'
import { PERMISOS, puede, useSesion } from '@/sesion/sesion'
import { Avance, EstadoRequisicion } from './Comunes'
import {
  consultasAbastecimiento,
  ESTADOS_REQUISICION,
  TAMANO_PAGINA,
  type ResumenRequisicion,
} from './consultas'

/**
 * Requisiciones de obra: lo que el residente pide, coordinación autoriza, el almacén aparta y surte
 * y compras pide al proveedor cuando falta.
 */
export function PantallaRequisiciones() {
  const sesion = useSesion()
  const crea = puede(sesion, PERMISOS.requisicionCrear)
  const autoriza = puede(sesion, PERMISOS.documentoAutorizar)
  const [estado, setEstado] = useState('')
  const [contrato, setContrato] = useState('')
  const [folio, setFolio] = useState('')
  const [pagina, setPagina] = useState(0)
  const folioDiferido = useDiferido(folio.trim(), 250)
  const contratos = useQuery(consultas.contratos())
  const porAutorizar = useQuery({ ...consultasAbastecimiento.porAutorizar, enabled: autoriza })
  const lista = useQuery(
    consultasAbastecimiento.requisiciones({
      estados: estado || undefined,
      contratoId: contrato ? Number(contrato) : undefined,
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
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">Requisiciones</h1>
          <p className="text-tenue">
            Al autorizar se aparta lo que hay en el almacén; lo que falta lo ve compras para
            pedirlo.
          </p>
        </div>
        {crea && (
          <Link
            to="/requisiciones/nueva"
            className="inline-flex min-h-11 items-center gap-2 rounded-md bg-marca px-4 text-sm font-medium text-white hover:bg-marca-claro"
          >
            <Plus className="size-4" /> Nueva requisición
          </Link>
        )}
      </header>

      {autoriza && (porAutorizar.data?.length ?? 0) > 0 && (
        <Tarjeta className="p-0">
          <h2 className="px-4 pt-4 font-medium">Por autorizar</h2>
          <TablaRequisiciones filas={porAutorizar.data} />
        </Tarjeta>
      )}

      <div className="grid gap-4 md:grid-cols-3 md:items-end">
        <Seleccion
          etiqueta="Estado"
          vacio="Todos"
          opciones={Object.entries(ESTADOS_REQUISICION).map(([valor, texto]) => ({ valor, texto }))}
          value={estado}
          onChange={(e) => filtrar(() => setEstado(e.target.value))}
        />
        <Seleccion
          etiqueta="Contrato"
          vacio="Todos"
          opciones={(contratos.data ?? []).map((c) => ({ valor: c.id, texto: c.codigo }))}
          value={contrato}
          onChange={(e) => filtrar(() => setContrato(e.target.value))}
        />
        <Campo
          etiqueta="Folio"
          type="search"
          placeholder="REQ-2026-000045"
          value={folio}
          onChange={(e) => filtrar(() => setFolio(e.target.value))}
        />
      </div>

      <Tarjeta className="p-0">
        {lista.isError && (
          <div className="p-4">
            <Aviso>{mensajeDe(lista.error)}</Aviso>
          </div>
        )}
        <TablaRequisiciones filas={lista.data} />
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
            disabled={(lista.data?.length ?? 0) < TAMANO_PAGINA}
            onClick={() => setPagina(pagina + 1)}
          >
            <ChevronRight className="size-4" />
          </Boton>
        </div>
      </Tarjeta>
    </div>
  )
}

function TablaRequisiciones({ filas }: { filas: ResumenRequisicion[] | undefined }) {
  return (
    <Tabla
      filas={filas}
      clave={(r) => r.id}
      vacio="No hay requisiciones con esos filtros."
      columnas={[
        {
          titulo: 'Folio',
          celda: (r) => (
            <Link
              to="/requisiciones/$id"
              params={{ id: String(r.id) }}
              className="font-mono text-marca hover:underline"
            >
              {r.folio}
            </Link>
          ),
          clase: 'whitespace-nowrap',
        },
        { titulo: 'Contrato', celda: (r) => r.contrato },
        {
          titulo: 'Para el',
          celda: (r) => (
            <span className="inline-flex items-center gap-2 whitespace-nowrap">
              {formatearDia(r.fechaRequerida)}
              {r.urgente && <Insignia>Urgente</Insignia>}
            </span>
          ),
        },
        { titulo: 'Renglones', celda: (r) => r.renglones, clase: 'text-right' },
        { titulo: 'Surtido', celda: (r) => <Avance valor={r.avance} /> },
        {
          titulo: 'Falta comprar',
          celda: (r) => (r.conFaltante ? <Insignia>Sí</Insignia> : '—'),
        },
        { titulo: 'Pidió', celda: (r) => r.capturo },
        { titulo: 'Estado', celda: (r) => <EstadoRequisicion estado={r.estado} /> },
      ]}
    />
  )
}

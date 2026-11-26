import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useState, type FormEvent } from 'react'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Buscador } from '@/components/Buscador'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Insignia } from '@/components/ui/insignia'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import type { Material } from '@/features/catalogo/consultas'
import { formatearNumero } from '@/lib/formato'
import { numero } from '@/lib/valores'
import { PERMISOS, puede, useSesion } from '@/sesion/sesion'
import {
  buscarMateriales,
  buscarPersonas,
  consultasInventario,
  formatearDia,
  hoy,
  type PersonaNombre,
  type Prestamo,
} from './consultas'

const ESTADO_PRESTAMO: Record<string, string> = {
  abierto: 'Prestado',
  devuelto: 'Devuelto',
  perdido: 'Perdido',
}

/**
 * Préstamos de herramienta: lo prestado no está disponible para vales hasta que regresa. Si se
 * pierde, se crea la merma que coordinación autoriza.
 */
export function PantallaPrestamos() {
  const sesion = useSesion()
  const captura = puede(sesion, PERMISOS.movimientoCapturar)
  const cliente = useQueryClient()
  const [estado, setEstado] = useState('abierto')
  const [vencidos, setVencidos] = useState(false)
  const prestamos = useQuery(
    consultasInventario.prestamos(undefined, estado || undefined, vencidos),
  )
  const [perdiendo, setPerdiendo] = useState<Prestamo>()
  const [motivo, setMotivo] = useState('')

  const refrescar = () => {
    void cliente.invalidateQueries({ queryKey: ['prestamos'] })
    void cliente.invalidateQueries({ queryKey: ['existencias'] })
  }
  const devolver = useMutation({
    mutationFn: async (id: number) =>
      exigir(
        await api.POST('/api/v1/prestamos/{id}/devolver', { params: { path: { id } }, body: {} }),
      ),
    onSuccess: refrescar,
  })
  const perder = useMutation({
    mutationFn: async ({ id, texto }: { id: number; texto: string }) =>
      exigir(
        await api.POST('/api/v1/prestamos/{id}/perdido', {
          params: { path: { id } },
          body: { motivo: texto },
        }),
      ),
    onSuccess: () => {
      setPerdiendo(undefined)
      setMotivo('')
      refrescar()
      void cliente.invalidateQueries({ queryKey: ['movimientos'] })
    },
  })

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold">Préstamos de herramienta</h1>
        <p className="text-tenue">Quién tiene qué, desde cuándo y cuándo debe regresarlo.</p>
      </header>

      {captura && <NuevoPrestamo alGuardar={refrescar} />}

      <div className="flex flex-wrap items-end gap-4">
        <Seleccion
          etiqueta="Estado"
          vacio="Todos"
          opciones={Object.entries(ESTADO_PRESTAMO).map(([valor, texto]) => ({ valor, texto }))}
          value={estado}
          onChange={(e) => setEstado(e.target.value)}
        />
        <label className="flex min-h-11 items-center gap-2 text-sm">
          <input
            type="checkbox"
            className="size-4"
            checked={vencidos}
            onChange={(e) => setVencidos(e.target.checked)}
          />
          Solo vencidos
        </label>
      </div>

      {(devolver.error ?? perder.error) && (
        <Aviso>{mensajeDe(devolver.error ?? perder.error)}</Aviso>
      )}
      {perder.data && (
        <Aviso tipo="exito">
          Se registró la merma{' '}
          <Link
            to="/movimientos/$id"
            params={{ id: String(perder.data.mermaId) }}
            className="font-medium underline"
          >
            por autorizar
          </Link>
          .
        </Aviso>
      )}
      {perdiendo && (
        <Tarjeta className="flex flex-col gap-3 md:flex-row md:items-end">
          <div className="flex-1">
            <Campo
              etiqueta={`¿Qué pasó con ${perdiendo.material}?`}
              autoFocus
              value={motivo}
              onChange={(e) => setMotivo(e.target.value)}
            />
          </div>
          <Boton
            disabled={!motivo.trim() || perder.isPending}
            onClick={() => perder.mutate({ id: perdiendo.id, texto: motivo })}
          >
            Registrar pérdida
          </Boton>
          <Boton variante="fantasma" onClick={() => setPerdiendo(undefined)}>
            No
          </Boton>
        </Tarjeta>
      )}

      <Tarjeta className="p-0">
        <Tabla
          filas={prestamos.data}
          clave={(p) => p.id}
          vacio="No hay préstamos con esos filtros."
          columnas={[
            { titulo: 'Folio', celda: (p) => <span className="font-mono">{p.folio}</span> },
            { titulo: 'Herramienta', celda: (p) => p.material },
            {
              titulo: 'Cantidad',
              celda: (p) => `${formatearNumero(p.cantidad)} ${p.unidad}`,
              clase: 'text-right whitespace-nowrap',
            },
            { titulo: 'Responsable', celda: (p) => p.responsable },
            { titulo: 'Almacén', celda: (p) => p.almacen },
            { titulo: 'Desde', celda: (p) => formatearDia(p.fecha), clase: 'whitespace-nowrap' },
            {
              titulo: 'Regresa',
              celda: (p) => (
                <span className={p.vencido ? 'font-medium text-peligro' : undefined}>
                  {formatearDia(p.compromiso)}
                  {p.vencido && ' · vencido'}
                </span>
              ),
              clase: 'whitespace-nowrap',
            },
            {
              titulo: 'Estado',
              celda: (p) => (
                <Insignia
                  tono={
                    p.estado === 'abierto'
                      ? 'neutro'
                      : p.estado === 'devuelto'
                        ? 'exito'
                        : 'apagado'
                  }
                >
                  {ESTADO_PRESTAMO[p.estado] ?? p.estado}
                </Insignia>
              ),
            },
          ]}
          acciones={(p) =>
            captura && p.estado === 'abierto' ? (
              <>
                <Boton
                  variante="fantasma"
                  disabled={devolver.isPending}
                  onClick={() => devolver.mutate(p.id)}
                >
                  Regresó
                </Boton>
                <Boton variante="fantasma" onClick={() => setPerdiendo(p)}>
                  Se perdió
                </Boton>
              </>
            ) : null
          }
        />
      </Tarjeta>
    </div>
  )
}

function NuevoPrestamo({ alGuardar }: { alGuardar: () => void }) {
  const almacenes = useQuery(consultasInventario.almacenes)
  const activos = (almacenes.data ?? []).filter((a) => a.activo)
  const [almacenId, setAlmacenId] = useState('')
  const [material, setMaterial] = useState<Material>()
  const [persona, setPersona] = useState<PersonaNombre>()
  const [cantidad, setCantidad] = useState('1')
  const [compromiso, setCompromiso] = useState('')
  const almacen = almacenId || String(activos[0]?.id ?? '')

  const prestar = useMutation({
    mutationFn: async () =>
      exigir(
        await api.POST('/api/v1/prestamos', {
          body: {
            almacenId: Number(almacen),
            materialId: material!.id,
            cantidad: numero(cantidad) ?? 0,
            responsableId: persona!.id,
            fecha: hoy(),
            compromiso: compromiso || undefined,
          },
        }),
      ),
    onSuccess: () => {
      setMaterial(undefined)
      setPersona(undefined)
      setCantidad('1')
      setCompromiso('')
      alGuardar()
    },
  })

  const enviar = (evento: FormEvent) => {
    evento.preventDefault()
    prestar.mutate()
  }

  return (
    <Tarjeta>
      <form aria-label="Nuevo préstamo" onSubmit={enviar} className="flex flex-col gap-4">
        <h2 className="font-medium">Prestar</h2>
        <div className="grid gap-4 md:grid-cols-[1fr_2fr_6rem_2fr_10rem_auto] md:items-end">
          <Seleccion
            etiqueta="Almacén"
            opciones={activos.map((a) => ({ valor: a.id, texto: a.clave }))}
            value={almacen}
            onChange={(e) => setAlmacenId(e.target.value)}
          />
          <Buscador<Material>
            etiqueta="Herramienta"
            placeholder="Rotomartillo, compresor…"
            nombre="materiales"
            buscar={buscarMateriales}
            texto={(m) => m.nombre}
            detalle={(m) => m.clave}
            clave={(m) => m.id}
            valor={material?.nombre}
            alElegir={setMaterial}
          />
          <Campo
            etiqueta="Cantidad"
            inputMode="decimal"
            value={cantidad}
            onChange={(e) => setCantidad(e.target.value)}
          />
          <Buscador<PersonaNombre>
            etiqueta="Responsable"
            placeholder="Nombre o apodo"
            nombre="personas"
            buscar={buscarPersonas}
            texto={(p) => p.nombre}
            clave={(p) => p.id}
            valor={persona?.nombre}
            alElegir={setPersona}
          />
          <Campo
            etiqueta="Regresa el"
            type="date"
            min={hoy()}
            value={compromiso}
            onChange={(e) => setCompromiso(e.target.value)}
          />
          <Boton type="submit" disabled={!material || !persona || !almacen || prestar.isPending}>
            Prestar
          </Boton>
        </div>
        {prestar.isError && <Aviso>{mensajeDe(prestar.error)}</Aviso>}
      </form>
    </Tarjeta>
  )
}

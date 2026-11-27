import { useQuery } from '@tanstack/react-query'
import { Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Buscador } from '@/components/Buscador'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { consultasCatalogo, type Material } from '@/features/catalogo/consultas'
import { buscarMateriales, consultasInventario, hoy } from '@/features/inventario/consultas'
import { formatearMoneda } from '@/lib/formato'
import { aTexto, numero } from '@/lib/valores'
import type { RenglonArmado } from './calculos'
import type { DatosPedido } from './consultas'

export interface PedidoInicial {
  proveedorId?: number | null
  almacenId?: number | null
  fechaEntrega?: string | null
  observaciones?: string | null
  renglones: (RenglonArmado & { unidadId?: number | null; tasaIva?: number | null })[]
}

interface Renglon {
  clave: number
  materialId?: number
  material?: string
  unidad?: string
  cantidad: string
  unidadId: string
  precio: string
  tasaIva: string
  requisicionLineaIds: number[]
  cubre: string[]
}

let siguiente = 1

/** Encabezado y renglones de un pedido: se usa al armarlo con faltantes y al corregir un borrador. */
export function EditorPedido({
  inicial,
  titulo,
  guardando,
  error,
  alGuardar,
  alCancelar,
}: {
  inicial: PedidoInicial
  titulo: string
  guardando: boolean
  error?: string
  alGuardar: (datos: DatosPedido) => void
  alCancelar: () => void
}) {
  const proveedores = useQuery(consultasCatalogo.proveedores())
  const almacenes = useQuery(consultasInventario.almacenes)
  const unidades = useQuery(consultasCatalogo.unidades)
  const [proveedorId, setProveedorId] = useState(aTexto(inicial.proveedorId))
  const [almacenId, setAlmacenId] = useState(aTexto(inicial.almacenId))
  const [fechaEntrega, setFechaEntrega] = useState(inicial.fechaEntrega ?? '')
  const [observaciones, setObservaciones] = useState(inicial.observaciones ?? '')
  const [renglones, setRenglones] = useState<Renglon[]>(() =>
    inicial.renglones.map((r) => ({
      clave: siguiente++,
      materialId: r.materialId,
      material: r.material,
      unidad: r.unidad,
      cantidad: String(r.cantidad),
      unidadId: aTexto(r.unidadId),
      precio: aTexto(r.precio),
      tasaIva: r.tasaIva == null ? '' : String(r.tasaIva * 100),
      requisicionLineaIds: r.requisicionLineaIds,
      cubre: r.cubre,
    })),
  )
  const [aviso, setAviso] = useState<string>()
  const cambiar = (clave: number, cambio: Partial<Renglon>) =>
    setRenglones((actual) => actual.map((r) => (r.clave === clave ? { ...r, ...cambio } : r)))

  const subtotal = renglones.reduce(
    (suma, r) => suma + (numero(r.cantidad) ?? 0) * (numero(r.precio) ?? 0),
    0,
  )

  const guardar = (enviar: boolean) => {
    if (!proveedorId) return setAviso('Elige el proveedor.')
    if (!almacenId) return setAviso('Elige el almacén que recibe.')
    if (renglones.length === 0) return setAviso('Agrega al menos un material.')
    const salida: DatosPedido['renglones'] = []
    for (const [indice, r] of renglones.entries()) {
      const donde = `Renglón ${indice + 1}: `
      const cantidad = numero(r.cantidad)
      const precio = numero(r.precio)
      const tasa = numero(r.tasaIva)
      if (!r.materialId) return setAviso(`${donde}elige el material.`)
      if (!cantidad || cantidad <= 0) return setAviso(`${donde}escribe la cantidad.`)
      if (precio === undefined || precio < 0) return setAviso(`${donde}escribe el precio sin IVA.`)
      if (tasa !== undefined && (tasa < 0 || tasa > 16))
        return setAviso(`${donde}el IVA va de 0 a 16%.`)
      salida.push({
        materialId: r.materialId,
        cantidad,
        unidadId: r.unidadId ? Number(r.unidadId) : undefined,
        precio,
        tasaIva: tasa === undefined ? undefined : tasa / 100,
        requisicionLineaIds: r.requisicionLineaIds,
      })
    }
    setAviso(undefined)
    alGuardar({
      proveedorId: Number(proveedorId),
      almacenId: Number(almacenId),
      fechaEntrega: fechaEntrega || undefined,
      observaciones: observaciones.trim() || undefined,
      renglones: salida,
      enviar,
    })
  }

  return (
    <Tarjeta className="flex flex-col gap-4">
      <h2 className="font-medium">{titulo}</h2>
      <div className="grid gap-4 md:grid-cols-4">
        <Seleccion
          etiqueta="Proveedor"
          vacio="Elige…"
          opciones={(proveedores.data ?? [])
            .filter((p) => p.activo)
            .map((p) => ({ valor: p.id, texto: p.nombre }))}
          value={proveedorId}
          onChange={(e) => setProveedorId(e.target.value)}
        />
        <Seleccion
          etiqueta="Almacén que recibe"
          vacio="Elige…"
          opciones={(almacenes.data ?? [])
            .filter((a) => a.activo)
            .map((a) => ({ valor: a.id, texto: `${a.clave} ${a.nombre}` }))}
          value={almacenId}
          onChange={(e) => setAlmacenId(e.target.value)}
        />
        <Campo
          etiqueta="Entrega esperada"
          type="date"
          min={hoy()}
          value={fechaEntrega}
          onChange={(e) => setFechaEntrega(e.target.value)}
        />
        <Campo
          etiqueta="Observaciones"
          value={observaciones}
          onChange={(e) => setObservaciones(e.target.value)}
        />
      </div>
      <div className="flex flex-col gap-3">
        {renglones.map((r, indice) => (
          <div
            key={r.clave}
            className="grid items-start gap-3 border-b border-borde pb-3 last:border-0 md:grid-cols-12"
          >
            <div className="md:col-span-4">
              {r.requisicionLineaIds.length > 0 ? (
                <p className="text-sm">
                  {r.material}
                  <span className="block text-xs text-tenue">Cubre {r.cubre.join(', ')}</span>
                </p>
              ) : (
                <Buscador<Material>
                  etiqueta={`Material ${indice + 1}`}
                  placeholder="Clave o nombre"
                  nombre="materiales"
                  buscar={buscarMateriales}
                  texto={(m) => m.nombre}
                  detalle={(m) => `${m.clave} · ${m.unidadControl}`}
                  clave={(m) => m.id}
                  valor={r.material}
                  alElegir={(m) =>
                    cambiar(r.clave, {
                      materialId: m.id,
                      material: m.nombre,
                      unidad: m.unidadControl,
                      unidadId: '',
                    })
                  }
                />
              )}
            </div>
            <div className="md:col-span-2">
              <Campo
                etiqueta="Cantidad"
                inputMode="decimal"
                value={r.cantidad}
                onChange={(e) => cambiar(r.clave, { cantidad: e.target.value })}
              />
            </div>
            <div className="md:col-span-2">
              <Seleccion
                etiqueta="Unidad"
                vacio={r.unidad ?? '—'}
                opciones={(unidades.data ?? [])
                  .filter((u) => u.activa)
                  .map((u) => ({ valor: u.id, texto: u.clave }))}
                value={r.unidadId}
                onChange={(e) => cambiar(r.clave, { unidadId: e.target.value })}
              />
            </div>
            <div className="md:col-span-2">
              <Campo
                etiqueta="Precio sin IVA"
                inputMode="decimal"
                value={r.precio}
                onChange={(e) => cambiar(r.clave, { precio: e.target.value })}
              />
            </div>
            <div className="md:col-span-1">
              <Campo
                etiqueta="IVA %"
                inputMode="decimal"
                placeholder="Prov."
                value={r.tasaIva}
                onChange={(e) => cambiar(r.clave, { tasaIva: e.target.value })}
              />
            </div>
            <div className="flex items-end md:col-span-1 md:h-full">
              <Boton
                variante="fantasma"
                aria-label={`Quitar el renglón ${indice + 1}`}
                onClick={() => setRenglones(renglones.filter((x) => x.clave !== r.clave))}
              >
                <Trash2 className="size-4" />
              </Boton>
            </div>
          </div>
        ))}
        <div className="flex flex-wrap items-center justify-between gap-2">
          <Boton
            variante="secundario"
            onClick={() =>
              setRenglones([
                ...renglones,
                {
                  clave: siguiente++,
                  cantidad: '',
                  unidadId: '',
                  precio: '',
                  tasaIva: '',
                  requisicionLineaIds: [],
                  cubre: [],
                },
              ])
            }
          >
            <Plus className="size-4" /> Material sin requisición
          </Boton>
          <p className="text-sm text-tenue">Subtotal sin IVA: {formatearMoneda(subtotal)}</p>
        </div>
      </div>
      {(aviso ?? error) && <Aviso>{aviso ?? error}</Aviso>}
      <div className="flex flex-wrap gap-2">
        <Boton disabled={guardando} onClick={() => guardar(true)}>
          Guardar y marcar enviado
        </Boton>
        <Boton variante="secundario" disabled={guardando} onClick={() => guardar(false)}>
          Guardar borrador
        </Boton>
        <Boton variante="fantasma" onClick={alCancelar}>
          Cancelar
        </Boton>
      </div>
    </Tarjeta>
  )
}

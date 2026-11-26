import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useState } from 'react'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Buscador } from '@/components/Buscador'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Tarjeta } from '@/components/ui/tarjeta'
import type { Material } from '@/features/catalogo/consultas'
import { formatearNumero } from '@/lib/formato'
import { numero } from '@/lib/valores'
import { PERMISOS, puede, puedeAlguno, useSesion } from '@/sesion/sesion'
import {
  buscarMateriales,
  consultasInventario,
  formatearDia,
  type DetalleConteo,
  type LineaConteo,
} from './consultas'

/**
 * Captura de un conteo. Mientras está abierto, quien cuenta no ve la existencia del sistema (conteo
 * a ciegas); administración sí, y al cerrar ve la diferencia de cada material.
 */
export function PantallaConteo({ id }: { id: number }) {
  const sesion = useSesion()
  const cliente = useQueryClient()
  const captura = puedeAlguno(sesion, PERMISOS.movimientoCapturar, PERMISOS.administrar)
  const administra = puede(sesion, PERMISOS.administrar)
  const conteo = useQuery(consultasInventario.conteo(id))
  const [contados, setContados] = useState<Record<number, string>>({})
  const [costos, setCostos] = useState<Record<number, string>>({})

  const actualizar = (detalle: DetalleConteo) => {
    cliente.setQueryData(consultasInventario.conteo(id).queryKey, detalle)
    void cliente.invalidateQueries({ queryKey: ['conteos'], exact: false })
  }
  const guardar = useMutation({
    mutationFn: async (lineas: { materialId: number; contado: number; costoUnitario?: number }[]) =>
      exigir(
        await api.PUT('/api/v1/conteos/{id}/lineas', {
          params: { path: { id } },
          body: { lineas },
        }),
      ),
    onSuccess: (detalle) => {
      setContados({})
      setCostos({})
      actualizar(detalle)
    },
  })
  const cerrar = useMutation({
    mutationFn: async () =>
      exigir(await api.POST('/api/v1/conteos/{id}/cerrar', { params: { path: { id } } })),
    onSuccess: (detalle) => {
      actualizar(detalle)
      void cliente.invalidateQueries({ queryKey: ['existencias'] })
      void cliente.invalidateQueries({ queryKey: ['movimientos'] })
    },
  })
  const cancelar = useMutation({
    mutationFn: async () =>
      exigir(await api.POST('/api/v1/conteos/{id}/cancelar', { params: { path: { id } } })),
    onSuccess: actualizar,
  })

  if (conteo.isError) return <Aviso>{mensajeDe(conteo.error)}</Aviso>
  const detalle = conteo.data
  if (!detalle) return <p className="text-tenue">Cargando…</p>
  const c = detalle.conteo
  const abierto = c.estado === 'abierto'
  const cambios = Object.entries(contados)
    .filter(([, valor]) => valor.trim() !== '')
    .map(([material, valor]) => ({
      materialId: Number(material),
      contado: numero(valor) ?? Number.NaN,
      costoUnitario: numero(costos[Number(material)]),
    }))
  const invalido = cambios.some((l) => Number.isNaN(l.contado) || l.contado < 0)
  const error = guardar.error ?? cerrar.error ?? cancelar.error

  return (
    <div className="flex max-w-5xl flex-col gap-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <Link to="/conteos" className="text-sm text-marca hover:underline">
            ← Conteos
          </Link>
          <h1 className="text-2xl font-semibold">
            Conteo <span className="font-mono">{c.folio}</span>
          </h1>
          <p className="text-tenue">
            {c.almacen} · {formatearDia(c.fecha)} · {c.contadas} de {c.lineas} contados ·{' '}
            {c.estado === 'abierto' ? 'abierto' : c.estado}
          </p>
        </div>
        {abierto && (
          <div className="flex flex-wrap gap-2">
            {captura && (
              <Boton
                disabled={cambios.length === 0 || invalido || guardar.isPending}
                onClick={() => guardar.mutate(cambios)}
              >
                Guardar {cambios.length > 0 ? cambios.length : ''}
              </Boton>
            )}
            {administra && (
              <Boton
                variante="secundario"
                disabled={cambios.length > 0 || cerrar.isPending}
                title={cambios.length > 0 ? 'Guarda primero lo capturado' : undefined}
                onClick={() => cerrar.mutate()}
              >
                Cerrar y aplicar ajustes
              </Boton>
            )}
            {captura && (
              <Boton
                variante="fantasma"
                disabled={cancelar.isPending}
                onClick={() => cancelar.mutate()}
              >
                Cancelar conteo
              </Boton>
            )}
          </div>
        )}
      </header>

      {error && <Aviso>{mensajeDe(error)}</Aviso>}
      {c.estado === 'cerrado' && (
        <Aviso tipo="exito">
          Cerrado por {c.cerro}. Los ajustes ya se aplicaron;{' '}
          <Link to="/movimientos" className="font-medium underline">
            están en Documentos
          </Link>
          .
        </Aviso>
      )}

      {abierto && captura && (
        <Tarjeta>
          <Buscador<Material>
            etiqueta="Agregar un material que no está en la lista"
            placeholder="Clave o nombre"
            nombre="materiales"
            buscar={buscarMateriales}
            texto={(m) => m.nombre}
            detalle={(m) => m.clave}
            clave={(m) => m.id}
            alElegir={(m) => guardar.mutate([{ materialId: m.id, contado: 0 }])}
          />
          <p className="mt-2 text-sm text-tenue">Se agrega con 0; escribe después lo contado.</p>
        </Tarjeta>
      )}

      <Tarjeta className="p-0">
        <Tabla
          filas={detalle.lineas}
          clave={(l) => l.materialId}
          vacio="El conteo no tiene materiales."
          columnas={[
            { titulo: 'Clave', celda: (l) => <span className="font-mono">{l.clave}</span> },
            { titulo: 'Material', celda: (l) => l.material },
            {
              titulo: 'Contado',
              celda: (l: LineaConteo) =>
                abierto && captura ? (
                  <input
                    aria-label={`Contado de ${l.material}`}
                    inputMode="decimal"
                    className="min-h-11 w-28 rounded-md border border-borde px-2 text-right"
                    placeholder={l.contado == null ? '—' : String(l.contado)}
                    value={contados[l.materialId] ?? ''}
                    onChange={(e) => setContados({ ...contados, [l.materialId]: e.target.value })}
                  />
                ) : l.contado == null ? (
                  '—'
                ) : (
                  formatearNumero(l.contado)
                ),
              clase: 'text-right',
            },
            { titulo: 'Unidad', celda: (l) => l.unidad },
            ...(detalle.lineas.some((l) => l.existenciaSistema != null)
              ? [
                  {
                    titulo: 'Sistema',
                    celda: (l: LineaConteo) =>
                      l.existenciaSistema == null ? '—' : formatearNumero(l.existenciaSistema),
                    clase: 'text-right',
                  },
                  {
                    titulo: 'Diferencia',
                    celda: (l: LineaConteo) =>
                      l.diferencia == null || l.diferencia === 0 ? (
                        '—'
                      ) : (
                        <span className={l.diferencia < 0 ? 'text-peligro' : 'text-exito'}>
                          {l.diferencia > 0 ? '+' : ''}
                          {formatearNumero(l.diferencia)}
                        </span>
                      ),
                    clase: 'text-right font-medium',
                  },
                ]
              : []),
            ...(abierto && captura
              ? [
                  {
                    titulo: 'Costo si sobra',
                    celda: (l: LineaConteo) => (
                      <input
                        aria-label={`Costo de ${l.material}`}
                        inputMode="decimal"
                        className="min-h-11 w-28 rounded-md border border-borde px-2 text-right"
                        placeholder={l.costoUnitario == null ? 'opcional' : String(l.costoUnitario)}
                        value={costos[l.materialId] ?? ''}
                        onChange={(e) => setCostos({ ...costos, [l.materialId]: e.target.value })}
                      />
                    ),
                    clase: 'text-right',
                  },
                ]
              : []),
          ]}
        />
      </Tarjeta>
    </div>
  )
}

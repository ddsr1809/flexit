import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useState } from 'react'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Insignia } from '@/components/ui/insignia'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { PERMISOS, puedeAlguno, useSesion } from '@/sesion/sesion'
import { consultasInventario, formatearDia, hoy } from './consultas'

const ESTADO_CONTEO: Record<string, string> = {
  abierto: 'Abierto',
  cerrado: 'Cerrado',
  cancelado: 'Cancelado',
}

/** Conteos físicos: abrir uno por almacén, capturar lo contado y cerrarlo (administración). */
export function PantallaConteos() {
  const sesion = useSesion()
  const abre = puedeAlguno(sesion, PERMISOS.movimientoCapturar, PERMISOS.administrar)
  const cliente = useQueryClient()
  const navegar = useNavigate()
  const almacenes = useQuery(consultasInventario.almacenes)
  const conteos = useQuery(consultasInventario.conteos())
  const [almacenId, setAlmacenId] = useState('')
  const activos = (almacenes.data ?? []).filter((a) => a.activo)
  const almacen = almacenId || String(activos[0]?.id ?? '')

  const abrir = useMutation({
    mutationFn: async () =>
      exigir(
        await api.POST('/api/v1/conteos', { body: { almacenId: Number(almacen), fecha: hoy() } }),
      ),
    onSuccess: (detalle) => {
      void cliente.invalidateQueries({ queryKey: ['conteos'] })
      void navegar({ to: '/conteos/$id', params: { id: String(detalle.conteo.id) } })
    },
  })

  return (
    <div className="flex max-w-5xl flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold">Conteo físico</h1>
        <p className="text-tenue">
          Mientras el conteo está abierto, el almacén no aplica movimientos. Al cerrarlo, los
          sobrantes y faltantes se vuelven ajustes.
        </p>
      </header>

      {abre && (
        <Tarjeta className="flex flex-col gap-4 md:flex-row md:items-end">
          <Seleccion
            etiqueta="Almacén"
            opciones={activos.map((a) => ({ valor: a.id, texto: `${a.clave} · ${a.nombre}` }))}
            value={almacen}
            onChange={(e) => setAlmacenId(e.target.value)}
          />
          <Boton disabled={!almacen || abrir.isPending} onClick={() => abrir.mutate()}>
            Abrir conteo de hoy
          </Boton>
        </Tarjeta>
      )}
      {abrir.isError && <Aviso>{mensajeDe(abrir.error)}</Aviso>}

      <Tarjeta className="p-0">
        <Tabla
          filas={conteos.data}
          clave={(c) => c.id}
          vacio="Todavía no hay conteos."
          columnas={[
            {
              titulo: 'Folio',
              celda: (c) => (
                <Link
                  to="/conteos/$id"
                  params={{ id: String(c.id) }}
                  className="font-mono text-marca hover:underline"
                >
                  {c.folio}
                </Link>
              ),
            },
            { titulo: 'Almacén', celda: (c) => c.almacen },
            { titulo: 'Fecha', celda: (c) => formatearDia(c.fecha) },
            {
              titulo: 'Contados',
              celda: (c) => `${c.contadas} de ${c.lineas}`,
              clase: 'text-right',
            },
            { titulo: 'Abrió', celda: (c) => c.abrio },
            { titulo: 'Cerró', celda: (c) => c.cerro ?? '—' },
            {
              titulo: 'Estado',
              celda: (c) => (
                <Insignia
                  tono={
                    c.estado === 'abierto' ? 'neutro' : c.estado === 'cerrado' ? 'exito' : 'apagado'
                  }
                >
                  {ESTADO_CONTEO[c.estado] ?? c.estado}
                </Insignia>
              ),
            },
          ]}
        />
      </Tarjeta>
    </div>
  )
}

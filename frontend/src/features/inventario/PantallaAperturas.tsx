import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useState, type FormEvent } from 'react'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Insignia } from '@/components/ui/insignia'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { formatearMoneda } from '@/lib/formato'
import { PERMISOS, puedeAlguno, useSesion } from '@/sesion/sesion'
import { ESTADO_APERTURA } from './apertura'
import { consultasInventario, formatearDia, hoy, type Apertura } from './consultas'

/**
 * Corte: la apertura de cada almacén. El saldo con que arranca el sistema sale del conteo físico,
 * valuado sin IVA y firmado por coordinación y administración (H8).
 */
export function PantallaAperturas() {
  const sesion = useSesion()
  const abre = puedeAlguno(sesion, PERMISOS.movimientoCapturar, PERMISOS.administrar)
  const aperturas = useQuery(consultasInventario.aperturas)

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold">Apertura de almacén</h1>
        <p className="text-tenue">
          El día del corte: conteo físico, préstamos abiertos del Excel, costo sin IVA y las firmas
          de coordinación y administración.
        </p>
      </header>

      {abre && <NuevaApertura aperturas={aperturas.data ?? []} />}

      {aperturas.isError && <Aviso>{mensajeDe(aperturas.error)}</Aviso>}
      <Tarjeta className="p-0">
        <Tabla
          filas={aperturas.data}
          clave={(a) => a.id}
          vacio="Todavía no se abre ningún almacén."
          columnas={[
            {
              titulo: 'Folio',
              celda: (a) => (
                <Link
                  to="/aperturas/$id"
                  params={{ id: String(a.id) }}
                  className="font-mono text-marca hover:underline"
                >
                  {a.folio}
                </Link>
              ),
            },
            { titulo: 'Almacén', celda: (a) => a.almacen },
            { titulo: 'Corte', celda: (a) => formatearDia(a.fecha), clase: 'whitespace-nowrap' },
            {
              titulo: 'Estado',
              celda: (a) => (
                <Insignia
                  tono={
                    a.estado === 'aplicada'
                      ? 'exito'
                      : a.estado === 'cancelada'
                        ? 'apagado'
                        : 'neutro'
                  }
                >
                  {ESTADO_APERTURA[a.estado]}
                </Insignia>
              ),
            },
            {
              titulo: 'Valor sin IVA',
              celda: (a) => (a.importe == null ? '—' : formatearMoneda(a.importe)),
              clase: 'text-right',
            },
          ]}
        />
      </Tarjeta>
    </div>
  )
}

function NuevaApertura({ aperturas }: { aperturas: Apertura[] }) {
  const cliente = useQueryClient()
  const navegar = useNavigate()
  const almacenes = useQuery(consultasInventario.almacenes)
  const ocupados = new Set(
    aperturas.filter((a) => a.estado !== 'cancelada').map((a) => a.almacenId),
  )
  const libres = (almacenes.data ?? []).filter((a) => a.activo && !ocupados.has(a.id))
  const [almacenId, setAlmacenId] = useState('')
  const [fecha, setFecha] = useState(hoy())
  const [observaciones, setObservaciones] = useState('')
  const almacen = almacenId || String(libres[0]?.id ?? '')

  const abrir = useMutation({
    mutationFn: async () =>
      exigir(
        await api.POST('/api/v1/aperturas', {
          body: {
            almacenId: Number(almacen),
            fecha,
            observaciones: observaciones.trim() || undefined,
          },
        }),
      ),
    onSuccess: (detalle) => {
      void cliente.invalidateQueries({ queryKey: ['aperturas'] })
      void cliente.invalidateQueries({ queryKey: ['conteos'] })
      void navegar({ to: '/aperturas/$id', params: { id: String(detalle.apertura.id) } })
    },
  })

  const enviar = (evento: FormEvent) => {
    evento.preventDefault()
    abrir.mutate()
  }

  if (almacenes.isSuccess && libres.length === 0) return null
  return (
    <Tarjeta>
      <form aria-label="Abrir almacén" onSubmit={enviar} className="flex flex-col gap-4">
        <div>
          <h2 className="font-medium">Abrir un almacén</h2>
          <p className="text-sm text-tenue">
            Se crea el conteo a ciegas con todos los materiales y se traen los préstamos del Excel
            que siguen abiertos. Mientras no se aplique, el almacén no mueve nada.
          </p>
        </div>
        <div className="grid gap-4 md:grid-cols-[1fr_12rem_2fr_auto] md:items-end">
          <Seleccion
            etiqueta="Almacén"
            opciones={libres.map((a) => ({ valor: a.id, texto: `${a.clave} · ${a.nombre}` }))}
            value={almacen}
            onChange={(e) => setAlmacenId(e.target.value)}
          />
          <Campo
            etiqueta="Fecha del corte"
            type="date"
            max={hoy()}
            value={fecha}
            onChange={(e) => setFecha(e.target.value)}
          />
          <Campo
            etiqueta="Observaciones"
            maxLength={500}
            value={observaciones}
            onChange={(e) => setObservaciones(e.target.value)}
          />
          <Boton type="submit" disabled={!almacen || !fecha || abrir.isPending}>
            Abrir
          </Boton>
        </div>
        {abrir.error && <Aviso>{mensajeDe(abrir.error)}</Aviso>}
      </form>
    </Tarjeta>
  )
}

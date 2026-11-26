import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { CheckCircle2, Circle, Lock, Upload } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Insignia } from '@/components/ui/insignia'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { consultas } from '@/features/estructura/consultas'
import { formatearFecha, formatearNumero } from '@/lib/formato'
import { id } from '@/lib/valores'
import {
  consultasMigracion,
  ESTADOS,
  subirArchivo,
  type ResumenCarga,
  type TipoCargaInfo,
} from './consultas'

const PASOS: Record<number, string> = {
  1: 'Listas',
  2: 'Estructura',
  3: 'Catálogo',
  4: 'Personas',
}

/**
 * Centro de migración: el orden de carga, subir un Excel y la bitácora de cargas. Lo que el
 * sistema no resuelve solo se decide dentro de cada carga.
 */
export function PantallaMigracion() {
  const orden = useQuery(consultasMigracion.orden)
  const cargas = useQuery(consultasMigracion.cargas)

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">Migración desde Excel</h1>
          <p className="text-tenue">
            Sube cada hoja en orden. Lo dudoso queda en cuarentena hasta que alguien decide; nada
            entra al sistema hasta confirmar.
          </p>
        </div>
        <Link to="/migracion/alias" className="text-sm text-marca underline">
          Ver alias
        </Link>
      </header>

      {orden.isError && <Aviso>{mensajeDe(orden.error)}</Aviso>}
      {orden.data && <OrdenDeCarga tipos={orden.data} />}
      {orden.data && <Subir tipos={orden.data} />}

      <Tarjeta className="p-0">
        <h2 className="px-4 pt-4 text-lg font-medium">Bitácora de cargas</h2>
        {cargas.isError && (
          <div className="p-4">
            <Aviso>{mensajeDe(cargas.error)}</Aviso>
          </div>
        )}
        <Tabla
          filas={cargas.data}
          clave={(c) => c.id}
          vacio="Todavía no se ha subido ningún archivo."
          columnas={[
            {
              titulo: 'Carga',
              celda: (c) => (
                <Link
                  to="/migracion/$cargaId"
                  params={{ cargaId: String(c.id) }}
                  className="font-medium text-marca underline"
                >
                  #{c.id} {c.tipoNombre}
                </Link>
              ),
            },
            {
              titulo: 'Archivo',
              celda: (c) => (
                <span title={c.hojas} className="break-all">
                  {c.archivo}
                </span>
              ),
            },
            { titulo: 'Estado', celda: (c) => <EstadoCarga estado={c.estado} /> },
            {
              titulo: 'Renglones',
              celda: (c) => <Totales carga={c} />,
              clase: 'whitespace-nowrap',
            },
            {
              titulo: 'Quién y cuándo',
              celda: (c) => (
                <span className="text-tenue">
                  {c.usuario} · {formatearFecha(c.creada)}
                </span>
              ),
            },
          ]}
        />
      </Tarjeta>
    </div>
  )
}

export function EstadoCarga({ estado }: { estado: string }) {
  const tono = estado === 'confirmada' ? 'exito' : estado === 'revertida' ? 'apagado' : 'neutro'
  return <Insignia tono={tono}>{ESTADOS[estado] ?? estado}</Insignia>
}

export function Totales({ carga }: { carga: ResumenCarga }) {
  return (
    <span className="text-sm">
      {formatearNumero(carga.renglones)}
      {carga.enCuarentena > 0 && (
        <span className="ml-2 font-medium text-peligro">
          {formatearNumero(carga.enCuarentena)} en cuarentena
        </span>
      )}
    </span>
  )
}

function OrdenDeCarga({ tipos }: { tipos: TipoCargaInfo[] }) {
  const pasos = [...new Set(tipos.map((t) => t.paso))]
  return (
    <section aria-label="Orden de carga" className="grid gap-3 md:grid-cols-4">
      {pasos.map((paso) => (
        <Tarjeta key={paso} className="p-4">
          <h2 className="text-sm font-medium text-tenue">
            {paso}. {PASOS[paso]}
          </h2>
          <ul className="mt-2 flex flex-col gap-2">
            {tipos
              .filter((t) => t.paso === paso)
              .map((t) => (
                <li key={t.tipo} className="flex items-start gap-2 text-sm">
                  {t.confirmada ? (
                    <CheckCircle2
                      aria-label="Confirmada"
                      className="mt-0.5 size-4 shrink-0 text-exito"
                    />
                  ) : t.disponible ? (
                    <Circle aria-label="Pendiente" className="mt-0.5 size-4 shrink-0 text-tenue" />
                  ) : (
                    <Lock
                      aria-label="Falta una carga previa"
                      className="mt-0.5 size-4 shrink-0 text-tenue"
                    />
                  )}
                  <span>
                    {t.ultimaCargaId ? (
                      <Link
                        to="/migracion/$cargaId"
                        params={{ cargaId: String(t.ultimaCargaId) }}
                        className="underline"
                      >
                        {t.nombre}
                      </Link>
                    ) : (
                      t.nombre
                    )}
                    <span className="block text-xs text-tenue">{t.origen}</span>
                  </span>
                </li>
              ))}
          </ul>
        </Tarjeta>
      ))}
    </section>
  )
}

function Subir({ tipos }: { tipos: TipoCargaInfo[] }) {
  const cliente = useQueryClient()
  const navegar = useNavigate()
  const disponibles = tipos.filter((t) => t.disponible)
  const [tipo, setTipo] = useState(disponibles[0]?.tipo ?? '')
  const [clienteId, setClienteId] = useState('')
  const [archivo, setArchivo] = useState<File>()
  const elegido = tipos.find((t) => t.tipo === tipo)
  const pideCliente = elegido?.parametros.includes('clienteId') ?? false
  const clientes = useQuery({ ...consultas.clientes, enabled: pideCliente })

  const subir = useMutation({
    mutationFn: async () => {
      if (!archivo) throw new Error('Falta el archivo')
      return subirArchivo(tipo, archivo, pideCliente ? id(clienteId) : undefined)
    },
    onSuccess: (carga) => {
      void cliente.invalidateQueries({ queryKey: ['importaciones'] })
      void navegar({ to: '/migracion/$cargaId', params: { cargaId: String(carga.id) } })
    },
  })

  const enviar = (evento: FormEvent) => {
    evento.preventDefault()
    subir.mutate()
  }

  return (
    <Tarjeta>
      <form aria-label="Subir archivo" onSubmit={enviar} className="flex flex-col gap-4">
        <h2 className="text-lg font-medium">Subir un archivo</h2>
        <div className="grid gap-4 md:grid-cols-[1fr_1fr_1fr_auto] md:items-end">
          <Seleccion
            etiqueta="Qué se carga"
            opciones={disponibles.map((t) => ({ valor: t.tipo, texto: t.nombre }))}
            value={tipo}
            onChange={(e) => setTipo(e.target.value)}
          />
          {pideCliente && (
            <Seleccion
              etiqueta="Cliente de los frentes"
              vacio="Elige…"
              opciones={(clientes.data ?? []).map((c) => ({ valor: c.id, texto: c.nombre }))}
              value={clienteId}
              onChange={(e) => setClienteId(e.target.value)}
            />
          )}
          <div className="flex min-w-0 flex-col gap-1.5">
            <label htmlFor="archivo-excel" className="text-sm font-medium">
              Archivo de Excel (.xlsx)
            </label>
            <input
              id="archivo-excel"
              type="file"
              accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
              className="min-h-11 text-sm"
              onChange={(e) => setArchivo(e.target.files?.[0])}
            />
          </div>
          <Boton
            type="submit"
            disabled={!archivo || !tipo || (pideCliente && !clienteId) || subir.isPending}
          >
            <Upload className="size-4" /> {subir.isPending ? 'Leyendo…' : 'Subir y validar'}
          </Boton>
        </div>
        {elegido && <p className="text-sm text-tenue">Lee: {elegido.origen}.</p>}
        {subir.isError && <Aviso>{mensajeDe(subir.error)}</Aviso>}
      </form>
    </Tarjeta>
  )
}

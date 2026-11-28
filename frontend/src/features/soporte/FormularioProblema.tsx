import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useRouterState } from '@tanstack/react-router'
import { X } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { api, ultimaFalla } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { AreaTexto } from '@/components/ui/area'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Seleccion } from '@/components/ui/seleccion'
import { consultasInventario } from '@/features/inventario/consultas'
import { propuesta, SEVERIDADES, type Incidente, type Severidad } from './consultas'

/**
 * El formulario de "Reportar un problema". Se descarga la primera vez que se abre: el marco no
 * carga las consultas del inventario.
 */
export function Formulario({ alCerrar }: { alCerrar: () => void }) {
  const pantalla = useRouterState({ select: (s) => s.location.href })
  const [inicial] = useState(() => propuesta(pantalla, ultimaFalla()))
  const [severidad, setSeveridad] = useState<Severidad>('mayor')
  const [titulo, setTitulo] = useState('')
  const [descripcion, setDescripcion] = useState(inicial.descripcion)
  const [documento, setDocumento] = useState('')
  const [almacenId, setAlmacenId] = useState('')
  const [enviado, setEnviado] = useState<Incidente>()
  const almacenes = useQuery(consultasInventario.almacenes)
  const cliente = useQueryClient()
  const reportar = useMutation({
    mutationFn: async () =>
      exigir(
        await api.POST('/api/v1/incidentes', {
          body: {
            severidad,
            titulo: titulo.trim(),
            descripcion: descripcion.trim(),
            almacenId: almacenId ? Number(almacenId) : undefined,
            documento: documento.trim() || undefined,
            pantalla: inicial.pantalla,
            peticion: inicial.peticion,
          },
        }),
      ),
    onSuccess: (incidente) => {
      setEnviado(incidente)
      void cliente.invalidateQueries({ queryKey: ['incidentes'] })
    },
  })
  const ayuda = SEVERIDADES.find((s) => s.clave === severidad)?.ayuda
  const enviar = (e: FormEvent) => {
    e.preventDefault()
    reportar.mutate()
  }

  return (
    <div className="fixed inset-0 z-20 flex items-start justify-center overflow-y-auto bg-black/40 p-4">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="titulo-reportar"
        className="w-full max-w-lg rounded-lg bg-white p-6 text-texto shadow-lg"
      >
        <div className="mb-4 flex items-start justify-between gap-4">
          <h2 id="titulo-reportar" className="text-lg font-semibold">
            Reportar un problema
          </h2>
          <Boton variante="fantasma" aria-label="Cerrar" onClick={alCerrar}>
            <X className="size-4" />
          </Boton>
        </div>
        {enviado ? (
          <div className="flex flex-col gap-4">
            <Aviso tipo="exito">
              Quedó como {enviado.folio}. Administración lo revisa; puedes seguirlo en{' '}
              <Link to="/incidentes" className="underline" onClick={alCerrar}>
                Incidentes
              </Link>
              .
            </Aviso>
            <div>
              <Boton onClick={alCerrar}>Listo</Boton>
            </div>
          </div>
        ) : (
          <form onSubmit={enviar} className="flex flex-col gap-4">
            <Seleccion
              etiqueta="¿Qué tan grave es?"
              value={severidad}
              onChange={(e) => setSeveridad(e.target.value as Severidad)}
              opciones={SEVERIDADES.map((s) => ({ valor: s.clave, texto: s.nombre }))}
            />
            {ayuda && <p className="-mt-2 text-sm text-tenue">{ayuda}</p>}
            <Campo
              etiqueta="En pocas palabras"
              required
              minLength={3}
              maxLength={120}
              value={titulo}
              onChange={(e) => setTitulo(e.target.value)}
            />
            <AreaTexto
              etiqueta="Qué pasó y qué estabas haciendo"
              required
              maxLength={2000}
              value={descripcion}
              onChange={(e) => setDescripcion(e.target.value)}
            />
            <div className="grid gap-4 sm:grid-cols-2">
              <Seleccion
                etiqueta="Almacén"
                vacio="—"
                value={almacenId}
                onChange={(e) => setAlmacenId(e.target.value)}
                opciones={(almacenes.data ?? []).map((a) => ({ valor: a.id, texto: a.clave }))}
              />
              <Campo
                etiqueta="Folio del documento"
                maxLength={40}
                placeholder="SAL-2027-000123"
                value={documento}
                onChange={(e) => setDocumento(e.target.value)}
              />
            </div>
            <p className="text-xs text-tenue">
              Se envía también la pantalla ({inicial.pantalla})
              {inicial.peticion ? ` y la petición que falló (${inicial.peticion})` : ''}.
            </p>
            {reportar.isError && <Aviso>{mensajeDe(reportar.error)}</Aviso>}
            <div className="flex gap-2">
              <Boton type="submit" disabled={reportar.isPending}>
                Enviar
              </Boton>
              <Boton variante="secundario" onClick={alCerrar}>
                Cancelar
              </Boton>
            </div>
          </form>
        )}
      </div>
    </div>
  )
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { ArrowLeft } from 'lucide-react'
import { useState } from 'react'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { siCoincide } from '@/features/estructura/consultas'
import { useDiferido } from '@/lib/diferido'
import { PERMISOS, puede, useSesion } from '@/sesion/sesion'
import { consultasMigracion, ENTIDADES, type AliasCarga } from './consultas'
import { BuscarDestino } from './Decidir'

const ORIGENES: Record<string, string> = {
  regla: 'Por regla',
  sugerencia_aceptada: 'Sugerencia aceptada',
  manual: 'Elegido a mano',
}

/**
 * Cómo se tradujo cada nombre del Excel: "BLADIMIR GCIA" → García Cruz Bladimir León. Corregir un
 * alias cambia lo que harán las siguientes cargas; olvidarlo hace que se vuelva a preguntar.
 */
export function PantallaAlias() {
  const sesion = useSesion()
  const cliente = useQueryClient()
  const [entidad, setEntidad] = useState('')
  const [busqueda, setBusqueda] = useState('')
  const [elegido, setElegido] = useState<AliasCarga>()
  const q = useDiferido(busqueda.trim(), 250)
  const alias = useQuery(consultasMigracion.alias(entidad || undefined, q || undefined))
  const entidades = Object.entries(ENTIDADES).filter(
    ([clave]) => clave !== 'persona' || puede(sesion, PERMISOS.personasAdministrar),
  )

  const refrescar = () => {
    setElegido(undefined)
    void cliente.invalidateQueries({ queryKey: ['alias'] })
  }
  const cambiar = useMutation({
    mutationFn: async ({ a, destinoId }: { a: AliasCarga; destinoId: number }) =>
      exigir(
        await api.PATCH('/api/v1/alias/{id}', {
          params: { path: { id: a.id }, header: siCoincide(a.version) },
          body: { destinoId },
        }),
      ),
    onSuccess: refrescar,
  })
  const olvidar = useMutation({
    mutationFn: async (a: AliasCarga) =>
      exigir(await api.DELETE('/api/v1/alias/{id}', { params: { path: { id: a.id } } })),
    onSuccess: refrescar,
  })

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header className="flex flex-col gap-2">
        <Link
          to="/migracion"
          className="flex items-center gap-1 text-sm text-tenue hover:text-texto"
        >
          <ArrowLeft className="size-4" /> Migración
        </Link>
        <h1 className="text-2xl font-semibold">Alias</h1>
        <p className="text-tenue">
          Cómo se escribía algo en el Excel y qué registro es en el sistema. Lo que se decide en la
          cuarentena queda aquí y la siguiente carga ya no pregunta.
        </p>
      </header>
      <div className="grid gap-4 md:grid-cols-[1fr_2fr] md:items-end">
        <Seleccion
          etiqueta="De qué"
          vacio="Todo"
          opciones={entidades.map(([valor, texto]) => ({ valor, texto }))}
          value={entidad}
          onChange={(e) => setEntidad(e.target.value)}
        />
        <Campo
          etiqueta="Buscar texto del Excel"
          type="search"
          value={busqueda}
          onChange={(e) => setBusqueda(e.target.value)}
        />
      </div>
      {elegido && (
        <Tarjeta className="flex flex-col gap-3">
          <h2 className="text-lg font-medium">
            Ligar “{elegido.texto}” con otro {ENTIDADES[elegido.entidad]?.toLowerCase()}
          </h2>
          <BuscarDestino
            entidad={elegido.entidad}
            ocupado={cambiar.isPending}
            textoBoton="Guardar"
            alElegir={(destinoId) => cambiar.mutate({ a: elegido, destinoId })}
          />
          <div>
            <Boton variante="fantasma" onClick={() => setElegido(undefined)}>
              Cancelar
            </Boton>
          </div>
        </Tarjeta>
      )}
      {(cambiar.isError || olvidar.isError) && (
        <Aviso>{mensajeDe(cambiar.error ?? olvidar.error)}</Aviso>
      )}
      <Tarjeta className="p-0">
        {alias.isError && (
          <div className="p-4">
            <Aviso>{mensajeDe(alias.error)}</Aviso>
          </div>
        )}
        <Tabla
          filas={alias.data}
          clave={(a) => a.id}
          seleccionada={elegido?.id}
          vacio="Todavía no hay alias: se crean al resolver la cuarentena."
          columnas={[
            { titulo: 'De qué', celda: (a) => ENTIDADES[a.entidad] ?? a.entidad },
            { titulo: 'En el Excel', celda: (a) => <span className="font-mono">{a.texto}</span> },
            {
              titulo: 'En el sistema',
              celda: (a) => a.destino ?? <span className="text-peligro">Ya no existe</span>,
            },
            {
              titulo: 'Origen',
              celda: (a) => (
                <span className="text-tenue">
                  {ORIGENES[a.origen] ?? a.origen}
                  {a.usuario ? ` · ${a.usuario}` : ''}
                  {a.cargaId ? ` · carga #${a.cargaId}` : ''}
                </span>
              ),
            },
          ]}
          acciones={(a) => (
            <div className="flex justify-end gap-1">
              <Boton variante="fantasma" onClick={() => setElegido(a)}>
                Cambiar
              </Boton>
              <Boton
                variante="fantasma"
                disabled={olvidar.isPending}
                onClick={() => olvidar.mutate(a)}
              >
                Olvidar
              </Boton>
            </div>
          )}
        />
      </Tarjeta>
    </div>
  )
}

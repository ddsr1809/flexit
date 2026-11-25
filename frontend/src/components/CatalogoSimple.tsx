import {
  useMutation,
  useQueryClient,
  type QueryKey,
  type UseQueryResult,
} from '@tanstack/react-query'
import { Pencil } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { ErrorApi, mensajeDe } from '@/api/errores'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Seleccion, type Opcion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import type { Valores } from '@/lib/valores'
import { Tabla, type Columna } from './Tabla'

export interface CampoFormulario {
  nombre: string
  etiqueta: string
  tipo?: 'texto' | 'numero' | 'fecha' | 'seleccion' | 'casilla'
  opciones?: Opcion[]
  /** Texto de la opción vacía en una selección. */
  vacio?: string
  ayuda?: string
  /** Solo se muestra al editar (por ejemplo, "Activo"). */
  soloEdicion?: boolean
  /** No se puede cambiar después del alta. */
  soloAlta?: boolean
}

interface Props<F extends { id: number; version: number }> {
  titulo: string
  descripcion?: string
  consulta: UseQueryResult<F[]>
  claveConsulta: QueryKey
  columnas: Columna<F>[]
  /** Cómo se nombra una fila para lectores de pantalla ("Editar Ruba"). */
  nombre: (fila: F) => string
  campos: CampoFormulario[]
  inicial: Valores
  aValores: (fila: F) => Valores
  /** Alta si `editando` no viene; cambio con su versión si viene. */
  guardar: (valores: Valores, editando?: F) => Promise<unknown>
  puedeEditar: boolean
  /** Filas que no se pueden cambiar (por ejemplo, las del sistema). */
  editable?: (fila: F) => boolean
  textoAlta: string
}

/**
 * Catálogo de una tabla: lista, alta y cambio en la misma pantalla. Sirve para clientes, frentes,
 * prototipos, unidades, categorías y proveedores; los errores por campo del backend se muestran
 * junto a cada campo.
 */
export function CatalogoSimple<F extends { id: number; version: number }>({
  titulo,
  descripcion,
  consulta,
  claveConsulta,
  columnas,
  nombre,
  campos,
  inicial,
  aValores,
  guardar,
  puedeEditar,
  editable = () => true,
  textoAlta,
}: Props<F>) {
  const cliente = useQueryClient()
  const [valores, setValores] = useState<Valores>(inicial)
  const [editando, setEditando] = useState<F>()
  const [aviso, setAviso] = useState<string>()

  const mutacion = useMutation({
    mutationFn: () => guardar(valores, editando),
    onSuccess: () => {
      setAviso(editando ? 'Cambios guardados.' : 'Alta guardada.')
      setEditando(undefined)
      setValores(inicial)
      void cliente.invalidateQueries({ queryKey: claveConsulta })
    },
    onError: (error) => {
      setAviso(undefined)
      if (error instanceof ErrorApi && error.codigo === 'VERSION_DESACTUALIZADA') {
        void cliente.invalidateQueries({ queryKey: claveConsulta })
        setEditando(undefined)
      }
    },
  })

  const errores = mutacion.error instanceof ErrorApi ? mutacion.error.campos : {}
  const cambiar = (nombre: string, valor: string | boolean) =>
    setValores((anteriores) => ({ ...anteriores, [nombre]: valor }))

  const editar = (fila: F) => {
    mutacion.reset()
    setAviso(undefined)
    setEditando(fila)
    setValores({ ...inicial, ...aValores(fila) })
  }

  const cancelar = () => {
    mutacion.reset()
    setEditando(undefined)
    setValores(inicial)
  }

  const enviar = (evento: FormEvent) => {
    evento.preventDefault()
    mutacion.mutate()
  }

  return (
    <section className="flex flex-col gap-4" aria-label={titulo}>
      {descripcion && <p className="text-sm text-tenue">{descripcion}</p>}
      <Tarjeta className="p-0">
        {consulta.isError && (
          <div className="p-4">
            <Aviso>{mensajeDe(consulta.error)}</Aviso>
          </div>
        )}
        {consulta.isPending && <p className="p-4 text-sm text-tenue">Cargando…</p>}
        <Tabla
          filas={consulta.data}
          columnas={columnas}
          clave={(fila) => fila.id}
          seleccionada={editando?.id}
          acciones={
            puedeEditar
              ? (fila) =>
                  editable(fila) && (
                    <Boton
                      variante="fantasma"
                      onClick={() => editar(fila)}
                      aria-label={`Editar ${nombre(fila)}`}
                    >
                      <Pencil className="size-4" /> Editar
                    </Boton>
                  )
              : undefined
          }
        />
      </Tarjeta>

      {puedeEditar && (
        <Tarjeta>
          <h2 className="text-lg font-medium">{editando ? 'Cambiar' : textoAlta}</h2>
          <form noValidate onSubmit={enviar} className="mt-4 grid gap-4 md:grid-cols-2">
            {campos
              .filter((campo) => (editando ? true : !campo.soloEdicion))
              .map((campo) => {
                const valor = valores[campo.nombre]
                const bloqueado = Boolean(editando && campo.soloAlta)
                if (campo.tipo === 'casilla') {
                  return (
                    <label
                      key={campo.nombre}
                      className="flex min-h-11 items-center gap-2 self-end text-sm"
                    >
                      <input
                        type="checkbox"
                        className="size-4"
                        checked={valor === true}
                        disabled={bloqueado}
                        onChange={(e) => cambiar(campo.nombre, e.target.checked)}
                      />
                      {campo.etiqueta}
                    </label>
                  )
                }
                if (campo.tipo === 'seleccion') {
                  return (
                    <Seleccion
                      key={campo.nombre}
                      etiqueta={campo.etiqueta}
                      opciones={campo.opciones ?? []}
                      vacio={campo.vacio}
                      value={typeof valor === 'string' ? valor : ''}
                      disabled={bloqueado}
                      error={errores[campo.nombre]}
                      onChange={(e) => cambiar(campo.nombre, e.target.value)}
                    />
                  )
                }
                return (
                  <Campo
                    key={campo.nombre}
                    etiqueta={campo.etiqueta}
                    type={campo.tipo === 'fecha' ? 'date' : 'text'}
                    inputMode={campo.tipo === 'numero' ? 'decimal' : undefined}
                    value={typeof valor === 'string' ? valor : ''}
                    disabled={bloqueado}
                    ayuda={campo.ayuda}
                    error={errores[campo.nombre]}
                    onChange={(e) => cambiar(campo.nombre, e.target.value)}
                  />
                )
              })}
            <div className="flex flex-col gap-3 md:col-span-2">
              {mutacion.isError && <Aviso>{mensajeDe(mutacion.error)}</Aviso>}
              {aviso && !mutacion.isError && <Aviso tipo="exito">{aviso}</Aviso>}
              <div className="flex gap-2">
                <Boton type="submit" disabled={mutacion.isPending}>
                  {mutacion.isPending ? 'Guardando…' : editando ? 'Guardar cambios' : 'Dar de alta'}
                </Boton>
                {editando && (
                  <Boton variante="secundario" onClick={cancelar}>
                    Cancelar
                  </Boton>
                )}
              </div>
            </div>
          </form>
        </Tarjeta>
      )}
    </section>
  )
}

import { useQuery } from '@tanstack/react-query'
import { useId, useState, type KeyboardEvent } from 'react'
import { useDiferido } from '@/lib/diferido'
import { cn } from '@/lib/clases'

/**
 * Campo de búsqueda con sugerencias, hecho para el teclado: se escribe, las flechas eligen y Enter
 * toma la sugerencia marcada (la primera si no se movió). Escape cierra la lista.
 */
export function Buscador<T>({
  etiqueta,
  ocultarEtiqueta,
  placeholder,
  nombre,
  buscar,
  texto,
  clave,
  detalle,
  valor,
  alElegir,
  id,
  autoFocus,
}: {
  etiqueta: string
  ocultarEtiqueta?: boolean
  placeholder?: string
  /** Nombre de la búsqueda para la caché de consultas: 'materiales', 'personas'… */
  nombre: string
  buscar: (q: string) => Promise<T[]>
  texto: (elemento: T) => string
  clave: (elemento: T) => number
  detalle?: (elemento: T) => string
  /** Texto del elemento elegido, para mostrarlo cuando no se está buscando. */
  valor?: string
  alElegir: (elemento: T) => void
  id?: string
  autoFocus?: boolean
}) {
  const generado = useId()
  const idCampo = id ?? generado
  const idLista = `${idCampo}-lista`
  const [escrito, setEscrito] = useState<string>()
  const [marcado, setMarcado] = useState(0)
  const q = useDiferido((escrito ?? '').trim(), 200)
  const resultados = useQuery({
    queryKey: ['buscador', nombre, q],
    queryFn: () => buscar(q),
    enabled: q.length >= 2,
    staleTime: 60_000,
  })
  const sugerencias =
    escrito !== undefined && q.length >= 2 ? (resultados.data ?? []).slice(0, 8) : []
  const abierta = sugerencias.length > 0

  const elegir = (elemento: T) => {
    alElegir(elemento)
    setEscrito(undefined)
    setMarcado(0)
  }

  const teclas = (evento: KeyboardEvent<HTMLInputElement>) => {
    if (evento.key === 'ArrowDown' && abierta) {
      evento.preventDefault()
      setMarcado((m) => Math.min(m + 1, sugerencias.length - 1))
    } else if (evento.key === 'ArrowUp' && abierta) {
      evento.preventDefault()
      setMarcado((m) => Math.max(m - 1, 0))
    } else if (evento.key === 'Enter' && escrito !== undefined) {
      evento.preventDefault()
      const elegido = sugerencias[marcado]
      if (elegido) elegir(elegido)
    } else if (evento.key === 'Escape') {
      setEscrito(undefined)
    }
  }

  return (
    <div className="relative flex min-w-0 flex-col gap-1.5">
      <label htmlFor={idCampo} className={cn('text-sm font-medium', ocultarEtiqueta && 'sr-only')}>
        {etiqueta}
      </label>
      <input
        id={idCampo}
        role="combobox"
        aria-expanded={abierta}
        aria-controls={idLista}
        aria-autocomplete="list"
        autoComplete="off"
        autoFocus={autoFocus}
        placeholder={placeholder}
        value={escrito ?? valor ?? ''}
        onChange={(e) => {
          setEscrito(e.target.value)
          setMarcado(0)
        }}
        onKeyDown={teclas}
        onBlur={() => setTimeout(() => setEscrito(undefined), 150)}
        className="min-h-11 w-full rounded-md border border-borde bg-white px-3 text-base outline-none focus:border-marca focus:ring-2 focus:ring-marca/20"
      />
      {abierta && (
        <ul
          id={idLista}
          role="listbox"
          className="absolute top-full z-20 mt-1 max-h-72 w-full min-w-72 overflow-auto rounded-md border border-borde bg-white py-1 shadow-lg"
        >
          {sugerencias.map((elemento, indice) => (
            <li
              key={clave(elemento)}
              role="option"
              aria-selected={indice === marcado}
              onMouseDown={(e) => {
                e.preventDefault()
                elegir(elemento)
              }}
              className="cursor-pointer px-3 py-2 text-sm aria-selected:bg-marca/10"
            >
              <span>{texto(elemento)}</span>
              {detalle && <span className="ml-2 text-tenue">{detalle(elemento)}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

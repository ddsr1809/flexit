import { useId, type SelectHTMLAttributes } from 'react'
import { cn } from '@/lib/clases'

export interface Opcion {
  valor: string | number
  texto: string
}

type Props = SelectHTMLAttributes<HTMLSelectElement> & {
  etiqueta: string
  opciones: Opcion[]
  /** Texto de la opción vacía; sin él no hay opción vacía. */
  vacio?: string
  error?: string
}

/** Lista desplegable con etiqueta y error ligados por aria, igual que Campo. */
export function Seleccion({ etiqueta, opciones, vacio, error, className, id, ...props }: Props) {
  const generado = useId()
  const idCampo = id ?? generado
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <label htmlFor={idCampo} className="text-sm font-medium">
        {etiqueta}
      </label>
      <select
        id={idCampo}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${idCampo}-error` : undefined}
        className={cn(
          'min-h-11 w-full min-w-0 rounded-md border border-borde bg-white px-3 text-base outline-none focus:border-marca focus:ring-2 focus:ring-marca/20',
          error && 'border-peligro',
          className,
        )}
        {...props}
      >
        {vacio !== undefined && <option value="">{vacio}</option>}
        {opciones.map((opcion) => (
          <option key={opcion.valor} value={opcion.valor}>
            {opcion.texto}
          </option>
        ))}
      </select>
      {error && (
        <p id={`${idCampo}-error`} className="text-sm text-peligro">
          {error}
        </p>
      )}
    </div>
  )
}

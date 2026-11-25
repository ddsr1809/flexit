import { useId, type InputHTMLAttributes, type ReactNode } from 'react'
import { cn } from '@/lib/clases'

type Props = InputHTMLAttributes<HTMLInputElement> & {
  etiqueta: string
  error?: string
  ayuda?: ReactNode
}

/** Campo con etiqueta, ayuda y mensaje de error ligados por aria para lectores de pantalla. */
export function Campo({ etiqueta, error, ayuda, className, id, ...props }: Props) {
  const generado = useId()
  const idCampo = id ?? generado
  const idDescripcion = `${idCampo}-descripcion`
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={idCampo} className="text-sm font-medium">
        {etiqueta}
      </label>
      <input
        id={idCampo}
        aria-invalid={error ? true : undefined}
        aria-describedby={error || ayuda ? idDescripcion : undefined}
        className={cn(
          'min-h-11 rounded-md border border-borde bg-white px-3 text-base outline-none focus:border-marca focus:ring-2 focus:ring-marca/20',
          error && 'border-peligro',
          className,
        )}
        {...props}
      />
      {(error || ayuda) && (
        <p id={idDescripcion} className={cn('text-sm', error ? 'text-peligro' : 'text-tenue')}>
          {error ?? ayuda}
        </p>
      )}
    </div>
  )
}

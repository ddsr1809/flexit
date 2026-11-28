import { useId, type TextareaHTMLAttributes } from 'react'
import { cn } from '@/lib/clases'

type Props = TextareaHTMLAttributes<HTMLTextAreaElement> & {
  etiqueta: string
  error?: string
  ayuda?: string
}

/** Texto de varias líneas con etiqueta, ayuda y error ligados por aria, igual que Campo. */
export function AreaTexto({ etiqueta, error, ayuda, className, id, ...props }: Props) {
  const generado = useId()
  const idCampo = id ?? generado
  const idDescripcion = `${idCampo}-descripcion`
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={idCampo} className="text-sm font-medium">
        {etiqueta}
      </label>
      <textarea
        id={idCampo}
        rows={4}
        aria-invalid={error ? true : undefined}
        aria-describedby={error || ayuda ? idDescripcion : undefined}
        className={cn(
          'rounded-md border border-borde bg-white px-3 py-2 text-base outline-none focus:border-marca focus:ring-2 focus:ring-marca/20',
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

import type { ReactNode } from 'react'
import { cn } from '@/lib/clases'

export function Aviso({
  tipo = 'error',
  children,
}: {
  tipo?: 'error' | 'exito'
  children: ReactNode
}) {
  return (
    <div
      role={tipo === 'error' ? 'alert' : 'status'}
      className={cn(
        'rounded-md border px-3 py-2 text-sm',
        tipo === 'error'
          ? 'border-peligro/30 bg-peligro/5 text-peligro'
          : 'border-exito/30 bg-exito/5 text-exito',
      )}
    >
      {children}
    </div>
  )
}

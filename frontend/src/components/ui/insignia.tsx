import type { ReactNode } from 'react'
import { cn } from '@/lib/clases'

/** Etiqueta corta de estado: Activo, Inactivo, Del sistema. */
export function Insignia({
  tono = 'neutro',
  children,
}: {
  tono?: 'neutro' | 'exito' | 'apagado'
  children: ReactNode
}) {
  return (
    <span
      className={cn(
        'inline-flex rounded-full px-2 py-0.5 text-xs font-medium',
        tono === 'exito' && 'bg-exito/10 text-exito',
        tono === 'neutro' && 'bg-marca/10 text-marca',
        tono === 'apagado' && 'bg-fondo text-tenue',
      )}
    >
      {children}
    </span>
  )
}

export const EstadoActivo = ({ activo }: { activo: boolean }) =>
  activo ? <Insignia tono="exito">Activo</Insignia> : <Insignia tono="apagado">Inactivo</Insignia>

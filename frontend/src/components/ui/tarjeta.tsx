import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/clases'

export function Tarjeta({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('rounded-lg border border-borde bg-white p-6 shadow-sm', className)}
      {...props}
    />
  )
}

import { cva, type VariantProps } from 'class-variance-authority'
import type { ButtonHTMLAttributes } from 'react'
import { cn } from '@/lib/clases'

const variantes = cva(
  'inline-flex min-h-11 items-center justify-center gap-2 rounded-md px-4 text-sm font-medium transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-marca disabled:cursor-not-allowed disabled:opacity-60',
  {
    variants: {
      variante: {
        principal: 'bg-marca text-white hover:bg-marca-claro',
        secundario: 'border border-borde bg-white text-texto hover:bg-fondo',
        fantasma: 'text-tenue hover:bg-fondo hover:text-texto',
      },
    },
    defaultVariants: { variante: 'principal' },
  },
)

type Props = ButtonHTMLAttributes<HTMLButtonElement> & VariantProps<typeof variantes>

export function Boton({ className, variante, type = 'button', ...props }: Props) {
  return <button type={type} className={cn(variantes({ variante }), className)} {...props} />
}

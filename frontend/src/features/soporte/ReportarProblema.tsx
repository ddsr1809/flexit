import { LifeBuoy } from 'lucide-react'
import { lazy, Suspense, useState } from 'react'
import { Boton } from '@/components/ui/boton'

const Formulario = lazy(() =>
  import('./FormularioProblema').then((m) => ({ default: m.Formulario })),
)

/**
 * "Reportar un problema": está en todas las pantallas. Lleva sola la pantalla y, si una petición
 * acaba de fallar, su identificador (X-Request-Id) para encontrar el error en los logs.
 */
export function ReportarProblema({ className }: { className?: string }) {
  const [abierto, setAbierto] = useState(false)
  return (
    <>
      <Boton variante="fantasma" className={className} onClick={() => setAbierto(true)}>
        <LifeBuoy className="size-4" /> Reportar un problema
      </Boton>
      {abierto && (
        <Suspense fallback={null}>
          <Formulario alCerrar={() => setAbierto(false)} />
        </Suspense>
      )}
    </>
  )
}

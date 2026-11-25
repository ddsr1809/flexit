import { Tarjeta } from '@/components/ui/tarjeta'
import { PieVersion } from '@/features/sesion/PieVersion'
import { DESCRIPCION_PERMISO, useSesion } from '@/sesion/sesion'

export function PantallaInicio() {
  const sesion = useSesion()
  if (!sesion) return null
  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold">Hola, {sesion.nombre.split(' ')[0]}</h1>
        <p className="text-tenue">{sesion.empresa}</p>
      </header>
      <Tarjeta>
        <h2 className="font-medium">Lo que puedes hacer</h2>
        <ul className="mt-3 flex flex-wrap gap-2">
          {sesion.permisos.map((permiso) => (
            <li key={permiso} className="rounded-full bg-fondo px-3 py-1 text-sm text-tenue">
              {DESCRIPCION_PERMISO[permiso] ?? permiso}
            </li>
          ))}
        </ul>
        <p className="mt-4 text-sm text-tenue">
          Catálogos y contratos llegan en el hito H2; existencias y vales, en H4.
        </p>
      </Tarjeta>
      <PieVersion />
    </div>
  )
}

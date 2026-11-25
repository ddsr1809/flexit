import { Link, Outlet, useNavigate } from '@tanstack/react-router'
import { LogOut } from 'lucide-react'
import { Boton } from '@/components/ui/boton'
import { useCerrarSesion, useSesion } from '@/sesion/sesion'
import { menuPara } from './menu'

export function Marco() {
  const sesion = useSesion()
  const cerrar = useCerrarSesion()
  const navegar = useNavigate()
  if (!sesion) return null

  const salir = () => cerrar.mutate(undefined, { onSettled: () => navegar({ to: '/login' }) })

  return (
    <div className="flex min-h-full flex-col md:flex-row">
      <aside className="flex flex-col bg-marca text-white md:w-60">
        <div className="px-4 py-4">
          <p className="text-sm font-semibold">{sesion.empresa}</p>
          <p className="text-xs text-white/70">
            {sesion.nombre} · {sesion.roles.map((r) => r.nombre).join(', ')}
          </p>
        </div>
        <nav aria-label="Principal" className="flex gap-1 overflow-x-auto px-2 pb-2 md:flex-col">
          {menuPara(sesion).map(({ ruta, texto, icono: Icono, hito }) =>
            hito ? (
              <span
                key={ruta}
                aria-disabled="true"
                title={`Llega en el hito ${hito}`}
                className="flex min-h-11 items-center gap-2 rounded-md px-3 text-sm text-white/40"
              >
                <Icono className="size-4" /> {texto}
              </span>
            ) : (
              <Link
                key={ruta}
                to={ruta}
                activeOptions={{ exact: true }}
                className="flex min-h-11 items-center gap-2 rounded-md px-3 text-sm hover:bg-white/10 data-[status=active]:bg-white/15 data-[status=active]:font-medium"
              >
                <Icono className="size-4" /> {texto}
              </Link>
            ),
          )}
        </nav>
        <div className="mt-auto hidden p-2 md:block">
          <Boton
            variante="fantasma"
            className="w-full justify-start text-white/80 hover:bg-white/10 hover:text-white"
            onClick={salir}
          >
            <LogOut className="size-4" /> Cerrar sesión
          </Boton>
        </div>
      </aside>
      <main className="flex-1 p-4 md:p-8">
        <div className="mb-4 flex justify-end md:hidden">
          <Boton variante="secundario" onClick={salir}>
            <LogOut className="size-4" /> Salir
          </Boton>
        </div>
        <Outlet />
      </main>
    </div>
  )
}

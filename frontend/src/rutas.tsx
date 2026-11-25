import type { QueryClient } from '@tanstack/react-query'
import {
  createRootRouteWithContext,
  createRoute,
  createRouter,
  Outlet,
  redirect,
  useNavigate,
} from '@tanstack/react-router'
import { Marco } from '@/components/Marco'
import { PantallaInicio } from '@/features/inicio/PantallaInicio'
import { PantallaLogin } from '@/features/sesion/PantallaLogin'
import { PantallaUsuarios } from '@/features/usuarios/PantallaUsuarios'
import { consultaSesion, PERMISOS, puede } from '@/sesion/sesion'

interface Contexto {
  queryClient: QueryClient
}

const raiz = createRootRouteWithContext<Contexto>()({
  component: Outlet,
  notFoundComponent: () => (
    <main className="p-8">
      <h1 className="text-xl font-semibold">No encontramos esa pantalla</h1>
    </main>
  ),
})

function RutaLogin() {
  const navegar = useNavigate()
  return <PantallaLogin alEntrar={() => navegar({ to: '/' })} />
}

const login = createRoute({
  getParentRoute: () => raiz,
  path: '/login',
  beforeLoad: async ({ context }) => {
    if (await context.queryClient.ensureQueryData(consultaSesion)) throw redirect({ to: '/' })
  },
  component: RutaLogin,
})

/** Todo lo que cuelga de aquí exige sesión. */
const privado = createRoute({
  getParentRoute: () => raiz,
  id: 'privado',
  beforeLoad: async ({ context }) => {
    const sesion = await context.queryClient.ensureQueryData(consultaSesion)
    if (!sesion) throw redirect({ to: '/login' })
    return { sesion }
  },
  component: Marco,
})

const inicio = createRoute({
  getParentRoute: () => privado,
  path: '/',
  component: PantallaInicio,
})

const usuarios = createRoute({
  getParentRoute: () => privado,
  path: '/usuarios',
  beforeLoad: ({ context }) => {
    if (!puede(context.sesion, PERMISOS.usuariosAdministrar)) throw redirect({ to: '/' })
  },
  component: PantallaUsuarios,
})

const arbol = raiz.addChildren([login, privado.addChildren([inicio, usuarios])])

export function crearEnrutador(queryClient: QueryClient) {
  return createRouter({ routeTree: arbol, context: { queryClient }, defaultPreload: 'intent' })
}

declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof crearEnrutador>
  }
}

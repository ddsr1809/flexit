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
import { PantallaMateriales } from '@/features/catalogo/PantallaMateriales'
import { PantallaProveedores } from '@/features/catalogo/PantallaProveedores'
import { PantallaCatalogos } from '@/features/estructura/PantallaCatalogos'
import { PantallaContrato } from '@/features/estructura/PantallaContrato'
import { PantallaContratos } from '@/features/estructura/PantallaContratos'
import { PantallaPaquetes } from '@/features/estructura/PantallaPaquetes'
import { PantallaInicio } from '@/features/inicio/PantallaInicio'
import { PantallaAlias } from '@/features/migracion/PantallaAlias'
import { PantallaCarga } from '@/features/migracion/PantallaCarga'
import { PantallaMigracion } from '@/features/migracion/PantallaMigracion'
import { PantallaPersonas } from '@/features/personas/PantallaPersonas'
import { PantallaLogin } from '@/features/sesion/PantallaLogin'
import { PantallaUsuarios } from '@/features/usuarios/PantallaUsuarios'
import { consultaSesion, PERMISOS, puede, type Permiso } from '@/sesion/sesion'

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

/** Ruta privada que además exige un permiso; sin él, regresa al inicio. */
const conPermiso =
  (permiso: Permiso) =>
  ({ context }: { context: { sesion: Parameters<typeof puede>[0] } }) => {
    if (!puede(context.sesion, permiso)) throw redirect({ to: '/' })
  }

const contratos = createRoute({
  getParentRoute: () => privado,
  path: '/contratos',
  component: PantallaContratos,
})

function RutaContrato() {
  const { contratoId } = contrato.useParams()
  return <PantallaContrato key={contratoId} contratoId={Number(contratoId)} />
}

const contrato = createRoute({
  getParentRoute: () => privado,
  path: '/contratos/$contratoId',
  component: RutaContrato,
})

const paquetes = createRoute({
  getParentRoute: () => privado,
  path: '/paquetes',
  beforeLoad: conPermiso(PERMISOS.estructuraVerTodo),
  component: PantallaPaquetes,
})

const catalogos = createRoute({
  getParentRoute: () => privado,
  path: '/catalogos',
  beforeLoad: conPermiso(PERMISOS.estructuraVerTodo),
  component: PantallaCatalogos,
})

const materiales = createRoute({
  getParentRoute: () => privado,
  path: '/materiales',
  component: PantallaMateriales,
})

const proveedores = createRoute({
  getParentRoute: () => privado,
  path: '/proveedores',
  beforeLoad: conPermiso(PERMISOS.catalogoEditar),
  component: PantallaProveedores,
})

const migracion = createRoute({
  getParentRoute: () => privado,
  path: '/migracion',
  beforeLoad: conPermiso(PERMISOS.administrar),
  component: PantallaMigracion,
})

const alias = createRoute({
  getParentRoute: () => privado,
  path: '/migracion/alias',
  beforeLoad: conPermiso(PERMISOS.administrar),
  component: PantallaAlias,
})

function RutaCarga() {
  const { cargaId } = carga.useParams()
  return <PantallaCarga key={cargaId} cargaId={Number(cargaId)} />
}

const carga = createRoute({
  getParentRoute: () => privado,
  path: '/migracion/$cargaId',
  beforeLoad: conPermiso(PERMISOS.administrar),
  component: RutaCarga,
})

const personas = createRoute({
  getParentRoute: () => privado,
  path: '/personas',
  beforeLoad: conPermiso(PERMISOS.personasAdministrar),
  component: PantallaPersonas,
})

const arbol = raiz.addChildren([
  login,
  privado.addChildren([
    inicio,
    contratos,
    contrato,
    paquetes,
    catalogos,
    materiales,
    proveedores,
    migracion,
    alias,
    carga,
    personas,
    usuarios,
  ]),
])

export function crearEnrutador(queryClient: QueryClient) {
  return createRouter({ routeTree: arbol, context: { queryClient }, defaultPreload: 'intent' })
}

declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof crearEnrutador>
  }
}

import type { QueryClient } from '@tanstack/react-query'
import {
  createRootRouteWithContext,
  createRoute,
  createRouter,
  lazyRouteComponent,
  Outlet,
  redirect,
  useNavigate,
} from '@tanstack/react-router'
import { Marco } from '@/components/Marco'
import { PantallaInicio } from '@/features/inicio/PantallaInicio'
import { PantallaLogin } from '@/features/sesion/PantallaLogin'
import { consultaSesion, PERMISOS, puede, puedeAlguno, type Permiso } from '@/sesion/sesion'

// Cada pantalla se descarga la primera vez que se abre (y se adelanta al pasar el cursor por su
// enlace): el JS inicial solo lleva el marco, el inicio de sesión y las bibliotecas comunes.
const PantallaMateriales = lazyRouteComponent(
  () => import('@/features/catalogo/PantallaMateriales'),
  'PantallaMateriales',
)
const PantallaProveedores = lazyRouteComponent(
  () => import('@/features/catalogo/PantallaProveedores'),
  'PantallaProveedores',
)
const PantallaCatalogos = lazyRouteComponent(
  () => import('@/features/estructura/PantallaCatalogos'),
  'PantallaCatalogos',
)
const PantallaContrato = lazyRouteComponent(
  () => import('@/features/estructura/PantallaContrato'),
  'PantallaContrato',
)
const PantallaContratos = lazyRouteComponent(
  () => import('@/features/estructura/PantallaContratos'),
  'PantallaContratos',
)
const PantallaPaquetes = lazyRouteComponent(
  () => import('@/features/estructura/PantallaPaquetes'),
  'PantallaPaquetes',
)
const PantallaAlias = lazyRouteComponent(
  () => import('@/features/migracion/PantallaAlias'),
  'PantallaAlias',
)
const PantallaCarga = lazyRouteComponent(
  () => import('@/features/migracion/PantallaCarga'),
  'PantallaCarga',
)
const PantallaMigracion = lazyRouteComponent(
  () => import('@/features/migracion/PantallaMigracion'),
  'PantallaMigracion',
)
const PantallaPersonas = lazyRouteComponent(
  () => import('@/features/personas/PantallaPersonas'),
  'PantallaPersonas',
)
const PantallaUsuarios = lazyRouteComponent(
  () => import('@/features/usuarios/PantallaUsuarios'),
  'PantallaUsuarios',
)
const PantallaExistencias = lazyRouteComponent(
  () => import('@/features/inventario/PantallaExistencias'),
  'PantallaExistencias',
)
const PantallaKardex = lazyRouteComponent(
  () => import('@/features/inventario/PantallaKardex'),
  'PantallaKardex',
)
const PantallaMovimientos = lazyRouteComponent(
  () => import('@/features/inventario/PantallaMovimientos'),
  'PantallaMovimientos',
)
const PantallaMovimiento = lazyRouteComponent(
  () => import('@/features/inventario/PantallaMovimiento'),
  'PantallaMovimiento',
)
const PantallaCaptura = lazyRouteComponent(
  () => import('@/features/inventario/PantallaCaptura'),
  'PantallaCaptura',
)
const PantallaAutorizaciones = lazyRouteComponent(
  () => import('@/features/inventario/PantallaAutorizaciones'),
  'PantallaAutorizaciones',
)
const PantallaPrestamos = lazyRouteComponent(
  () => import('@/features/inventario/PantallaPrestamos'),
  'PantallaPrestamos',
)
const PantallaConteos = lazyRouteComponent(
  () => import('@/features/inventario/PantallaConteos'),
  'PantallaConteos',
)
const PantallaConteo = lazyRouteComponent(
  () => import('@/features/inventario/PantallaConteo'),
  'PantallaConteo',
)
const PantallaCierre = lazyRouteComponent(
  () => import('@/features/inventario/PantallaCierre'),
  'PantallaCierre',
)

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
  pendingComponent: () => <p className="p-8 text-tenue">Cargando…</p>,
})

const inicio = createRoute({
  getParentRoute: () => privado,
  path: '/',
  component: PantallaInicio,
})

type ConSesion = { context: { sesion: Parameters<typeof puede>[0] } }

/** Ruta privada que además exige un permiso; sin él, regresa al inicio. */
const conPermiso =
  (permiso: Permiso) =>
  ({ context }: ConSesion) => {
    if (!puede(context.sesion, permiso)) throw redirect({ to: '/' })
  }

/** Igual, con cualquiera de los permisos. */
const conAlguno =
  (...permisos: Permiso[]) =>
  ({ context }: ConSesion) => {
    if (!puedeAlguno(context.sesion, ...permisos)) throw redirect({ to: '/' })
  }

const usuarios = createRoute({
  getParentRoute: () => privado,
  path: '/usuarios',
  beforeLoad: conPermiso(PERMISOS.usuariosAdministrar),
  component: PantallaUsuarios,
})

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

// ------------------------------------------------------------------------------ Inventario (H4)

const consultaInventario = conAlguno(
  PERMISOS.existenciasVer,
  PERMISOS.movimientoCapturar,
  PERMISOS.valeAutorizar,
  PERMISOS.documentoAutorizar,
)

/** Número positivo de un parámetro de búsqueda, o nada. */
const numeroDeBusqueda = (valor: unknown) => {
  const n = Number(valor)
  return Number.isInteger(n) && n > 0 ? n : undefined
}

const existencias = createRoute({
  getParentRoute: () => privado,
  path: '/existencias',
  beforeLoad: consultaInventario,
  component: PantallaExistencias,
})

function RutaKardex() {
  const { materialId } = kardex.useParams()
  const { almacen } = kardex.useSearch()
  return <PantallaKardex key={materialId} materialId={Number(materialId)} almacen={almacen} />
}

const kardex = createRoute({
  getParentRoute: () => privado,
  path: '/kardex/$materialId',
  validateSearch: (busqueda: Record<string, unknown>): { almacen?: number } => ({
    almacen: numeroDeBusqueda(busqueda.almacen),
  }),
  beforeLoad: consultaInventario,
  component: RutaKardex,
})

const movimientos = createRoute({
  getParentRoute: () => privado,
  path: '/movimientos',
  beforeLoad: consultaInventario,
  component: PantallaMovimientos,
})

function RutaNuevoMovimiento() {
  const { tipo } = nuevoMovimiento.useParams()
  const { origen } = nuevoMovimiento.useSearch()
  return <PantallaCaptura key={`${tipo}-${origen ?? ''}`} tipo={tipo} origen={origen} />
}

const nuevoMovimiento = createRoute({
  getParentRoute: () => privado,
  path: '/movimientos/nuevo/$tipo',
  validateSearch: (busqueda: Record<string, unknown>): { origen?: number } => ({
    origen: numeroDeBusqueda(busqueda.origen),
  }),
  beforeLoad: conPermiso(PERMISOS.movimientoCapturar),
  component: RutaNuevoMovimiento,
})

function RutaMovimiento() {
  const { id } = movimiento.useParams()
  return <PantallaMovimiento key={id} id={Number(id)} />
}

const movimiento = createRoute({
  getParentRoute: () => privado,
  path: '/movimientos/$id',
  beforeLoad: consultaInventario,
  component: RutaMovimiento,
})

function RutaEditarMovimiento() {
  const { id } = editarMovimiento.useParams()
  const datos = editarMovimiento.useLoaderData()
  return <PantallaCaptura key={id} tipo={datos.tipo} movimientoId={Number(id)} />
}

const editarMovimiento = createRoute({
  getParentRoute: () => privado,
  path: '/movimientos/$id/editar',
  beforeLoad: conPermiso(PERMISOS.movimientoCapturar),
  loader: async ({ context, params }) => {
    const { consultasInventario } = await import('@/features/inventario/consultas')
    return context.queryClient.ensureQueryData(consultasInventario.movimiento(Number(params.id)))
  },
  component: RutaEditarMovimiento,
})

const autorizaciones = createRoute({
  getParentRoute: () => privado,
  path: '/autorizaciones',
  beforeLoad: conAlguno(PERMISOS.valeAutorizar, PERMISOS.documentoAutorizar),
  component: PantallaAutorizaciones,
})

const prestamos = createRoute({
  getParentRoute: () => privado,
  path: '/prestamos',
  beforeLoad: consultaInventario,
  component: PantallaPrestamos,
})

const conteos = createRoute({
  getParentRoute: () => privado,
  path: '/conteos',
  beforeLoad: consultaInventario,
  component: PantallaConteos,
})

function RutaConteo() {
  const { id } = conteo.useParams()
  return <PantallaConteo key={id} id={Number(id)} />
}

const conteo = createRoute({
  getParentRoute: () => privado,
  path: '/conteos/$id',
  beforeLoad: consultaInventario,
  component: RutaConteo,
})

const cierre = createRoute({
  getParentRoute: () => privado,
  path: '/cierre',
  beforeLoad: conPermiso(PERMISOS.administrar),
  component: PantallaCierre,
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
    existencias,
    kardex,
    movimientos,
    nuevoMovimiento,
    movimiento,
    editarMovimiento,
    autorizaciones,
    prestamos,
    conteos,
    conteo,
    cierre,
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

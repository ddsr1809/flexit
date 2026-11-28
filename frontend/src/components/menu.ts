import {
  ArrowLeftRight,
  ChartColumn,
  Boxes,
  CalendarCheck,
  ClipboardCheck,
  ClipboardList,
  Contact,
  DoorOpen,
  FileSpreadsheet,
  FileText,
  Gauge,
  House,
  Layers,
  LifeBuoy,
  Library,
  ListChecks,
  Package,
  ShoppingCart,
  Truck,
  Users,
  Wrench,
} from 'lucide-react'
import type { ComponentType } from 'react'
import { PERMISOS, puede, puedeAlguno, type Permiso, type Sesion } from '@/sesion/sesion'

interface Entrada {
  ruta: string
  texto: string
  icono: ComponentType<{ className?: string }>
  permiso?: Permiso
  /** Con cualquiera de estos permisos (si no hay `permiso`). */
  alguno?: Permiso[]
  /** Pantallas de hitos siguientes: se muestran deshabilitadas para que el menú no cambie de forma. */
  hito?: string
}

/** Quien ve requisiciones (mx.constructora.abastecimiento.aplicacion.Acceso). */
const VER_REQUISICIONES = [
  PERMISOS.requisicionCrear,
  PERMISOS.documentoAutorizar,
  PERMISOS.valeAutorizar,
  PERMISOS.movimientoCapturar,
  PERMISOS.pedidoCrear,
]

const VER_PEDIDOS = [
  PERMISOS.pedidoCrear,
  PERMISOS.movimientoCapturar,
  PERMISOS.documentoAutorizar,
  PERMISOS.costoVerTodo,
]

/** Quien ve algún reporte: costos, inventario valorizado o requisición contra surtido. */
const VER_REPORTES = [
  PERMISOS.costoVerPropio,
  PERMISOS.costoVerTodo,
  PERMISOS.precioVer,
  PERMISOS.requisicionCrear,
  PERMISOS.documentoAutorizar,
  PERMISOS.pedidoCrear,
]

/** Quien ve el reporte de estabilización (ServicioEstabilizacion.VER). */
const VER_ESTABILIZACION = [PERMISOS.costoVerTodo, PERMISOS.administrar, PERMISOS.incidenteAtender]

const CONSULTA_INVENTARIO = [
  PERMISOS.existenciasVer,
  PERMISOS.movimientoCapturar,
  PERMISOS.valeAutorizar,
  PERMISOS.documentoAutorizar,
]

export const MENU: Entrada[] = [
  { ruta: '/', texto: 'Inicio', icono: House },
  { ruta: '/contratos', texto: 'Contratos', icono: FileText },
  { ruta: '/materiales', texto: 'Materiales', icono: Package },
  { ruta: '/existencias', texto: 'Existencias', icono: Boxes, alguno: CONSULTA_INVENTARIO },
  {
    ruta: '/movimientos',
    texto: 'Documentos',
    icono: ArrowLeftRight,
    alguno: CONSULTA_INVENTARIO,
  },
  {
    ruta: '/autorizaciones',
    texto: 'Autorizaciones',
    icono: ClipboardCheck,
    alguno: [PERMISOS.valeAutorizar, PERMISOS.documentoAutorizar],
  },
  { ruta: '/prestamos', texto: 'Préstamos', icono: Wrench, alguno: CONSULTA_INVENTARIO },
  { ruta: '/conteos', texto: 'Conteo físico', icono: ListChecks, alguno: CONSULTA_INVENTARIO },
  { ruta: '/aperturas', texto: 'Apertura', icono: DoorOpen, alguno: CONSULTA_INVENTARIO },
  {
    ruta: '/requisiciones',
    texto: 'Requisiciones',
    icono: ClipboardList,
    alguno: VER_REQUISICIONES,
  },
  { ruta: '/pedidos', texto: 'Pedidos', icono: ShoppingCart, alguno: VER_PEDIDOS },
  { ruta: '/reportes', texto: 'Reportes', icono: ChartColumn, alguno: VER_REPORTES },
  {
    ruta: '/estabilizacion',
    texto: 'Estabilización',
    icono: Gauge,
    alguno: VER_ESTABILIZACION,
  },
  { ruta: '/paquetes', texto: 'Paquetes', icono: Layers, permiso: PERMISOS.estructuraVerTodo },
  { ruta: '/catalogos', texto: 'Catálogos', icono: Library, permiso: PERMISOS.estructuraVerTodo },
  { ruta: '/proveedores', texto: 'Proveedores', icono: Truck, permiso: PERMISOS.catalogoEditar },
  {
    ruta: '/migracion',
    texto: 'Migración',
    icono: FileSpreadsheet,
    permiso: PERMISOS.administrar,
  },
  { ruta: '/cierre', texto: 'Cierre de mes', icono: CalendarCheck, permiso: PERMISOS.administrar },
  {
    ruta: '/personas',
    texto: 'Personas',
    icono: Contact,
    permiso: PERMISOS.personasAdministrar,
  },
  { ruta: '/usuarios', texto: 'Usuarios', icono: Users, permiso: PERMISOS.usuariosAdministrar },
  { ruta: '/incidentes', texto: 'Incidentes', icono: LifeBuoy },
]

export { VER_ESTABILIZACION, VER_PEDIDOS, VER_REPORTES, VER_REQUISICIONES }

export const menuPara = (sesion: Sesion) =>
  MENU.filter((entrada) =>
    entrada.permiso
      ? puede(sesion, entrada.permiso)
      : entrada.alguno
        ? puedeAlguno(sesion, ...entrada.alguno)
        : true,
  )

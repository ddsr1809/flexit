import {
  ArrowLeftRight,
  Boxes,
  CalendarCheck,
  ClipboardCheck,
  ClipboardList,
  Contact,
  FileSpreadsheet,
  FileText,
  House,
  Layers,
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
  {
    ruta: '/requisiciones',
    texto: 'Requisiciones',
    icono: ClipboardList,
    permiso: PERMISOS.requisicionCrear,
    hito: 'H6',
  },
  {
    ruta: '/pedidos',
    texto: 'Pedidos',
    icono: ShoppingCart,
    permiso: PERMISOS.pedidoCrear,
    hito: 'H6',
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
]

export const menuPara = (sesion: Sesion) =>
  MENU.filter((entrada) =>
    entrada.permiso
      ? puede(sesion, entrada.permiso)
      : entrada.alguno
        ? puedeAlguno(sesion, ...entrada.alguno)
        : true,
  )

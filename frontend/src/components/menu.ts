import {
  Boxes,
  ClipboardList,
  FileText,
  House,
  Layers,
  Library,
  Package,
  ShoppingCart,
  Truck,
  Users,
} from 'lucide-react'
import type { ComponentType } from 'react'
import { PERMISOS, puede, type Permiso, type Sesion } from '@/sesion/sesion'

interface Entrada {
  ruta: string
  texto: string
  icono: ComponentType<{ className?: string }>
  permiso?: Permiso
  /** Pantallas de hitos siguientes: se muestran deshabilitadas para que el menú no cambie de forma. */
  hito?: string
}

export const MENU: Entrada[] = [
  { ruta: '/', texto: 'Inicio', icono: House },
  { ruta: '/contratos', texto: 'Contratos', icono: FileText },
  { ruta: '/materiales', texto: 'Materiales', icono: Package },
  {
    ruta: '/existencias',
    texto: 'Existencias',
    icono: Boxes,
    permiso: PERMISOS.existenciasVer,
    hito: 'H4',
  },
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
  { ruta: '/usuarios', texto: 'Usuarios', icono: Users, permiso: PERMISOS.usuariosAdministrar },
]

export const menuPara = (sesion: Sesion) =>
  MENU.filter((entrada) => !entrada.permiso || puede(sesion, entrada.permiso))

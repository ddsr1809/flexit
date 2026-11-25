import { Boxes, ClipboardList, House, ShoppingCart, Users } from 'lucide-react'
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
  { ruta: '/usuarios', texto: 'Usuarios', icono: Users, permiso: PERMISOS.usuariosAdministrar },
]

export const menuPara = (sesion: Sesion) =>
  MENU.filter((entrada) => !entrada.permiso || puede(sesion, entrada.permiso))

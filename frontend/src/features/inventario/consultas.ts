import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import { api } from '@/api/cliente'
import { exigir } from '@/api/errores'
import type { components } from '@/api/esquema'

export type Almacen = components['schemas']['Almacen']
export type Existencia = components['schemas']['Existencia']
export type RenglonKardex = components['schemas']['RenglonKardex']
export type Movimiento = components['schemas']['Movimiento']
export type LineaMovimiento = components['schemas']['LineaMovimiento']
export type ResumenMovimiento = components['schemas']['ResumenMovimiento']
export type DatosMovimiento = components['schemas']['DatosMovimiento']
export type LineaCaptura = components['schemas']['LineaCaptura']
export type ResultadoLote = components['schemas']['ResultadoLote']
export type Prestamo = components['schemas']['Prestamo']
export type Conteo = components['schemas']['Conteo']
export type DetalleConteo = components['schemas']['DetalleConteo']
export type LineaConteo = components['schemas']['LineaConteo']
export type Periodos = components['schemas']['Periodos']
export type PersonaNombre = components['schemas']['PersonaNombre']
export type Apertura = components['schemas']['Apertura']
export type DetalleApertura = components['schemas']['DetalleApertura']
export type LineaApertura = components['schemas']['LineaApertura']
export type PrestamoApertura = components['schemas']['PrestamoApertura']

/** Tipos de documento que se capturan a mano (los ajustes de existencia salen del conteo). */
export type TipoCaptura = DatosMovimiento['tipo']

interface InfoTipo {
  nombre: string
  /** Para el botón de "Nuevo …" y el título de la captura. */
  nuevo: string
  descripcion: string
  capturable: boolean
}

export const TIPOS: Record<string, InfoTipo> = {
  entrada: {
    nombre: 'Recepción',
    nuevo: 'Recepción de compra',
    descripcion: 'Material que llega del proveedor, con el precio de la factura sin IVA.',
    capturable: true,
  },
  salida: {
    nombre: 'Vale',
    nuevo: 'Vale de salida',
    descripcion:
      'Material que sale a obra. Lleva contrato, lote, paquete y razón; lo autoriza el residente o coordinación.',
    capturable: true,
  },
  devolucion: {
    nombre: 'Devolución de obra',
    nuevo: 'Devolución de obra',
    descripcion: 'Material que regresa de un lote al almacén, al costo con que salió.',
    capturable: true,
  },
  devolucion_proveedor: {
    nombre: 'Devolución a proveedor',
    nuevo: 'Devolución a proveedor',
    descripcion: 'Material que se regresa al proveedor, al costo con que entró.',
    capturable: true,
  },
  traspaso: {
    nombre: 'Traspaso',
    nuevo: 'Traspaso entre almacenes',
    descripcion: 'Material que pasa a otro almacén. Lo autoriza coordinación.',
    capturable: true,
  },
  reasignacion: {
    nombre: 'Reasignación',
    nuevo: 'Reasignación de lote',
    descripcion: 'Pasa el costo de un lote a otro sin mover material. Lo autoriza coordinación.',
    capturable: true,
  },
  merma: {
    nombre: 'Merma',
    nuevo: 'Merma',
    descripcion: 'Material roto, vencido o perdido, con foto. Lo autoriza coordinación.',
    capturable: true,
  },
  ajuste_costo: {
    nombre: 'Ajuste de costo',
    nuevo: 'Ajuste de costo (factura)',
    descripcion:
      'Corrige el costo de una recepción aplicada. Lo que queda en almacén ajusta el promedio; lo que ya salió se carga a sus lotes.',
    capturable: true,
  },
  ajuste_entrada: {
    nombre: 'Sobrante de conteo',
    nuevo: '',
    descripcion: '',
    capturable: false,
  },
  ajuste_salida: {
    nombre: 'Faltante de conteo',
    nuevo: '',
    descripcion: '',
    capturable: false,
  },
  apertura: {
    nombre: 'Saldo de apertura',
    nuevo: '',
    descripcion: 'Lo contado y lo prestado al arrancar el almacén, al costo firmado.',
    capturable: false,
  },
}

export const nombreTipo = (tipo: string) => TIPOS[tipo]?.nombre ?? tipo

export const ESTADOS: Record<string, string> = {
  borrador: 'Borrador',
  pendiente: 'Por autorizar',
  autorizado: 'Autorizado',
  aplicado: 'Aplicado',
  cancelado: 'Cancelado',
}

export const nombreEstado = (estado: string) => ESTADOS[estado] ?? estado

/** Hoy en México, como AAAA-MM-DD (lo que espera la API para una fecha de documento). */
export function hoy(): string {
  const partes = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Mexico_City',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date())
  return partes
}

/** Fecha de documento (AAAA-MM-DD) como dd/mm/aaaa, sin corrimiento de zona horaria. */
export function formatearDia(valor: string | null | undefined): string {
  if (!valor) return '—'
  const [anio, mes, dia] = valor.split('-')
  return `${dia}/${mes}/${anio}`
}

export interface FiltroMovimientos {
  tipo?: string
  estados?: string
  almacenId?: number
  folio?: string
  pagina: number
}

export const consultasInventario = {
  almacenes: queryOptions({
    queryKey: ['almacenes'],
    queryFn: async () => exigir(await api.GET('/api/v1/almacenes')),
  }),
  existencias: (almacenId: number, q: string | undefined, soloConExistencia: boolean) =>
    queryOptions({
      queryKey: ['existencias', almacenId, { q, soloConExistencia }],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/almacenes/{id}/existencias', {
            params: { path: { id: almacenId }, query: { q, soloConExistencia } },
          }),
        ),
      placeholderData: keepPreviousData,
    }),
  kardex: (materialId: number, almacenId?: number, desde?: string, hasta?: string) =>
    queryOptions({
      queryKey: ['kardex', materialId, { almacenId, desde, hasta }],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/materiales/{id}/kardex', {
            params: { path: { id: materialId }, query: { almacenId, desde, hasta } },
          }),
        ),
    }),
  movimientos: (filtro: FiltroMovimientos) =>
    queryOptions({
      queryKey: ['movimientos', 'lista', filtro],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/movimientos', {
            params: { query: { ...filtro, tamano: TAMANO_PAGINA } },
          }),
        ),
      placeholderData: keepPreviousData,
    }),
  porAutorizar: queryOptions({
    queryKey: ['movimientos', 'por-autorizar'],
    queryFn: async () => exigir(await api.GET('/api/v1/movimientos/por-autorizar')),
  }),
  movimiento: (id: number) =>
    queryOptions({
      queryKey: ['movimientos', id],
      queryFn: async () =>
        exigir(await api.GET('/api/v1/movimientos/{id}', { params: { path: { id } } })),
    }),
  restantes: (id: number) =>
    queryOptions({
      queryKey: ['movimientos', id, 'restantes'],
      queryFn: async () =>
        exigir(await api.GET('/api/v1/movimientos/{id}/restantes', { params: { path: { id } } })),
    }),
  prestamos: (almacenId?: number, estado?: string, vencidos = false) =>
    queryOptions({
      queryKey: ['prestamos', { almacenId, estado, vencidos }],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/prestamos', {
            params: { query: { almacenId, estado, vencidos } },
          }),
        ),
    }),
  conteos: (almacenId?: number) =>
    queryOptions({
      queryKey: ['conteos', { almacenId }],
      queryFn: async () =>
        exigir(await api.GET('/api/v1/conteos', { params: { query: { almacenId } } })),
    }),
  conteo: (id: number) =>
    queryOptions({
      queryKey: ['conteos', id],
      queryFn: async () =>
        exigir(await api.GET('/api/v1/conteos/{id}', { params: { path: { id } } })),
    }),
  aperturas: queryOptions({
    queryKey: ['aperturas'],
    queryFn: async () => exigir(await api.GET('/api/v1/aperturas')),
  }),
  apertura: (id: number) =>
    queryOptions({
      queryKey: ['aperturas', id],
      queryFn: async () =>
        exigir(await api.GET('/api/v1/aperturas/{id}', { params: { path: { id } } })),
    }),
  periodos: queryOptions({
    queryKey: ['periodos'],
    queryFn: async () => exigir(await api.GET('/api/v1/periodos')),
  }),
}

export const TAMANO_PAGINA = 50

/** Clave nueva por captura: si la red falla y se reintenta, el servidor no duplica el documento. */
export const claveIdempotencia = () => crypto.randomUUID()

/** Sube la foto de una merma; devuelve el id para el documento. */
export async function subirFoto(archivo: File) {
  const datos = new FormData()
  datos.append('archivo', archivo, archivo.name)
  return exigir(
    await api.POST('/api/v1/archivos', {
      body: datos as unknown as { archivo: string },
      bodySerializer: (cuerpo) => cuerpo as unknown as FormData,
    }),
  )
}

export const buscarMateriales = async (q: string) =>
  exigir(await api.GET('/api/v1/materiales/buscar', { params: { query: { q } } }))

export const buscarPersonas = async (q: string) =>
  exigir(await api.GET('/api/v1/personas/nombres', { params: { query: { q } } }))

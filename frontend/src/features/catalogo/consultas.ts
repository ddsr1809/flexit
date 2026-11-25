import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import { api } from '@/api/cliente'
import { exigir } from '@/api/errores'
import type { components } from '@/api/esquema'

export type UnidadMedida = components['schemas']['UnidadMedida']
export type Conversion = components['schemas']['Conversion']
export type Categoria = components['schemas']['Categoria']
export type Material = components['schemas']['Material']
export type Proveedor = components['schemas']['Proveedor']

export interface FiltroMateriales {
  q?: string
  categoriaId?: number
  soloActivos: boolean
  pagina: number
}

export const TAMANO_PAGINA = 50

export const consultasCatalogo = {
  unidades: queryOptions({
    queryKey: ['unidades-medida'],
    queryFn: async () => exigir(await api.GET('/api/v1/unidades-medida')),
  }),
  categorias: queryOptions({
    queryKey: ['categorias'],
    queryFn: async () => exigir(await api.GET('/api/v1/categorias')),
  }),
  clasificaciones: queryOptions({
    queryKey: ['clasificaciones-costo'],
    queryFn: async () => exigir(await api.GET('/api/v1/clasificaciones-costo')),
    staleTime: Infinity,
  }),
  conversiones: (materialId?: number) =>
    queryOptions({
      queryKey: ['conversiones', { materialId }],
      queryFn: async () =>
        exigir(await api.GET('/api/v1/conversiones', { params: { query: { materialId } } })),
    }),
  materiales: (filtro: FiltroMateriales) =>
    queryOptions({
      queryKey: ['materiales', filtro],
      queryFn: async () =>
        exigir(
          await api.GET('/api/v1/materiales', {
            params: { query: { ...filtro, tamano: TAMANO_PAGINA } },
          }),
        ),
      placeholderData: keepPreviousData,
    }),
  proveedores: (q?: string) =>
    queryOptions({
      queryKey: ['proveedores', { q }],
      queryFn: async () =>
        exigir(await api.GET('/api/v1/proveedores', { params: { query: { q } } })),
      placeholderData: keepPreviousData,
    }),
}

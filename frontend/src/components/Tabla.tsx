import type { ReactNode } from 'react'
import { cn } from '@/lib/clases'

export interface Columna<F> {
  titulo: string
  celda: (fila: F) => ReactNode
  clase?: string
}

/** Tabla simple con encabezado fijo en estilo y mensaje cuando no hay filas. */
export function Tabla<F>({
  filas,
  columnas,
  clave,
  vacio = 'Todavía no hay registros.',
  acciones,
  seleccionada,
}: {
  filas: F[] | undefined
  columnas: Columna<F>[]
  clave: (fila: F) => string | number
  vacio?: string
  acciones?: (fila: F) => ReactNode
  seleccionada?: string | number
}) {
  return (
    <div className="relative overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-borde bg-fondo text-tenue">
          <tr>
            {columnas.map((columna) => (
              <th
                key={columna.titulo}
                scope="col"
                className={cn('px-4 py-3 font-medium', columna.clase)}
              >
                {columna.titulo}
              </th>
            ))}
            {acciones && (
              <th scope="col" className="px-4 py-3">
                <span className="sr-only">Acciones</span>
              </th>
            )}
          </tr>
        </thead>
        <tbody>
          {filas?.map((fila) => (
            <tr
              key={clave(fila)}
              aria-selected={seleccionada === clave(fila) ? true : undefined}
              className="border-b border-borde last:border-0 aria-selected:bg-marca/5"
            >
              {columnas.map((columna) => (
                <td key={columna.titulo} className={cn('px-4 py-2.5', columna.clase)}>
                  {columna.celda(fila)}
                </td>
              ))}
              {acciones && (
                <td className="px-4 py-1.5 text-right whitespace-nowrap">{acciones(fila)}</td>
              )}
            </tr>
          ))}
          {filas && filas.length === 0 && (
            <tr>
              <td
                colSpan={columnas.length + (acciones ? 1 : 0)}
                className="px-4 py-6 text-center text-tenue"
              >
                {vacio}
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}

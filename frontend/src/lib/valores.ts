/** Conversión de valores de formulario (siempre texto) a lo que espera la API. */
export type Valores = Record<string, string | boolean>

/** Texto sin espacios en los extremos, o `undefined` si queda vacío. */
export function texto(valor: string | boolean | undefined): string | undefined {
  if (typeof valor !== 'string') return undefined
  const limpio = valor.trim()
  return limpio === '' ? undefined : limpio
}

/** Número (acepta coma decimal y separador de miles con coma), o `undefined` si está vacío. */
export function numero(valor: string | boolean | undefined): number | undefined {
  const limpio = texto(valor)
  if (limpio === undefined) return undefined
  const normalizado = limpio
    .replace(/\s/g, '')
    .replace(/,(?=\d{3}(\D|$))/g, '')
    .replace(',', '.')
  const resultado = Number(normalizado)
  return Number.isFinite(resultado) ? resultado : Number.NaN
}

/** Id de una selección, o `undefined` si no se eligió. */
export function id(valor: string | boolean | undefined): number | undefined {
  const limpio = texto(valor)
  return limpio === undefined ? undefined : Number(limpio)
}

export const casilla = (valor: string | boolean | undefined) => valor === true

/** De un dato de la API a texto para un campo. */
export const aTexto = (valor: string | number | null | undefined) =>
  valor === null || valor === undefined ? '' : String(valor)

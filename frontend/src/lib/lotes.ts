/**
 * Vista previa de los lotes que se van a crear, con las mismas reglas que
 * mx.constructora.estructura.ClaveUnidad: "29-31, 35, 40A" → 29, 30, 31, 35, 40A.
 * El backend vuelve a validar; esto solo sirve para que la persona vea qué va a guardar.
 */
const NUMERO = /^0*(\d{1,4})([A-Z]?)$/
const RANGO = /^(\d{1,4})-(\d{1,4})$/
export const MAXIMO_LOTES = 500

export function numeroLote(texto: string): string | undefined {
  const coincidencia = NUMERO.exec(texto.trim().toUpperCase())
  if (!coincidencia) return undefined
  return coincidencia[1]!.replace(/^0+(?=\d)/, '') + coincidencia[2]!
}

export type ResultadoLotes = { lotes: string[] } | { error: string }

export function expandirLotes(expresion: string): ResultadoLotes {
  const partes = expresion
    .trim()
    .replace(/\s*-\s*/g, '-')
    .split(/[,;\s]+/)
    .filter(Boolean)
  if (partes.length === 0) return { error: 'Escribe al menos un lote' }
  const lotes = new Set<string>()
  for (const parte of partes) {
    const rango = RANGO.exec(parte)
    if (rango) {
      const desde = Number(rango[1])
      const hasta = Number(rango[2])
      if (hasta < desde) return { error: `El rango ${parte} va al revés` }
      if (hasta - desde + 1 > MAXIMO_LOTES) return { error: `Son más de ${MAXIMO_LOTES} lotes` }
      for (let n = desde; n <= hasta; n++) lotes.add(String(n))
    } else {
      const numero = numeroLote(parte)
      if (!numero) return { error: `"${parte}" no es un número de lote` }
      lotes.add(numero)
    }
    if (lotes.size > MAXIMO_LOTES) return { error: `Son más de ${MAXIMO_LOTES} lotes` }
  }
  return { lotes: [...lotes] }
}

/** Claves como las guarda el sistema: M20-L29. */
export function clavesDeLotes(manzana: string, expresion: string): ResultadoLotes {
  const mz = numeroLote(manzana)
  if (!mz) return { error: 'Escribe la manzana (número, con letra opcional)' }
  const resultado = expandirLotes(expresion)
  if ('error' in resultado) return resultado
  return { lotes: resultado.lotes.map((lote) => `M${mz}-L${lote}`) }
}

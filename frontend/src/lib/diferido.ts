import { useEffect, useState } from 'react'

/** El valor, pero solo después de `ms` sin cambios: para no consultar en cada tecla. */
export function useDiferido<T>(valor: T, ms: number): T {
  const [diferido, setDiferido] = useState(valor)
  useEffect(() => {
    const temporizador = setTimeout(() => setDiferido(valor), ms)
    return () => clearTimeout(temporizador)
  }, [valor, ms])
  return diferido
}

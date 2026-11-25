import { useQuery } from '@tanstack/react-query'
import { api } from '@/api/cliente'
import { exigir } from '@/api/errores'

/** Versión desplegada: así se comprueba que un cambio fusionado ya llegó al entorno. */
export function PieVersion() {
  const { data } = useQuery({
    queryKey: ['version'],
    queryFn: async () => exigir(await api.GET('/api/v1/version')),
    staleTime: Infinity,
  })
  if (!data) return null
  return (
    <p className="text-xs text-tenue">
      Versión {data.version.slice(0, 7)} · {data.entorno}
    </p>
  )
}

import { Insignia } from '@/components/ui/insignia'
import { cn } from '@/lib/clases'
import { formatearNumero } from '@/lib/formato'
import { ESTADOS_PEDIDO, ESTADOS_REQUISICION } from './consultas'
import { TEXTO_SEMAFORO, TONO_SEMAFORO } from './calculos'

const TERMINADOS = new Set(['cerrada', 'cancelada', 'cerrado', 'cancelado'])
const LISTOS = new Set(['autorizada', 'recibido'])

export const EstadoRequisicion = ({ estado }: { estado: string }) => (
  <Insignia tono={LISTOS.has(estado) ? 'exito' : TERMINADOS.has(estado) ? 'apagado' : 'neutro'}>
    {ESTADOS_REQUISICION[estado] ?? estado}
  </Insignia>
)

export const EstadoPedido = ({ estado }: { estado: string }) => (
  <Insignia tono={LISTOS.has(estado) ? 'exito' : TERMINADOS.has(estado) ? 'apagado' : 'neutro'}>
    {ESTADOS_PEDIDO[estado] ?? estado}
  </Insignia>
)

/** Semáforo de presupuesto: verde, aviso al 80% y rebasado al 100%. */
export function Semaforo({
  semaforo,
  porcentaje,
}: {
  semaforo: string
  porcentaje?: number | null
}) {
  return (
    <span
      title={TEXTO_SEMAFORO[semaforo]}
      className={cn(
        'inline-flex rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap',
        TONO_SEMAFORO[semaforo] ?? TONO_SEMAFORO.sin,
      )}
    >
      {porcentaje == null ? TEXTO_SEMAFORO[semaforo] : `${formatearNumero(porcentaje)}%`}
    </span>
  )
}

/** Barra de avance (0 a 100). */
export function Avance({ valor }: { valor: number }) {
  const ancho = Math.max(0, Math.min(100, valor))
  return (
    <span className="inline-flex items-center gap-2">
      <span className="h-2 w-16 overflow-hidden rounded-full bg-fondo" aria-hidden>
        <span className="block h-full bg-marca" style={{ width: `${ancho}%` }} />
      </span>
      <span className="text-xs text-tenue">{formatearNumero(valor)}%</span>
    </span>
  )
}

/** Dato con título, como en el detalle de un documento. */
export function Dato({ titulo, valor }: { titulo: string; valor?: string | null }) {
  if (!valor) return null
  return (
    <div>
      <dt className="text-tenue">{titulo}</dt>
      <dd>{valor}</dd>
    </div>
  )
}

/** Enlace de descarga para el Excel de un reporte (la sesión viaja en la cookie). */
export function DescargaExcel({
  ruta,
  texto = 'Descargar Excel',
}: {
  ruta: string
  texto?: string
}) {
  return (
    <a
      href={ruta}
      download
      className="inline-flex min-h-11 items-center gap-2 rounded-md border border-borde bg-white px-4 text-sm font-medium hover:bg-fondo"
    >
      {texto}
    </a>
  )
}

import qrcode from 'qrcode-generator'
import { useMemo } from 'react'

/**
 * Código QR en SVG, dibujado aquí (sin imágenes externas ni estilos en línea, por la CSP). Con el
 * margen de 4 módulos que piden los lectores de los teléfonos.
 */
export function CodigoQr({ texto, etiqueta }: { texto: string; etiqueta: string }) {
  const { tamano, camino } = useMemo(() => {
    const qr = qrcode(0, 'M')
    qr.addData(texto)
    qr.make()
    const modulos = qr.getModuleCount()
    const partes: string[] = []
    for (let fila = 0; fila < modulos; fila++) {
      for (let columna = 0; columna < modulos; columna++) {
        if (qr.isDark(fila, columna)) partes.push(`M${columna + 4},${fila + 4}h1v1h-1z`)
      }
    }
    return { tamano: modulos + 8, camino: partes.join('') }
  }, [texto])

  return (
    <svg
      role="img"
      aria-label={etiqueta}
      viewBox={`0 0 ${tamano} ${tamano}`}
      shapeRendering="crispEdges"
      className="size-52 rounded-md bg-white"
    >
      <rect width={tamano} height={tamano} fill="#ffffff" />
      <path d={camino} fill="#000000" />
    </svg>
  )
}

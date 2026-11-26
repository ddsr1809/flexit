import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Tarjeta } from '@/components/ui/tarjeta'
import { formatearNumero } from '@/lib/formato'
import { consultasMigracion } from './consultas'

/** Un número que viene como texto o número de la API, para mostrarlo. */
function numero(valor: number | string): string {
  return formatearNumero(Number(valor))
}

function Diferencia({ valor }: { valor: number | string }) {
  const cero = Number(valor) === 0
  return (
    <span className={cero ? 'text-exito' : 'font-medium text-peligro'}>
      {cero ? '0' : numero(valor)}
    </span>
  )
}

/**
 * Conciliación de totales de una carga del histórico (H5): lo que dice el Excel contra lo que se
 * carga, por concepto (cantidad por material y mes, pagado por lote…). Cada diferencia tiene que
 * ser 0; lo que no se carga está en cuarentena o descartado.
 */
export function Totales({ cargaId }: { cargaId: number }) {
  const [todas, setTodas] = useState(false)
  const conciliacion = useQuery(consultasMigracion.conciliacion(cargaId, todas))

  if (conciliacion.isPending) return <p className="text-tenue">Conciliando totales…</p>
  if (conciliacion.isError) return <Aviso>{mensajeDe(conciliacion.error)}</Aviso>
  const datos = conciliacion.data
  const cuadra = datos.conceptos.every((c) => c.conDiferencia === 0)

  return (
    <section aria-labelledby="totales" className="flex flex-col gap-4">
      <Tarjeta className="min-w-0 p-0">
        <div className="flex flex-wrap items-center justify-between gap-2 px-4 pt-4">
          <h2 id="totales" className="text-lg font-medium">
            Totales contra el Excel
          </h2>
          {cuadra ? (
            <span className="text-sm text-exito">Todo cuadra con el Excel</span>
          ) : (
            <span className="text-sm font-medium text-peligro">Hay diferencias</span>
          )}
        </div>
        <Tabla
          filas={datos.conceptos}
          clave={(c) => c.concepto}
          columnas={[
            { titulo: 'Concepto', celda: (c) => c.concepto },
            { titulo: 'Excel', celda: (c) => numero(c.excel), clase: 'text-right' },
            { titulo: 'Se carga', celda: (c) => numero(c.cargado), clase: 'text-right' },
            { titulo: 'Cuarentena', celda: (c) => numero(c.pendiente), clase: 'text-right' },
            { titulo: 'Descartado', celda: (c) => numero(c.descartado), clase: 'text-right' },
            {
              titulo: 'Diferencia',
              celda: (c) => <Diferencia valor={c.diferencia} />,
              clase: 'text-right',
            },
          ]}
        />
      </Tarjeta>
      <Tarjeta className="min-w-0 p-0">
        <div className="flex flex-wrap items-center justify-between gap-2 px-4 pt-4">
          <h3 className="font-medium">
            {todas ? 'Todas las líneas' : 'Líneas con cuarentena, descartes o diferencias'}
          </h3>
          <Boton variante="secundario" onClick={() => setTodas(!todas)}>
            {todas ? 'Ver solo las que tienen algo' : 'Ver todas'}
          </Boton>
        </div>
        <Tabla
          filas={datos.lineas}
          clave={(l) => `${l.concepto}|${l.llave}`}
          vacio="Cada línea cuadra y no hay nada en cuarentena ni descartado."
          columnas={[
            { titulo: 'Concepto', celda: (l) => l.concepto },
            { titulo: 'Qué', celda: (l) => l.llave },
            { titulo: 'Excel', celda: (l) => numero(l.excel), clase: 'text-right' },
            { titulo: 'Se carga', celda: (l) => numero(l.cargado), clase: 'text-right' },
            { titulo: 'Cuarentena', celda: (l) => numero(l.pendiente), clase: 'text-right' },
            { titulo: 'Descartado', celda: (l) => numero(l.descartado), clase: 'text-right' },
            {
              titulo: 'Diferencia',
              celda: (l) => <Diferencia valor={l.diferencia} />,
              clase: 'text-right',
            },
          ]}
        />
        {datos.recortada && (
          <p className="px-4 pb-4 text-sm text-tenue">Se muestran las primeras 500 líneas.</p>
        )}
      </Tarjeta>
    </section>
  )
}

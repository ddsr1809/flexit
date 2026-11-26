import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Seleccion } from '@/components/ui/seleccion'
import { useDiferido } from '@/lib/diferido'
import {
  candidatos,
  consultasMigracion,
  entidadDe,
  ENTIDADES,
  OPCIONES,
  sugerido,
  type DecisionCarga,
  type IncidenciaCarga,
  type Opcion,
} from './consultas'

/** Busca el registro al que se liga un nombre del Excel (frente, contrato, persona…). */
export function BuscarDestino({
  entidad,
  frenteId,
  iniciales = [],
  alElegir,
  textoBoton = 'Elegir',
  ocupado,
}: {
  entidad: string
  frenteId?: number
  iniciales?: { id: number; texto: string }[]
  alElegir: (id: number) => void
  textoBoton?: string
  ocupado?: boolean
}) {
  const [busqueda, setBusqueda] = useState('')
  const [elegido, setElegido] = useState('')
  const q = useDiferido(busqueda.trim(), 250)
  const destinos = useQuery(consultasMigracion.destinos(entidad, q, frenteId))
  const opciones = [
    ...iniciales.map((c) => ({ valor: c.id, texto: `${c.texto} (sugerido)` })),
    ...(destinos.data ?? [])
      .filter((d) => !iniciales.some((c) => c.id === d.id))
      .map((d) => ({ valor: d.id, texto: d.texto })),
  ]
  return (
    <div className="grid gap-2 md:grid-cols-[1fr_2fr_auto] md:items-end">
      <Campo
        etiqueta={`Buscar ${ENTIDADES[entidad]?.toLowerCase() ?? entidad}`}
        type="search"
        value={busqueda}
        onChange={(e) => setBusqueda(e.target.value)}
      />
      <Seleccion
        etiqueta={ENTIDADES[entidad] ?? entidad}
        vacio={destinos.isPending ? 'Buscando…' : 'Elige…'}
        opciones={opciones}
        value={elegido}
        onChange={(e) => setElegido(e.target.value)}
      />
      <Boton
        variante="secundario"
        disabled={!elegido || ocupado}
        onClick={() => alElegir(Number(elegido))}
      >
        {textoBoton}
      </Boton>
    </div>
  )
}

/** Las opciones de una incidencia pendiente: aceptar, elegir otro, dar de alta, capturar o descartar. */
export function Decidir({
  numero,
  incidencia,
  decidir,
  ocupado,
}: {
  numero: number
  incidencia: IncidenciaCarga
  decidir: (decision: DecisionCarga) => void
  ocupado: boolean
}) {
  const [abierta, setAbierta] = useState<Opcion>()
  const [valor, setValor] = useState('')
  const opciones = incidencia.opciones as Opcion[]
  const destino = sugerido(incidencia, 'destino')
  const entidad = entidadDe(incidencia)
  const base = { numero, regla: incidencia.regla }
  const frenteId = Number(sugerido(incidencia, 'frenteId'))

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap gap-2">
        {opciones.includes('ACEPTAR') && (destino || sugerido(incidencia, 'numero')) && (
          <Boton
            disabled={ocupado}
            onClick={() => decidir({ ...base, opcion: 'ACEPTAR' })}
            title={destino ? `Ligar con ${destino}` : undefined}
          >
            Aceptar{destino ? `: ${destino}` : ''}
          </Boton>
        )}
        {opciones.includes('CREAR') && (
          <Boton
            variante="secundario"
            disabled={ocupado}
            onClick={() => decidir({ ...base, opcion: 'CREAR' })}
          >
            {OPCIONES.CREAR}
          </Boton>
        )}
        {(['ELEGIR', 'VALOR', 'DESCARTAR'] as Opcion[])
          .filter((o) => opciones.includes(o) && (o !== 'ELEGIR' || entidad))
          .map((o) => (
            <Boton
              key={o}
              variante={abierta === o ? 'principal' : 'secundario'}
              aria-expanded={abierta === o}
              onClick={() => {
                setAbierta(abierta === o ? undefined : o)
                setValor('')
              }}
            >
              {OPCIONES[o]}
            </Boton>
          ))}
      </div>
      {abierta === 'ELEGIR' && entidad && (
        <BuscarDestino
          entidad={entidad}
          frenteId={Number.isFinite(frenteId) ? frenteId : undefined}
          iniciales={candidatos(incidencia)}
          ocupado={ocupado}
          alElegir={(destinoId) => decidir({ ...base, opcion: 'ELEGIR', destinoId })}
        />
      )}
      {(abierta === 'VALOR' || abierta === 'DESCARTAR') && (
        <form
          className="grid gap-2 md:grid-cols-[2fr_auto] md:items-end"
          onSubmit={(e) => {
            e.preventDefault()
            decidir(
              abierta === 'VALOR'
                ? { ...base, opcion: 'VALOR', valor }
                : { ...base, opcion: 'DESCARTAR', motivo: valor },
            )
          }}
        >
          <Campo
            etiqueta={abierta === 'VALOR' ? etiquetaValor(incidencia.regla) : 'Por qué se descarta'}
            value={valor}
            onChange={(e) => setValor(e.target.value)}
          />
          <Boton type="submit" variante="secundario" disabled={!valor.trim() || ocupado}>
            Guardar
          </Boton>
        </form>
      )}
    </div>
  )
}

function etiquetaValor(regla: string): string {
  switch (regla) {
    case 'SIN_PRECIO':
      return 'Precio sin IVA'
    case 'MATERIAL_SOLO_EN_MOVIMIENTOS':
    case 'SIN_UNIDAD':
      return 'Unidad (PZA, KG, L, M, CJA…)'
    case 'LOTE_ILEGIBLE':
      return 'Lote correcto (M7L16)'
    case 'CONCEPTO_SIN_NOMBRE':
      return 'Nombre del concepto'
    default:
      return 'Valor'
  }
}

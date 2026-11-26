import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Camera, Plus, Trash2 } from 'lucide-react'
import { useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { api } from '@/api/cliente'
import { ErrorApi, exigir, mensajeDe } from '@/api/errores'
import { Buscador } from '@/components/Buscador'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { consultasCatalogo, type Material } from '@/features/catalogo/consultas'
import { consultas, siCoincide } from '@/features/estructura/consultas'
import { formatearNumero } from '@/lib/formato'
import { numero } from '@/lib/valores'
import {
  buscarMateriales,
  buscarPersonas,
  claveIdempotencia,
  consultasInventario,
  formatearDia,
  hoy,
  subirFoto,
  TIPOS,
  type DatosMovimiento,
  type LineaCaptura,
  type Movimiento,
  type PersonaNombre,
} from './consultas'
import { factorConversion, lotesDeRenglon, vistaReparto } from './reparto'

/** Un renglón como se captura en pantalla (todo en texto hasta guardar). */
interface Renglon {
  clave: number
  materialId?: number
  material?: string
  unidadControl?: string
  unidadControlId?: number
  cantidad: string
  unidadId: string
  precio: string
  descuento: string
  manzana: string
  lotes: string
  paqueteId: string
  clasificacionId: string
  // Devolución, reasignación, devolución a proveedor y ajuste: la línea de origen.
  lineaOrigenId?: number
  etiqueta?: string
  maximo?: number
  elegido: boolean
  costoNuevo: string
  // Reasignación: el destino.
  contratoId: string
  loteId: string
}

let siguienteClave = 1
const renglonVacio = (parcial: Partial<Renglon> = {}): Renglon => ({
  clave: siguienteClave++,
  cantidad: '',
  unidadId: '',
  precio: '',
  descuento: '',
  manzana: '',
  lotes: '',
  paqueteId: '',
  clasificacionId: '',
  elegido: true,
  costoNuevo: '',
  contratoId: '',
  loteId: '',
  ...parcial,
})

const AUTORIZA = new Set(['salida', 'traspaso', 'reasignacion', 'merma', 'ajuste_costo'])
const DESDE_VALE = new Set(['devolucion', 'reasignacion'])
const DESDE_RECEPCION = new Set(['devolucion_proveedor', 'ajuste_costo'])

/**
 * Captura de un documento, pensada para el teclado: se busca el material, se escribe la cantidad y
 * Enter en el último campo agrega otro renglón. En el vale, la manzana y la lista de lotes
 * ("1-18, 20") reparten la cantidad exacta entre los lotes. Con {@code movimientoId} corrige un
 * borrador.
 */
export function PantallaCaptura({
  tipo,
  movimientoId,
  origen,
}: {
  tipo: string
  movimientoId?: number
  origen?: number
}) {
  const info = TIPOS[tipo]
  const existente = useQuery({
    ...consultasInventario.movimiento(movimientoId ?? 0),
    enabled: movimientoId !== undefined,
  })
  if (!info?.capturable) return <Aviso>Ese tipo de documento no se captura a mano.</Aviso>
  if (movimientoId !== undefined && !existente.data) {
    return existente.isError ? (
      <Aviso>{mensajeDe(existente.error)}</Aviso>
    ) : (
      <p className="text-tenue">Cargando…</p>
    )
  }
  return (
    <Formulario
      key={movimientoId ?? `nuevo-${tipo}-${origen ?? ''}`}
      tipo={tipo}
      existente={existente.data}
      origenInicial={origen}
    />
  )
}

function Formulario({
  tipo,
  existente,
  origenInicial,
}: {
  tipo: string
  existente?: Movimiento
  origenInicial?: number
}) {
  const info = TIPOS[tipo]!
  const cliente = useQueryClient()
  const navegar = useNavigate()
  const clave = useRef(claveIdempotencia())
  const desdeVale = DESDE_VALE.has(tipo)
  const desdeRecepcion = DESDE_RECEPCION.has(tipo)
  const conOrigen = desdeVale || desdeRecepcion

  const almacenes = useQuery(consultasInventario.almacenes)
  const unidades = useQuery(consultasCatalogo.unidades)
  const activos = (almacenes.data ?? []).filter((a) => a.activo)

  const [almacenElegido, setAlmacenId] = useState(existente ? String(existente.almacenId) : '')
  const [fecha, setFecha] = useState(existente?.fecha ?? hoy())
  const [destinoId, setDestinoId] = useState(aTexto(existente?.almacenDestinoId))
  const [proveedorId, setProveedorId] = useState(aTexto(existente?.proveedorId))
  const [folioFactura, setFolioFactura] = useState(existente?.folioFactura ?? '')
  const [flete, setFlete] = useState(aTexto(existente?.flete))
  const [solicitante, setSolicitante] = useState<PersonaNombre | undefined>(
    existente?.solicitanteId
      ? { id: existente.solicitanteId, nombre: existente.solicitante ?? '', activa: true }
      : undefined,
  )
  const [contratoId, setContratoId] = useState(aTexto(existente?.lineas[0]?.contratoId))
  const [paqueteId, setPaqueteId] = useState(aTexto(existente?.lineas[0]?.paqueteId))
  const [clasificacionId, setClasificacionId] = useState(
    aTexto(existente?.lineas[0]?.clasificacionId ?? (tipo === 'salida' ? 1 : undefined)),
  )
  const [motivo, setMotivo] = useState(existente?.motivo ?? '')
  const [observaciones, setObservaciones] = useState(existente?.observaciones ?? '')
  const [foto, setFoto] = useState<{ id: number; nombre: string } | undefined>(
    existente?.archivoId ? { id: existente.archivoId, nombre: 'Foto guardada' } : undefined,
  )
  const [origenId, setOrigenId] = useState<number | undefined>(
    origenInicial ?? existente?.movimientoOrigenId ?? undefined,
  )
  const [renglones, setRenglones] = useState<Renglon[]>(() =>
    existente ? renglonesDe(existente, tipo) : conOrigen ? [] : [renglonVacio()],
  )
  const [avisoValidacion, setAvisoValidacion] = useState<string>()
  const [sinAplicar, setSinAplicar] = useState<{ id: number; folio: string; mensaje: string }>()

  // Almacén por omisión: el primero activo.
  const almacenId = almacenElegido || String(activos[0]?.id ?? '')
  const almacen = activos.find((a) => String(a.id) === almacenId)

  // Catálogos según el tipo.
  const proveedores = useQuery({
    ...consultasCatalogo.proveedores(),
    enabled: tipo === 'entrada' || tipo === 'devolucion_proveedor',
  })
  const contratos = useQuery({
    ...consultas.contratos('activo'),
    enabled: tipo === 'salida' || tipo === 'reasignacion',
  })
  const paquetes = useQuery({
    ...consultas.paquetes,
    enabled: tipo === 'salida' || tipo === 'reasignacion',
  })
  const clasificaciones = useQuery({
    ...consultasCatalogo.clasificaciones,
    enabled: tipo === 'salida' || tipo === 'reasignacion',
  })
  const lotes = useQuery({
    ...consultas.unidades(Number(contratoId)),
    enabled: tipo === 'salida' && !!contratoId,
  })
  const contratosDelFrente = (contratos.data ?? []).filter(
    (c) => !almacen || c.frenteId == null || c.frenteId === almacen.frenteId,
  )

  // Documento de origen (devolución, reasignación, devolución a proveedor, ajuste de costo).
  const origen = useQuery({
    ...consultasInventario.movimiento(origenId ?? 0),
    enabled: conOrigen && origenId !== undefined,
  })
  const restantes = useQuery({
    ...consultasInventario.restantes(origenId ?? 0),
    enabled: desdeVale && origenId !== undefined,
  })
  // Al llegar el documento de origen, sus líneas se vuelven los renglones (una sola vez por
  // documento; se ajusta el estado durante el render, como recomienda React).
  const [cargadoDe, setCargadoDe] = useState<number>()
  if (!existente && origen.data && (!desdeVale || restantes.data) && cargadoDe !== origen.data.id) {
    const doc = origen.data
    setCargadoDe(doc.id)
    setAlmacenId(String(doc.almacenId))
    if (doc.proveedorId) setProveedorId(String(doc.proveedorId))
    if (doc.solicitanteId)
      setSolicitante({ id: doc.solicitanteId, nombre: doc.solicitante ?? '', activa: true })
    const queda = new Map((restantes.data ?? []).map((r) => [r.lineaId, r.cantidad]))
    setRenglones(
      doc.lineas
        .map((l) => {
          const maximo = desdeVale ? (queda.get(l.id) ?? 0) : l.cantidad
          return renglonVacio({
            materialId: l.materialId,
            material: l.material,
            unidadControl: l.unidadControl,
            lineaOrigenId: l.id,
            etiqueta: [l.unidadObra, l.paquete, l.clasificacion].filter(Boolean).join(' · '),
            maximo,
            cantidad: maximo > 0 ? String(maximo) : '',
            elegido: maximo > 0,
            contratoId: aTexto(l.contratoId),
            paqueteId: aTexto(l.paqueteId),
            clasificacionId: aTexto(l.clasificacionId),
          })
        })
        .filter((r) => (r.maximo ?? 0) > 0),
    )
  }

  const cambiar = (claveRenglon: number, cambio: Partial<Renglon>) =>
    setRenglones((actuales) =>
      actuales.map((r) => (r.clave === claveRenglon ? { ...r, ...cambio } : r)),
    )
  const agregar = () => setRenglones((actuales) => [...actuales, renglonVacio()])
  const quitar = (claveRenglon: number) =>
    setRenglones((actuales) => actuales.filter((r) => r.clave !== claveRenglon))
  /** Enter en el último campo de un renglón agrega otro. */
  const alFinal = (evento: KeyboardEvent<HTMLInputElement>, claveRenglon: number) => {
    if (evento.key !== 'Enter') return
    evento.preventDefault()
    if (renglones[renglones.length - 1]?.clave === claveRenglon) agregar()
  }

  const datos = (): DatosMovimiento => {
    const lineas: LineaCaptura[] = []
    for (const [indice, r] of renglones.entries()) {
      const donde = `Renglón ${indice + 1}`
      if (conOrigen) {
        if (!r.elegido) continue
        if (tipo === 'ajuste_costo') {
          lineas.push({ lineaOrigenId: r.lineaOrigenId, costoNuevo: numero(r.costoNuevo) })
          continue
        }
        const linea: LineaCaptura = { lineaOrigenId: r.lineaOrigenId, cantidad: numero(r.cantidad) }
        if (tipo === 'reasignacion') {
          linea.contratoId = Number(r.contratoId) || undefined
          linea.unidadObraIds = r.loteId ? [Number(r.loteId)] : []
          linea.paqueteId = Number(r.paqueteId) || undefined
          linea.clasificacionId = Number(r.clasificacionId) || undefined
        }
        lineas.push(linea)
        continue
      }
      if (!r.materialId && !r.cantidad) continue
      const linea: LineaCaptura = {
        materialId: r.materialId,
        cantidad: numero(r.cantidad),
        unidadId: r.unidadId ? Number(r.unidadId) : undefined,
      }
      if (tipo === 'entrada') {
        linea.precio = numero(r.precio)
        linea.descuento = numero(r.descuento)
      }
      if (tipo === 'salida') {
        const resultado = lotesDeRenglon(lotes.data ?? [], r.manzana, r.lotes)
        if ('error' in resultado) throw new Error(`${donde}: ${resultado.error}`)
        linea.contratoId = Number(contratoId) || undefined
        linea.unidadObraIds = resultado.ids
        linea.paqueteId = Number(r.paqueteId || paqueteId) || undefined
        linea.clasificacionId = Number(r.clasificacionId || clasificacionId) || undefined
      }
      lineas.push(linea)
    }
    if (lineas.length === 0) throw new Error('Agrega al menos un material')
    return {
      tipo: tipo as DatosMovimiento['tipo'],
      fecha,
      almacenId: Number(almacenId),
      almacenDestinoId: tipo === 'traspaso' ? Number(destinoId) || undefined : undefined,
      proveedorId:
        tipo === 'entrada' || tipo === 'devolucion_proveedor'
          ? Number(proveedorId) || undefined
          : undefined,
      folioFactura: folioFactura.trim() || undefined,
      flete: tipo === 'entrada' ? numero(flete) : undefined,
      solicitanteId: solicitante?.id,
      movimientoOrigenId: tipo === 'ajuste_costo' ? origenId : undefined,
      archivoId: tipo === 'merma' ? foto?.id : undefined,
      motivo: motivo.trim() || undefined,
      observaciones: observaciones.trim() || undefined,
      lineas,
    }
  }

  const guardar = useMutation({
    mutationFn: async (despues: 'borrador' | 'enviar' | 'aplicar') => {
      const cuerpo = { ...datos(), enviar: despues === 'enviar' }
      const guardado = existente
        ? exigir(
            await api.PUT('/api/v1/movimientos/{id}', {
              params: { path: { id: existente.id }, header: siCoincide(existente.version) },
              body: cuerpo,
            }),
          )
        : exigir(
            await api.POST('/api/v1/movimientos', {
              params: { header: { 'Idempotency-Key': clave.current } },
              body: cuerpo,
            }),
          )
      if (despues !== 'aplicar') return { guardado }
      try {
        return {
          guardado: exigir(
            await api.POST('/api/v1/movimientos/{id}/aplicar', {
              params: { path: { id: guardado.id } },
            }),
          ),
        }
      } catch (error) {
        return { guardado, errorAplicar: error }
      }
    },
    onSuccess: ({ guardado, errorAplicar }) => {
      void cliente.invalidateQueries({ queryKey: ['movimientos'] })
      void cliente.invalidateQueries({ queryKey: ['existencias'] })
      if (errorAplicar) {
        // Se guardó pero no se aplicó (por ejemplo, no alcanzó la existencia): se dice aquí.
        setSinAplicar({ id: guardado.id, folio: guardado.folio, mensaje: mensajeDe(errorAplicar) })
        return
      }
      void navegar({ to: '/movimientos/$id', params: { id: String(guardado.id) } })
    },
    onError: (error) => {
      if (!(error instanceof ErrorApi)) setAvisoValidacion(error.message)
    },
  })

  const enviar = (evento: FormEvent, despues: 'borrador' | 'enviar' | 'aplicar') => {
    evento.preventDefault()
    setAvisoValidacion(undefined)
    guardar.mutate(despues)
  }

  const fotoMutacion = useMutation({
    mutationFn: subirFoto,
    onSuccess: (archivo) => setFoto({ id: archivo.id, nombre: archivo.nombre }),
  })

  const opcionesUnidad = (unidades.data ?? [])
    .filter((u) => u.activa)
    .map((u) => ({ valor: u.id, texto: u.clave }))
  const opcionesPaquete = (paquetes.data ?? [])
    .filter((p) => p.activo)
    .map((p) => ({
      valor: p.id,
      texto: `${p.clave} ${p.nombre}${p.cliente ? ` (${p.cliente})` : ''}`,
    }))
  const opcionesRazon = (clasificaciones.data ?? []).map((c) => ({ valor: c.id, texto: c.nombre }))
  const fraccionable = useMemo(
    () => new Map((unidades.data ?? []).map((u) => [u.clave, u.fraccionable])),
    [unidades.data],
  )

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header>
        <Link to="/movimientos" className="text-sm text-marca hover:underline">
          ← Documentos
        </Link>
        <h1 className="text-2xl font-semibold">
          {existente ? `Corregir ${existente.folio}` : info.nuevo}
        </h1>
        <p className="text-tenue">{info.descripcion}</p>
      </header>

      <form
        aria-label={info.nuevo}
        className="flex flex-col gap-6"
        onKeyDown={(e) => {
          if (e.key === 'Enter' && (e.target as HTMLElement).tagName === 'INPUT') e.preventDefault()
        }}
        onSubmit={(e) => enviar(e, 'borrador')}
      >
        <Tarjeta className="grid gap-4 md:grid-cols-3">
          <Seleccion
            etiqueta={tipo === 'traspaso' ? 'Almacén origen' : 'Almacén'}
            opciones={activos.map((a) => ({ valor: a.id, texto: `${a.clave} · ${a.nombre}` }))}
            value={almacenId}
            disabled={conOrigen && origenId !== undefined}
            onChange={(e) => setAlmacenId(e.target.value)}
          />
          <Campo
            etiqueta="Fecha"
            type="date"
            max={hoy()}
            value={fecha}
            onChange={(e) => setFecha(e.target.value)}
          />
          {tipo === 'traspaso' && (
            <Seleccion
              etiqueta="Almacén destino"
              vacio="Elige"
              opciones={activos
                .filter((a) => String(a.id) !== almacenId)
                .map((a) => ({ valor: a.id, texto: `${a.clave} · ${a.nombre}` }))}
              value={destinoId}
              onChange={(e) => setDestinoId(e.target.value)}
            />
          )}
          {(tipo === 'entrada' || tipo === 'devolucion_proveedor') && (
            <Seleccion
              etiqueta="Proveedor"
              vacio="Elige"
              opciones={(proveedores.data ?? [])
                .filter((p) => p.activo)
                .map((p) => ({ valor: p.id, texto: p.nombre }))}
              value={proveedorId}
              disabled={tipo === 'devolucion_proveedor' && origenId !== undefined}
              onChange={(e) => setProveedorId(e.target.value)}
            />
          )}
          {(tipo === 'entrada' || tipo === 'ajuste_costo') && (
            <Campo
              etiqueta="Folio de factura"
              value={folioFactura}
              onChange={(e) => setFolioFactura(e.target.value)}
            />
          )}
          {tipo === 'entrada' && (
            <Campo
              etiqueta="Flete (sin IVA)"
              inputMode="decimal"
              ayuda="Se reparte entre los renglones según su importe."
              value={flete}
              onChange={(e) => setFlete(e.target.value)}
            />
          )}
          {(tipo === 'salida' || tipo === 'devolucion') && (
            <Buscador<PersonaNombre>
              etiqueta={tipo === 'salida' ? 'Solicitante' : 'Quién regresa'}
              placeholder="Nombre o apodo"
              nombre="personas"
              buscar={buscarPersonas}
              texto={(p) => p.nombre}
              clave={(p) => p.id}
              valor={solicitante?.nombre}
              alElegir={setSolicitante}
            />
          )}
          {tipo === 'salida' && (
            <>
              <Seleccion
                etiqueta="Contrato"
                vacio="Elige"
                opciones={contratosDelFrente.map((c) => ({ valor: c.id, texto: c.codigo }))}
                value={contratoId}
                onChange={(e) => setContratoId(e.target.value)}
              />
              <Seleccion
                etiqueta="Paquete"
                vacio="Elige"
                opciones={opcionesPaquete}
                value={paqueteId}
                onChange={(e) => setPaqueteId(e.target.value)}
              />
              <Seleccion
                etiqueta="Razón"
                opciones={opcionesRazon}
                value={clasificacionId}
                onChange={(e) => setClasificacionId(e.target.value)}
              />
            </>
          )}
          {tipo === 'merma' && (
            <>
              <Campo
                etiqueta="¿Qué le pasó al material?"
                value={motivo}
                onChange={(e) => setMotivo(e.target.value)}
              />
              <div className="flex flex-col gap-1.5">
                <label htmlFor="foto-merma" className="text-sm font-medium">
                  Foto
                </label>
                <label className="inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-md border border-borde bg-white px-4 text-sm font-medium hover:bg-fondo">
                  <Camera className="size-4" />
                  {foto
                    ? foto.nombre
                    : fotoMutacion.isPending
                      ? 'Subiendo…'
                      : 'Tomar o elegir foto'}
                  <input
                    id="foto-merma"
                    type="file"
                    accept="image/jpeg,image/png,image/webp,application/pdf"
                    capture="environment"
                    className="sr-only"
                    onChange={(e) => {
                      const archivo = e.target.files?.[0]
                      if (archivo) fotoMutacion.mutate(archivo)
                    }}
                  />
                </label>
                {fotoMutacion.isError && (
                  <p className="text-sm text-peligro">{mensajeDe(fotoMutacion.error)}</p>
                )}
              </div>
            </>
          )}
          <Campo
            etiqueta="Observaciones"
            value={observaciones}
            onChange={(e) => setObservaciones(e.target.value)}
          />
        </Tarjeta>

        {conOrigen && !existente && (
          <ElegirOrigen
            tipo={desdeVale ? 'salida' : 'entrada'}
            elegido={origen.data}
            alElegir={(id) => setOrigenId(id)}
          />
        )}

        <Tarjeta className="flex flex-col gap-3">
          <h2 className="font-medium">Materiales</h2>
          {renglones.length === 0 && (
            <p className="text-sm text-tenue">
              {conOrigen
                ? 'Elige el documento de origen para ver sus líneas.'
                : 'Agrega el primer material.'}
            </p>
          )}
          {renglones.map((r, indice) => (
            <div
              key={r.clave}
              className="grid items-start gap-3 border-b border-borde pb-3 last:border-0 md:grid-cols-12"
            >
              {conOrigen ? (
                <label className="flex min-h-11 items-center gap-2 text-sm md:col-span-4">
                  <input
                    type="checkbox"
                    className="size-5"
                    checked={r.elegido}
                    onChange={(e) => cambiar(r.clave, { elegido: e.target.checked })}
                  />
                  <span>
                    {r.material}
                    {r.etiqueta && <span className="block text-tenue">{r.etiqueta}</span>}
                  </span>
                </label>
              ) : (
                <div className="md:col-span-3">
                  <Buscador<Material>
                    etiqueta={`Material ${indice + 1}`}
                    placeholder="Clave o nombre"
                    nombre="materiales"
                    buscar={buscarMateriales}
                    texto={(m) => m.nombre}
                    detalle={(m) => `${m.clave} · ${m.unidadControl}`}
                    clave={(m) => m.id}
                    valor={r.material}
                    autoFocus={indice > 0 && indice === renglones.length - 1 && !r.materialId}
                    alElegir={(m) =>
                      cambiar(r.clave, {
                        materialId: m.id,
                        material: m.nombre,
                        unidadControl: m.unidadControl,
                        unidadControlId: m.unidadControlId,
                        unidadId: '',
                      })
                    }
                  />
                </div>
              )}

              {tipo === 'ajuste_costo' ? (
                <div className="md:col-span-3">
                  <Campo
                    etiqueta={`Costo correcto por ${r.unidadControl ?? 'unidad'} (sin IVA)`}
                    inputMode="decimal"
                    value={r.costoNuevo}
                    disabled={!r.elegido}
                    onChange={(e) => cambiar(r.clave, { costoNuevo: e.target.value })}
                  />
                </div>
              ) : (
                <>
                  <div className="md:col-span-2">
                    <Campo
                      etiqueta="Cantidad"
                      inputMode="decimal"
                      value={r.cantidad}
                      disabled={conOrigen && !r.elegido}
                      ayuda={
                        r.maximo !== undefined
                          ? `Hasta ${formatearNumero(r.maximo)} ${r.unidadControl ?? ''}`
                          : undefined
                      }
                      onKeyDown={(e) =>
                        (tipo === 'traspaso' || tipo === 'merma') && alFinal(e, r.clave)
                      }
                      onChange={(e) => cambiar(r.clave, { cantidad: e.target.value })}
                    />
                  </div>
                  {!conOrigen && (
                    <div className="md:col-span-2">
                      <Seleccion
                        etiqueta="Unidad"
                        opciones={opcionesUnidad}
                        vacio={r.unidadControl ?? '—'}
                        value={r.unidadId}
                        onChange={(e) => cambiar(r.clave, { unidadId: e.target.value })}
                      />
                    </div>
                  )}
                </>
              )}

              {tipo === 'entrada' && (
                <>
                  <div className="md:col-span-2">
                    <Campo
                      etiqueta="Precio sin IVA"
                      inputMode="decimal"
                      value={r.precio}
                      onChange={(e) => cambiar(r.clave, { precio: e.target.value })}
                    />
                  </div>
                  <div className="md:col-span-2">
                    <Campo
                      etiqueta="Descuento ($)"
                      inputMode="decimal"
                      value={r.descuento}
                      onKeyDown={(e) => alFinal(e, r.clave)}
                      onChange={(e) => cambiar(r.clave, { descuento: e.target.value })}
                    />
                  </div>
                </>
              )}

              {tipo === 'salida' && (
                <>
                  <div className="md:col-span-1">
                    <Campo
                      etiqueta="Mza"
                      value={r.manzana}
                      onChange={(e) => cambiar(r.clave, { manzana: e.target.value })}
                    />
                  </div>
                  <div className="md:col-span-3">
                    <Campo
                      etiqueta="Lotes"
                      placeholder="34 o 1-18, 20"
                      value={r.lotes}
                      ayuda={
                        <AyudaReparto
                          renglon={r}
                          lotes={lotes.data ?? []}
                          fraccionable={fraccionable}
                        />
                      }
                      onKeyDown={(e) => alFinal(e, r.clave)}
                      onChange={(e) => cambiar(r.clave, { lotes: e.target.value })}
                    />
                  </div>
                </>
              )}

              {tipo === 'reasignacion' && (
                <DestinoReasignacion
                  renglon={r}
                  contratos={(contratos.data ?? []).map((c) => ({ valor: c.id, texto: c.codigo }))}
                  paquetes={opcionesPaquete}
                  razones={opcionesRazon}
                  cambiar={(cambio) => cambiar(r.clave, cambio)}
                />
              )}

              {!conOrigen && (
                <div className="md:col-span-1 md:pt-7">
                  <Boton
                    variante="fantasma"
                    aria-label={`Quitar renglón ${indice + 1}`}
                    onClick={() => quitar(r.clave)}
                  >
                    <Trash2 className="size-4" />
                  </Boton>
                </div>
              )}
            </div>
          ))}
          {!conOrigen && (
            <div>
              <Boton variante="secundario" onClick={agregar}>
                <Plus className="size-4" /> Otro material
              </Boton>
            </div>
          )}
        </Tarjeta>

        {avisoValidacion && <Aviso>{avisoValidacion}</Aviso>}
        {sinAplicar && (
          <Aviso>
            Se guardó como {sinAplicar.folio}, pero no se aplicó: {sinAplicar.mensaje}{' '}
            <Link
              to="/movimientos/$id"
              params={{ id: String(sinAplicar.id) }}
              className="font-medium underline"
            >
              Ver documento
            </Link>
          </Aviso>
        )}
        {guardar.isError && guardar.error instanceof ErrorApi && (
          <Aviso>
            {mensajeDe(guardar.error)}
            {Object.entries(guardar.error.campos).map(([campo, mensaje]) => (
              <span key={campo} className="block">
                {campo}: {mensaje}
              </span>
            ))}
          </Aviso>
        )}

        <div className="flex flex-wrap gap-3">
          {AUTORIZA.has(tipo) ? (
            <Boton disabled={guardar.isPending} onClick={(e) => enviar(e, 'enviar')}>
              Guardar y enviar a autorizar
            </Boton>
          ) : (
            <Boton disabled={guardar.isPending} onClick={(e) => enviar(e, 'aplicar')}>
              Guardar y aplicar
            </Boton>
          )}
          <Boton type="submit" variante="secundario" disabled={guardar.isPending}>
            Guardar borrador
          </Boton>
        </div>
      </form>
    </div>
  )
}

/** Elegir el vale o la recepción de origen por su folio. */
function ElegirOrigen({
  tipo,
  elegido,
  alElegir,
}: {
  tipo: 'salida' | 'entrada'
  elegido?: Movimiento
  alElegir: (id: number) => void
}) {
  const [folio, setFolio] = useState('')
  const buscados = useQuery({
    ...consultasInventario.movimientos({
      tipo,
      estados: 'aplicado',
      folio: folio.trim() || undefined,
      pagina: 0,
    }),
    enabled: folio.trim().length >= 3,
  })
  return (
    <Tarjeta className="flex flex-col gap-3">
      <h2 className="font-medium">
        {tipo === 'salida' ? 'Vale de origen' : 'Recepción de origen'}
      </h2>
      {elegido ? (
        <p className="text-sm">
          <span className="font-mono">{elegido.folio}</span> · {formatearDia(elegido.fecha)} ·{' '}
          {elegido.almacen}
          {elegido.proveedor ? ` · ${elegido.proveedor}` : ''}
        </p>
      ) : (
        <>
          <Campo
            etiqueta="Folio"
            placeholder={tipo === 'salida' ? 'SAL-2026-000123' : 'ENT-2026-000045'}
            value={folio}
            onChange={(e) => setFolio(e.target.value)}
          />
          <ul className="flex flex-col gap-1">
            {(buscados.data ?? []).slice(0, 8).map((m) => (
              <li key={m.id}>
                <Boton variante="fantasma" onClick={() => alElegir(m.id)}>
                  <span className="font-mono">{m.folio}</span> · {formatearDia(m.fecha)} ·{' '}
                  {m.almacen} {m.contratos.length > 0 && `· ${m.contratos.join(', ')}`}
                </Boton>
              </li>
            ))}
          </ul>
        </>
      )}
    </Tarjeta>
  )
}

function DestinoReasignacion({
  renglon,
  contratos,
  paquetes,
  razones,
  cambiar,
}: {
  renglon: Renglon
  contratos: { valor: number; texto: string }[]
  paquetes: { valor: number; texto: string }[]
  razones: { valor: number; texto: string }[]
  cambiar: (cambio: Partial<Renglon>) => void
}) {
  const lotes = useQuery({
    ...consultas.unidades(Number(renglon.contratoId)),
    enabled: !!renglon.contratoId,
  })
  return (
    <>
      <div className="md:col-span-2">
        <Seleccion
          etiqueta="Contrato destino"
          opciones={contratos}
          value={renglon.contratoId}
          onChange={(e) => cambiar({ contratoId: e.target.value, loteId: '' })}
        />
      </div>
      <div className="md:col-span-1">
        <Seleccion
          etiqueta="Lote"
          vacio="—"
          opciones={(lotes.data ?? [])
            .filter((l) => l.activa)
            .map((l) => ({ valor: l.id, texto: l.clave }))}
          value={renglon.loteId}
          onChange={(e) => cambiar({ loteId: e.target.value })}
        />
      </div>
      <div className="md:col-span-2">
        <Seleccion
          etiqueta="Paquete"
          opciones={paquetes}
          value={renglon.paqueteId}
          onChange={(e) => cambiar({ paqueteId: e.target.value })}
        />
      </div>
      <div className="md:col-span-1">
        <Seleccion
          etiqueta="Razón"
          opciones={razones}
          value={renglon.clasificacionId}
          onChange={(e) => cambiar({ clasificacionId: e.target.value })}
        />
      </div>
    </>
  )
}

/** Lotes del renglón y, si hay cantidad, cuánto le toca a cada uno en la unidad de control. */
function AyudaReparto({
  renglon: r,
  lotes,
  fraccionable,
}: {
  renglon: Renglon
  lotes: { id: number; clave: string; activa: boolean }[]
  fraccionable: Map<string, boolean>
}) {
  const conversiones = useQuery({
    ...consultasCatalogo.conversiones(r.materialId),
    enabled: !!r.materialId && !!r.unidadId,
  })
  if (!r.lotes.trim() || !r.manzana.trim()) return null
  const resultado = lotesDeRenglon(lotes, r.manzana, r.lotes)
  if ('error' in resultado) return <span className="text-peligro">{resultado.error}</span>
  if (resultado.ids.length === 1) return <>{resultado.claves[0]}</>
  const capturada = numero(r.cantidad)
  const factor =
    !r.unidadId || r.unidadControlId === undefined || !r.materialId
      ? 1
      : factorConversion(
          conversiones.data ?? [],
          r.materialId,
          Number(r.unidadId),
          r.unidadControlId,
        )
  if (capturada === undefined || Number.isNaN(capturada) || factor === undefined) {
    return <>{resultado.ids.length} lotes</>
  }
  const total = Math.round(capturada * factor * 10_000) / 10_000
  const unidad = r.unidadControl ?? ''
  const partes = vistaReparto(total, resultado.ids.length, fraccionable.get(unidad) ?? true)
  return (
    <>
      {resultado.ids.length} lotes · {formatearNumero(total)} {unidad}:{' '}
      {formatearNumero(partes[0] ?? 0)} c/u, el último{' '}
      {formatearNumero(partes[partes.length - 1] ?? 0)}
    </>
  )
}

const aTexto = (valor: number | string | null | undefined) =>
  valor === null || valor === undefined ? '' : String(valor)

/** De un borrador guardado a renglones de captura (los repartos se juntan por renglón). */
function renglonesDe(m: Movimiento, tipo: string): Renglon[] {
  if (DESDE_VALE.has(tipo) || DESDE_RECEPCION.has(tipo)) {
    return m.lineas.map((l) =>
      renglonVacio({
        materialId: l.materialId,
        material: l.material,
        unidadControl: l.unidadControl,
        lineaOrigenId: l.lineaOrigenId ?? undefined,
        cantidad: String(l.cantidadCaptura),
        costoNuevo: aTexto(l.costoNuevo),
        contratoId: aTexto(l.contratoId),
        loteId: aTexto(l.unidadObraId),
        paqueteId: aTexto(l.paqueteId),
        clasificacionId: aTexto(l.clasificacionId),
      }),
    )
  }
  const porRenglon = new Map<number, typeof m.lineas>()
  for (const l of m.lineas) porRenglon.set(l.renglon, [...(porRenglon.get(l.renglon) ?? []), l])
  return [...porRenglon.values()].map((grupo) => {
    const primera = grupo[0]!
    const claves = grupo.map((l) => l.unidadObra ?? '').filter(Boolean)
    const manzana = /^M(\w+)-L/.exec(claves[0] ?? '')?.[1] ?? ''
    const total = grupo.reduce((suma, l) => suma + l.cantidadCaptura, 0)
    return renglonVacio({
      materialId: primera.materialId,
      material: primera.material,
      unidadControl: primera.unidadControl,
      cantidad: String(Math.round(total * 10_000) / 10_000),
      unidadId:
        primera.unidadCaptura === primera.unidadControl ? '' : String(primera.unidadCapturaId),
      precio: aTexto(primera.precio),
      descuento: aTexto(primera.descuento),
      manzana,
      lotes: claves.map((c) => c.replace(/^M\w+-L/, '')).join(', '),
      paqueteId: aTexto(primera.paqueteId),
      clasificacionId: aTexto(primera.clasificacionId),
    })
  })
}

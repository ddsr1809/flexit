import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Download, PenLine } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { api } from '@/api/cliente'
import { exigir, mensajeDe } from '@/api/errores'
import { Buscador } from '@/components/Buscador'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Insignia } from '@/components/ui/insignia'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { consultasCatalogo, type Material } from '@/features/catalogo/consultas'
import { siCoincide } from '@/features/estructura/consultas'
import { formatearMoneda, formatearNumero } from '@/lib/formato'
import { numero } from '@/lib/valores'
import { PERMISOS, puede, puedeAlguno, useSesion } from '@/sesion/sesion'
import {
  ESTADO_APERTURA,
  ESTADO_PRESTAMO_APERTURA,
  FILTROS_LINEAS,
  ORIGEN_COSTO,
  filtrarLineas,
  textoFirmas,
  type FiltroLineas,
} from './apertura'
import {
  buscarMateriales,
  buscarPersonas,
  consultasInventario,
  formatearDia,
  hoy,
  type DetalleApertura,
  type LineaApertura,
  type PersonaNombre,
  type PrestamoApertura,
} from './consultas'

/**
 * Una apertura de almacén: lo que falta (contar, resolver préstamos, costos), lo que se firma y,
 * ya aplicada, el acta y el plan de reversa.
 */
export function PantallaApertura({ id }: { id: number }) {
  const sesion = useSesion()
  const cliente = useQueryClient()
  const administra = puede(sesion, PERMISOS.administrar)
  const captura = puede(sesion, PERMISOS.movimientoCapturar)
  const firmante = puedeAlguno(sesion, PERMISOS.documentoAutorizar, PERMISOS.administrar)
  const consulta = useQuery(consultasInventario.apertura(id))
  const [cancelando, setCancelando] = useState(false)
  const [motivo, setMotivo] = useState('')

  const actualizar = (detalle: DetalleApertura) => {
    cliente.setQueryData(consultasInventario.apertura(id).queryKey, detalle)
    void cliente.invalidateQueries({ queryKey: ['aperturas'], exact: true })
    void cliente.invalidateQueries({ queryKey: ['conteos'] })
  }
  const enCero = useMutation({
    mutationFn: async () =>
      exigir(
        await api.POST('/api/v1/aperturas/{id}/sin-contar-en-cero', { params: { path: { id } } }),
      ),
    onSuccess: actualizar,
  })
  const retirar = useMutation({
    mutationFn: async () =>
      exigir(await api.POST('/api/v1/aperturas/{id}/retirar-firmas', { params: { path: { id } } })),
    onSuccess: actualizar,
  })
  const cancelar = useMutation({
    mutationFn: async () =>
      exigir(
        await api.POST('/api/v1/aperturas/{id}/cancelar', {
          params: { path: { id } },
          body: { motivo },
        }),
      ),
    onSuccess: (detalle) => {
      setCancelando(false)
      actualizar(detalle)
    },
  })

  if (consulta.isError) return <Aviso>{mensajeDe(consulta.error)}</Aviso>
  const d = consulta.data
  if (!d) return <p className="text-tenue">Cargando…</p>
  const a = d.apertura
  const abierta = a.estado === 'abierta'
  const enCurso = abierta || a.estado === 'en_firma'
  const r = d.resumen
  const error = enCero.error ?? retirar.error ?? cancelar.error

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <Link to="/aperturas" className="text-sm text-marca hover:underline">
            ← Aperturas
          </Link>
          <h1 className="text-2xl font-semibold">
            Apertura <span className="font-mono">{a.folio}</span>{' '}
            <Insignia
              tono={
                a.estado === 'aplicada' ? 'exito' : a.estado === 'cancelada' ? 'apagado' : 'neutro'
              }
            >
              {ESTADO_APERTURA[a.estado]}
            </Insignia>
          </h1>
          <p className="text-tenue">
            {a.almacen} · corte del {formatearDia(a.fecha)} · abrió {a.abrio}
          </p>
          <p className="text-sm text-tenue">{textoFirmas(a)}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Link
            to="/conteos/$id"
            params={{ id: String(a.conteoId) }}
            className="inline-flex min-h-11 items-center rounded-md border border-borde bg-white px-4 text-sm font-medium hover:bg-fondo"
          >
            Conteo {a.conteoFolio}
          </Link>
          {abierta && captura && r.sinContar > 0 && (
            <Boton
              variante="secundario"
              disabled={enCero.isPending}
              onClick={() => enCero.mutate()}
            >
              Marcar en 0 lo no encontrado ({r.sinContar})
            </Boton>
          )}
          {a.estado === 'en_firma' && firmante && (
            <Boton
              variante="secundario"
              disabled={retirar.isPending}
              onClick={() => retirar.mutate()}
            >
              Retirar firmas
            </Boton>
          )}
          {firmante && (
            <a
              href={`/api/v1/aperturas/${id}/acta.xlsx`}
              className="inline-flex min-h-11 items-center gap-2 rounded-md px-4 text-sm font-medium text-tenue hover:bg-fondo hover:text-texto"
            >
              <Download className="size-4" /> Acta en Excel
            </a>
          )}
          {enCurso && administra && !cancelando && (
            <Boton variante="fantasma" onClick={() => setCancelando(true)}>
              Cancelar apertura
            </Boton>
          )}
        </div>
      </header>

      {error && <Aviso>{mensajeDe(error)}</Aviso>}
      {a.estado === 'cancelada' && <Aviso>Cancelada: {a.motivoCancelacion}</Aviso>}
      {cancelando && (
        <Tarjeta className="flex flex-col gap-3 md:flex-row md:items-end">
          <div className="flex-1">
            <Campo
              etiqueta="¿Por qué se cancela? (se cancela también su conteo)"
              autoFocus
              value={motivo}
              onChange={(e) => setMotivo(e.target.value)}
            />
          </div>
          <Boton disabled={!motivo.trim() || cancelar.isPending} onClick={() => cancelar.mutate()}>
            Cancelar apertura
          </Boton>
          <Boton variante="fantasma" onClick={() => setCancelando(false)}>
            No
          </Boton>
        </Tarjeta>
      )}

      <section aria-label="Resumen" className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Dato
          titulo="Contados"
          valor={`${r.contados} de ${r.materiales}`}
          alerta={r.sinContar > 0}
        />
        <Dato
          titulo="Préstamos"
          valor={`${r.prestamosListos} listos · ${r.prestamosPendientes} por resolver`}
          alerta={r.prestamosPendientes > 0}
        />
        <Dato
          titulo="Con existencia"
          valor={`${r.conExistencia}${r.sinCosto > 0 ? ` · ${r.sinCosto} sin costo` : ''}`}
          alerta={r.sinCosto > 0}
        />
        <Dato titulo="Valor sin IVA" valor={r.importe == null ? '—' : formatearMoneda(r.importe)} />
      </section>

      {a.estado === 'aplicada' && <Aplicada detalle={d} />}

      {enCurso && d.faltaParaFirmar.length > 0 && (
        <Tarjeta>
          <h2 className="font-medium">Falta para firmar</h2>
          <ul className="mt-2 list-disc pl-5 text-sm">
            {d.faltaParaFirmar.map((f) => (
              <li key={f}>{f}</li>
            ))}
          </ul>
        </Tarjeta>
      )}

      {enCurso && d.firmaComo && d.faltaParaFirmar.length === 0 && (
        <Firmar detalle={d} alFirmar={actualizar} />
      )}

      {!r.conFotoExcel && enCurso && administra && (
        <p className="text-sm text-tenue">
          No está cargada la foto de Existencias del Excel (Migración › Histórico: foto de
          Existencias): sin ella no se compara lo contado con lo que decía el Excel.
        </p>
      )}

      <Prestamos detalle={d} editable={abierta && administra} alCambiar={actualizar} />
      <Materiales detalle={d} editable={abierta && administra} alCambiar={actualizar} />
    </div>
  )
}

function Dato({ titulo, valor, alerta }: { titulo: string; valor: string; alerta?: boolean }) {
  return (
    <Tarjeta>
      <p className="text-sm text-tenue">{titulo}</p>
      <p className={alerta ? 'text-lg font-semibold text-peligro' : 'text-lg font-semibold'}>
        {valor}
      </p>
    </Tarjeta>
  )
}

// ------------------------------------------------------------------------------------- firmar

function Firmar({
  detalle,
  alFirmar,
}: {
  detalle: DetalleApertura
  alFirmar: (d: DetalleApertura) => void
}) {
  const a = detalle.apertura
  const [revisado, setRevisado] = useState(false)
  const segunda = a.estado === 'en_firma'
  const como = detalle.firmaComo === 'coordinacion' ? 'coordinación' : 'administración'
  const firmar = useMutation({
    mutationFn: async () =>
      exigir(
        await api.POST('/api/v1/aperturas/{id}/firmar', {
          params: { path: { id: a.id } },
          body: { huella: detalle.huella },
        }),
      ),
    onSuccess: (d) => {
      setRevisado(false)
      alFirmar(d)
    },
  })
  return (
    <Tarjeta className="flex flex-col gap-3 border-marca">
      <h2 className="flex items-center gap-2 font-medium">
        <PenLine className="size-4" /> Firmar como {como}
      </h2>
      <p className="text-sm">
        Saldo de apertura de {a.almacen}: {detalle.resumen.conExistencia} materiales con existencia
        y {detalle.resumen.prestamosListos} préstamos vivos
        {detalle.resumen.importe != null && (
          <>, por {formatearMoneda(detalle.resumen.importe)}</>
        )}{' '}
        sin IVA.{' '}
        {segunda
          ? 'Con tu firma se aplica: entra al kardex, se cierra el conteo y los préstamos quedan vivos. No se deshace.'
          : 'Con tu firma queda congelado lo que se firma; para cambiar algo habrá que retirar las firmas.'}
      </p>
      <label className="flex min-h-11 items-center gap-2 text-sm">
        <input
          type="checkbox"
          className="size-4"
          checked={revisado}
          onChange={(e) => setRevisado(e.target.checked)}
        />
        Revisé el conteo, los préstamos y los costos
      </label>
      {firmar.error && <Aviso>{mensajeDe(firmar.error)}</Aviso>}
      <div>
        <Boton disabled={!revisado || firmar.isPending} onClick={() => firmar.mutate()}>
          {segunda ? 'Firmar y aplicar' : 'Firmar'}
        </Boton>
      </div>
    </Tarjeta>
  )
}

function Aplicada({ detalle }: { detalle: DetalleApertura }) {
  const a = detalle.apertura
  const [desde, setDesde] = useState(a.fecha)
  const [hasta, setHasta] = useState(hoy())
  return (
    <Tarjeta className="flex flex-col gap-3">
      <Aviso tipo="exito">
        Aplicada: el almacén arrancó con este saldo.{' '}
        {a.movimientoId != null && (
          <Link
            to="/movimientos/$id"
            params={{ id: String(a.movimientoId) }}
            className="font-medium underline"
          >
            Ver el saldo de apertura en Documentos
          </Link>
        )}
      </Aviso>
      <div>
        <h2 className="font-medium">Plan de reversa</h2>
        <p className="text-sm text-tenue">
          Si hubiera que regresar al Excel: los vales, devoluciones y reasignaciones del sistema con
          las columnas de la hoja Salidas, para pegarlos tal cual.
        </p>
      </div>
      <div className="grid gap-4 md:grid-cols-[12rem_12rem_auto] md:items-end">
        <Campo
          etiqueta="Desde"
          type="date"
          value={desde}
          onChange={(e) => setDesde(e.target.value)}
        />
        <Campo
          etiqueta="Hasta"
          type="date"
          value={hasta}
          onChange={(e) => setHasta(e.target.value)}
        />
        <a
          href={`/api/v1/almacenes/${a.almacenId}/salidas.xlsx?desde=${desde}&hasta=${hasta}`}
          className="inline-flex min-h-11 items-center gap-2 rounded-md border border-borde bg-white px-4 text-sm font-medium hover:bg-fondo"
        >
          <Download className="size-4" /> Salidas en Excel
        </a>
      </div>
    </Tarjeta>
  )
}

// ---------------------------------------------------------------------------------- préstamos

function Prestamos({
  detalle,
  editable,
  alCambiar,
}: {
  detalle: DetalleApertura
  editable: boolean
  alCambiar: (d: DetalleApertura) => void
}) {
  const id = detalle.apertura.id
  const [resolviendo, setResolviendo] = useState<PrestamoApertura>()
  const [nuevo, setNuevo] = useState(false)
  const traer = useMutation({
    mutationFn: async () =>
      exigir(
        await api.POST('/api/v1/aperturas/{id}/prestamos/traer', { params: { path: { id } } }),
      ),
    onSuccess: alCambiar,
  })
  return (
    <section aria-label="Préstamos abiertos" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h2 className="text-lg font-semibold">Préstamos abiertos al corte</h2>
          <p className="text-sm text-tenue">
            Lo prestado no está en el anaquel pero es del almacén: entra con lo contado y queda
            apartado como préstamo vivo con su responsable.
          </p>
        </div>
        {editable && (
          <div className="flex gap-2">
            <Boton variante="secundario" disabled={traer.isPending} onClick={() => traer.mutate()}>
              Volver a traer del Excel
            </Boton>
            <Boton variante="secundario" onClick={() => setNuevo(true)}>
              Agregar uno que no estaba
            </Boton>
          </div>
        )}
      </div>
      {traer.error && <Aviso>{mensajeDe(traer.error)}</Aviso>}
      {nuevo && (
        <NuevoPrestamoApertura
          apertura={id}
          alCerrar={() => setNuevo(false)}
          alGuardar={(d) => {
            setNuevo(false)
            alCambiar(d)
          }}
        />
      )}
      {resolviendo && (
        <ResolverPrestamo
          key={resolviendo.id}
          apertura={id}
          prestamo={resolviendo}
          alCerrar={() => setResolviendo(undefined)}
          alGuardar={(d) => {
            setResolviendo(undefined)
            alCambiar(d)
          }}
        />
      )}
      <Tarjeta className="p-0">
        <Tabla
          filas={detalle.prestamos}
          clave={(p) => p.id}
          vacio="El Excel no tiene préstamos abiertos de este frente."
          columnas={[
            {
              titulo: 'Fila',
              celda: (p) => (p.filaExcel == null ? 'a mano' : p.filaExcel),
              clase: 'text-tenue',
            },
            { titulo: 'Artículo (Excel)', celda: (p) => p.articulo },
            {
              titulo: 'Cantidad',
              celda: (p) => formatearNumero(p.cantidad),
              clase: 'text-right',
            },
            {
              titulo: 'Material',
              celda: (p) =>
                p.material ? (
                  <span>
                    <span className="font-mono">{p.materialClave}</span> {p.material}
                  </span>
                ) : (
                  <span className="text-peligro">Sin ligar</span>
                ),
            },
            {
              titulo: 'Responsable',
              celda: (p) =>
                p.responsable ?? <span className="text-tenue">{p.responsableTexto ?? '—'}</span>,
            },
            { titulo: 'Desde', celda: (p) => formatearDia(p.fecha), clase: 'whitespace-nowrap' },
            {
              titulo: 'Estado',
              celda: (p) => (
                <span title={p.motivo ?? undefined}>
                  <Insignia
                    tono={
                      p.estado === 'listo'
                        ? 'exito'
                        : p.estado === 'descartado'
                          ? 'apagado'
                          : 'neutro'
                    }
                  >
                    {ESTADO_PRESTAMO_APERTURA[p.estado]}
                  </Insignia>
                </span>
              ),
            },
          ]}
          acciones={(p) =>
            editable ? (
              <Boton variante="fantasma" onClick={() => setResolviendo(p)}>
                Resolver
              </Boton>
            ) : null
          }
        />
      </Tarjeta>
    </section>
  )
}

function ResolverPrestamo({
  apertura,
  prestamo,
  alCerrar,
  alGuardar,
}: {
  apertura: number
  prestamo: PrestamoApertura
  alCerrar: () => void
  alGuardar: (d: DetalleApertura) => void
}) {
  const unidades = useQuery(consultasCatalogo.unidades)
  const [material, setMaterial] = useState<{ id: number; nombre: string } | undefined>(
    prestamo.materialId && prestamo.material
      ? { id: prestamo.materialId, nombre: prestamo.material }
      : undefined,
  )
  const [persona, setPersona] = useState<PersonaNombre | undefined>(
    prestamo.responsableId && prestamo.responsable
      ? { id: prestamo.responsableId, nombre: prestamo.responsable, activa: true }
      : undefined,
  )
  const [cantidad, setCantidad] = useState(String(prestamo.cantidad))
  const [motivo, setMotivo] = useState(prestamo.motivo ?? '')
  const [unidadId, setUnidadId] = useState('')
  const piezas = unidades.data?.find((u) => u.clave === 'PZA')
  const unidad = unidadId || String(piezas?.id ?? unidades.data?.[0]?.id ?? '')

  const guardar = useMutation({
    mutationFn: async (estado: 'pendiente' | 'listo' | 'descartado') =>
      exigir(
        await api.PATCH('/api/v1/aperturas/{id}/prestamos/{prestamoId}', {
          params: {
            path: { id: apertura, prestamoId: prestamo.id },
            header: siCoincide(prestamo.version),
          },
          body: {
            materialId: material?.id,
            responsableId: persona?.id,
            cantidad: numero(cantidad) ?? 0,
            estado,
            motivo: estado === 'descartado' ? motivo : undefined,
          },
        }),
      ),
    onSuccess: alGuardar,
  })
  const alta = useMutation({
    mutationFn: async () =>
      exigir(
        await api.POST('/api/v1/aperturas/{id}/prestamos/{prestamoId}/herramienta', {
          params: { path: { id: apertura, prestamoId: prestamo.id } },
          body: { unidadId: Number(unidad) },
        }),
      ),
    onSuccess: alGuardar,
  })
  const cantidadValida = (numero(cantidad) ?? 0) > 0
  const error = guardar.error ?? alta.error

  return (
    <Tarjeta className="flex flex-col gap-4">
      <h3 className="font-medium">
        {prestamo.articulo}
        {prestamo.filaExcel != null && (
          <span className="text-sm font-normal text-tenue">
            {' '}
            · fila {prestamo.filaExcel} del Excel
          </span>
        )}
      </h3>
      <div className="grid gap-4 md:grid-cols-[2fr_2fr_7rem] md:items-end">
        <Buscador<Material>
          etiqueta="Material"
          placeholder="Clave o nombre"
          nombre="materiales"
          buscar={buscarMateriales}
          texto={(m) => m.nombre}
          detalle={(m) => m.clave}
          clave={(m) => m.id}
          valor={material?.nombre}
          alElegir={(m) => setMaterial({ id: m.id, nombre: m.nombre })}
        />
        <Buscador<PersonaNombre>
          etiqueta={`Quién la tiene${prestamo.responsableTexto ? ` (Excel: ${prestamo.responsableTexto})` : ''}`}
          placeholder="Nombre"
          nombre="personas"
          buscar={buscarPersonas}
          texto={(p) => p.nombre}
          clave={(p) => p.id}
          valor={persona?.nombre}
          alElegir={setPersona}
        />
        <Campo
          etiqueta="Cantidad"
          inputMode="decimal"
          value={cantidad}
          onChange={(e) => setCantidad(e.target.value)}
        />
      </div>
      {!material && (
        <div className="grid gap-4 rounded-md bg-fondo p-3 md:grid-cols-[1fr_12rem_auto] md:items-end">
          <p className="text-sm">
            ¿No es un material del catálogo? Se da de alta como <b>{prestamo.articulo}</b>, con la
            categoría Herramienta y sin precio de referencia (su costo se captura abajo).
          </p>
          <Seleccion
            etiqueta="Unidad"
            opciones={(unidades.data ?? []).map((u) => ({ valor: u.id, texto: u.clave }))}
            value={unidad}
            onChange={(e) => setUnidadId(e.target.value)}
          />
          <Boton
            variante="secundario"
            disabled={!unidad || alta.isPending}
            onClick={() => alta.mutate()}
          >
            Dar de alta como herramienta
          </Boton>
        </div>
      )}
      {error && <Aviso>{mensajeDe(error)}</Aviso>}
      <div className="flex flex-wrap items-end gap-2">
        <Boton
          disabled={!material || !persona || !cantidadValida || guardar.isPending}
          onClick={() => guardar.mutate('listo')}
        >
          Listo: se vuelve préstamo vivo
        </Boton>
        <div className="min-w-64 flex-1">
          <Campo
            etiqueta="Motivo para descartarlo"
            placeholder="Ya regresó, es de otro almacén…"
            value={motivo}
            onChange={(e) => setMotivo(e.target.value)}
          />
        </div>
        <Boton
          variante="secundario"
          disabled={!motivo.trim() || !cantidadValida || guardar.isPending}
          onClick={() => guardar.mutate('descartado')}
        >
          Descartar
        </Boton>
        {prestamo.estado !== 'pendiente' && (
          <Boton
            variante="fantasma"
            disabled={!cantidadValida || guardar.isPending}
            onClick={() => guardar.mutate('pendiente')}
          >
            Dejar por resolver
          </Boton>
        )}
        <Boton variante="fantasma" onClick={alCerrar}>
          Cerrar
        </Boton>
      </div>
    </Tarjeta>
  )
}

function NuevoPrestamoApertura({
  apertura,
  alCerrar,
  alGuardar,
}: {
  apertura: number
  alCerrar: () => void
  alGuardar: (d: DetalleApertura) => void
}) {
  const [articulo, setArticulo] = useState('')
  const [cantidad, setCantidad] = useState('1')
  const [responsable, setResponsable] = useState('')
  const agregar = useMutation({
    mutationFn: async () =>
      exigir(
        await api.POST('/api/v1/aperturas/{id}/prestamos', {
          params: { path: { id: apertura } },
          body: {
            articulo,
            cantidad: numero(cantidad) ?? 0,
            responsableTexto: responsable.trim() || undefined,
          },
        }),
      ),
    onSuccess: alGuardar,
  })
  const enviar = (evento: FormEvent) => {
    evento.preventDefault()
    agregar.mutate()
  }
  return (
    <Tarjeta>
      <form
        aria-label="Agregar préstamo"
        onSubmit={enviar}
        className="grid gap-4 md:grid-cols-[2fr_7rem_2fr_auto_auto] md:items-end"
      >
        <Campo etiqueta="Artículo" value={articulo} onChange={(e) => setArticulo(e.target.value)} />
        <Campo
          etiqueta="Cantidad"
          inputMode="decimal"
          value={cantidad}
          onChange={(e) => setCantidad(e.target.value)}
        />
        <Campo
          etiqueta="Quién la tiene"
          value={responsable}
          onChange={(e) => setResponsable(e.target.value)}
        />
        <Boton type="submit" disabled={!articulo.trim() || agregar.isPending}>
          Agregar
        </Boton>
        <Boton variante="fantasma" onClick={alCerrar}>
          Cancelar
        </Boton>
      </form>
      {agregar.error && <Aviso>{mensajeDe(agregar.error)}</Aviso>}
    </Tarjeta>
  )
}

// --------------------------------------------------------------------------------- materiales

function Materiales({
  detalle,
  editable,
  alCambiar,
}: {
  detalle: DetalleApertura
  editable: boolean
  alCambiar: (d: DetalleApertura) => void
}) {
  const id = detalle.apertura.id
  const [filtro, setFiltro] = useState<FiltroLineas>(
    detalle.resumen.sinCosto > 0 ? 'sin-costo' : 'existencia',
  )
  const [texto, setTexto] = useState('')
  const [costos, setCostos] = useState<Record<number, string>>({})
  const conCostos = detalle.lineas.some((l) => l.costoUnitario != null) || editable
  const conExcel = detalle.lineas.some((l) => l.existenciaExcel != null)
  const lineas = filtrarLineas(detalle.lineas, filtro, texto)
  const cambios = Object.entries(costos)
    .filter(([, v]) => v.trim() !== '')
    .map(([m, v]) => ({ materialId: Number(m), costoUnitario: numero(v) ?? Number.NaN }))
  const invalido = cambios.some((c) => Number.isNaN(c.costoUnitario) || c.costoUnitario < 0)

  const guardar = useMutation({
    mutationFn: async (lista: { materialId: number; costoUnitario?: number }[]) =>
      exigir(
        await api.PUT('/api/v1/aperturas/{id}/costos', {
          params: { path: { id } },
          body: { costos: lista },
        }),
      ),
    onSuccess: (d) => {
      setCostos({})
      alCambiar(d)
    },
  })

  return (
    <section aria-label="Materiales" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold">Materiales</h2>
          <p className="text-sm text-tenue">
            Entra lo contado más lo prestado. El costo sin IVA es el capturado, o el ponderado de
            las entradas del Excel hasta el corte, o el precio de referencia.
          </p>
        </div>
        <div className="flex flex-wrap items-end gap-3">
          <Seleccion
            etiqueta="Ver"
            opciones={FILTROS_LINEAS.filter((f) => f.valor !== 'diferencia' || conExcel)}
            value={filtro}
            onChange={(e) => setFiltro(e.target.value as FiltroLineas)}
          />
          <Campo etiqueta="Buscar" value={texto} onChange={(e) => setTexto(e.target.value)} />
          {editable && (
            <Boton
              disabled={cambios.length === 0 || invalido || guardar.isPending}
              onClick={() => guardar.mutate(cambios)}
            >
              Guardar costos {cambios.length > 0 ? cambios.length : ''}
            </Boton>
          )}
        </div>
      </div>
      {guardar.error && <Aviso>{mensajeDe(guardar.error)}</Aviso>}
      <Tarjeta className="p-0">
        <Tabla
          filas={lineas}
          clave={(l) => l.materialId}
          vacio="No hay materiales con ese filtro."
          columnas={[
            { titulo: 'Clave', celda: (l) => <span className="font-mono">{l.clave}</span> },
            { titulo: 'Material', celda: (l) => l.material },
            { titulo: 'Unidad', celda: (l) => l.unidad },
            {
              titulo: 'Contado',
              celda: (l) => (l.contado == null ? '—' : formatearNumero(l.contado)),
              clase: 'text-right',
            },
            {
              titulo: 'Prestado',
              celda: (l) => (l.prestado === 0 ? '' : formatearNumero(l.prestado)),
              clase: 'text-right',
            },
            {
              titulo: 'Entra',
              celda: (l) => formatearNumero(l.cantidad),
              clase: 'text-right font-medium',
            },
            ...(conCostos
              ? [
                  {
                    titulo: 'Costo sin IVA',
                    celda: (l: LineaApertura) =>
                      editable ? (
                        <input
                          aria-label={`Costo de ${l.material}`}
                          inputMode="decimal"
                          className={`min-h-11 w-28 rounded-md border px-2 text-right ${l.faltaCosto ? 'border-peligro' : 'border-borde'}`}
                          placeholder={l.costoUnitario == null ? 'falta' : String(l.costoUnitario)}
                          value={costos[l.materialId] ?? ''}
                          onChange={(e) => setCostos({ ...costos, [l.materialId]: e.target.value })}
                        />
                      ) : l.costoUnitario == null ? (
                        '—'
                      ) : (
                        formatearMoneda(l.costoUnitario)
                      ),
                    clase: 'text-right',
                  },
                  {
                    titulo: 'De dónde',
                    celda: (l: LineaApertura) =>
                      l.origenCosto ? (
                        <span title={l.detalleCosto ?? undefined} className="text-sm">
                          {ORIGEN_COSTO[l.origenCosto]}
                          {editable && l.origenCosto === 'capturado' && (
                            <button
                              type="button"
                              className="ml-2 text-marca underline"
                              onClick={() =>
                                guardar.mutate([
                                  { materialId: l.materialId, costoUnitario: undefined },
                                ])
                              }
                            >
                              quitar
                            </button>
                          )}
                        </span>
                      ) : l.faltaCosto ? (
                        <span className="text-sm text-peligro">Falta</span>
                      ) : (
                        ''
                      ),
                  },
                  {
                    titulo: 'Importe',
                    celda: (l: LineaApertura) =>
                      l.importe == null || l.cantidad === 0 ? '' : formatearMoneda(l.importe),
                    clase: 'text-right',
                  },
                ]
              : []),
            ...(conExcel
              ? [
                  {
                    titulo: 'Excel',
                    celda: (l: LineaApertura) =>
                      l.existenciaExcel == null ? '—' : formatearNumero(l.existenciaExcel),
                    clase: 'text-right text-tenue',
                  },
                  {
                    titulo: 'Diferencia',
                    celda: (l: LineaApertura) =>
                      l.diferenciaExcel == null || l.diferenciaExcel === 0 ? (
                        ''
                      ) : (
                        <span className={l.diferenciaExcel < 0 ? 'text-peligro' : 'text-exito'}>
                          {l.diferenciaExcel > 0 ? '+' : ''}
                          {formatearNumero(l.diferenciaExcel)}
                        </span>
                      ),
                    clase: 'text-right',
                  },
                ]
              : []),
          ]}
        />
      </Tarjeta>
      {conExcel && (
        <p className="text-sm text-tenue">
          Excel = inventario inicial + entradas − salidas entregadas, de todo el almacén del Excel
          (no tiene frente): es una referencia. El conteo es el que vale; la diferencia no se busca
          a quién cargarla.
        </p>
      )}
    </section>
  )
}

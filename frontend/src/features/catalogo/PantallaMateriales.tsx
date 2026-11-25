import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, Pencil } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { api } from '@/api/cliente'
import { ErrorApi, exigir, mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { EstadoActivo } from '@/components/ui/insignia'
import { Seleccion } from '@/components/ui/seleccion'
import { Tarjeta } from '@/components/ui/tarjeta'
import { useDiferido } from '@/lib/diferido'
import { formatearMoneda, formatearNumero } from '@/lib/formato'
import { aTexto, id, numero, texto } from '@/lib/valores'
import { siCoincide } from '@/features/estructura/consultas'
import { PERMISOS, puede, puedeAlguno, useSesion } from '@/sesion/sesion'
import { consultasCatalogo, TAMANO_PAGINA, type Material } from './consultas'

/**
 * Catálogo de materiales: búsqueda por clave o por palabras del nombre (sin importar acentos),
 * alta y cambio, y las conversiones de cada material (1 caja = 4 piezas).
 */
export function PantallaMateriales() {
  const sesion = useSesion()
  const edita = puede(sesion, PERMISOS.catalogoEditar)
  const vePrecio = puedeAlguno(
    sesion,
    PERMISOS.precioVer,
    PERMISOS.costoVerTodo,
    PERMISOS.catalogoEditar,
  )
  const [busqueda, setBusqueda] = useState('')
  const [categoria, setCategoria] = useState('')
  const [soloActivos, setSoloActivos] = useState(true)
  const [pagina, setPagina] = useState(0)
  const [elegido, setElegido] = useState<Material>()
  const q = useDiferido(busqueda.trim(), 250)

  const categorias = useQuery(consultasCatalogo.categorias)
  const materiales = useQuery(
    consultasCatalogo.materiales({
      q: q || undefined,
      categoriaId: id(categoria),
      soloActivos,
      pagina,
    }),
  )
  const total = materiales.data?.total ?? 0
  const paginas = Math.max(1, Math.ceil(total / TAMANO_PAGINA))

  const filtrar = (cambio: () => void) => {
    cambio()
    setPagina(0)
  }

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold">Materiales</h1>
        <p className="text-tenue">
          Cada material con su unidad de control: en ella se lleva la existencia y el costo.
        </p>
      </header>

      <div className="grid gap-4 md:grid-cols-[2fr_1fr_auto] md:items-end">
        <Campo
          etiqueta="Buscar"
          type="search"
          placeholder="Clave o palabras del nombre: cemento gris"
          value={busqueda}
          onChange={(e) => filtrar(() => setBusqueda(e.target.value))}
        />
        <Seleccion
          etiqueta="Categoría"
          vacio="Todas"
          opciones={(categorias.data ?? []).map((c) => ({ valor: c.id, texto: c.nombre }))}
          value={categoria}
          onChange={(e) => filtrar(() => setCategoria(e.target.value))}
        />
        <label className="flex min-h-11 items-center gap-2 text-sm">
          <input
            type="checkbox"
            className="size-4"
            checked={soloActivos}
            onChange={(e) => filtrar(() => setSoloActivos(e.target.checked))}
          />
          Solo activos
        </label>
      </div>

      <Tarjeta className="p-0">
        {materiales.isError && (
          <div className="p-4">
            <Aviso>{mensajeDe(materiales.error)}</Aviso>
          </div>
        )}
        <Tabla
          filas={materiales.data?.elementos}
          clave={(m) => m.id}
          seleccionada={elegido?.id}
          vacio={q ? `Ningún material coincide con "${q}".` : 'Todavía no hay materiales.'}
          columnas={[
            { titulo: 'Clave', celda: (m) => <span className="font-mono">{m.clave}</span> },
            { titulo: 'Nombre', celda: (m) => m.nombre },
            { titulo: 'Categoría', celda: (m) => m.categoria ?? '—' },
            { titulo: 'Unidad', celda: (m) => m.unidadControl },
            ...(vePrecio
              ? [
                  {
                    titulo: 'Precio ref. (sin IVA)',
                    celda: (m: Material) =>
                      m.precioReferencia == null ? '—' : formatearMoneda(m.precioReferencia),
                    clase: 'text-right whitespace-nowrap',
                  },
                ]
              : []),
            { titulo: 'Estado', celda: (m) => <EstadoActivo activo={m.activo} /> },
          ]}
          acciones={(m) => (
            <Boton
              variante="fantasma"
              aria-label={`${edita ? 'Editar' : 'Ver'} ${m.nombre}`}
              onClick={() => setElegido(m)}
            >
              <Pencil className="size-4" /> {edita ? 'Editar' : 'Ver'}
            </Boton>
          )}
        />
        <div className="flex items-center justify-between gap-4 border-t border-borde px-4 py-2 text-sm text-tenue">
          <span>
            {formatearNumero(total)} {total === 1 ? 'material' : 'materiales'}
          </span>
          <div className="flex items-center gap-2">
            <Boton
              variante="fantasma"
              aria-label="Página anterior"
              disabled={pagina === 0}
              onClick={() => setPagina(pagina - 1)}
            >
              <ChevronLeft className="size-4" />
            </Boton>
            <span>
              Página {pagina + 1} de {paginas}
            </span>
            <Boton
              variante="fantasma"
              aria-label="Página siguiente"
              disabled={pagina + 1 >= paginas}
              onClick={() => setPagina(pagina + 1)}
            >
              <ChevronRight className="size-4" />
            </Boton>
          </div>
        </div>
      </Tarjeta>

      <div className="grid gap-6 lg:grid-cols-2">
        {edita && (
          <FormularioMaterial
            key={elegido?.id ?? 'nuevo'}
            material={elegido}
            alTerminar={(m) => setElegido(m)}
            alCancelar={() => setElegido(undefined)}
          />
        )}
        {elegido && <Conversiones key={elegido.id} material={elegido} edita={edita} />}
      </div>
    </div>
  )
}

function FormularioMaterial({
  material,
  alTerminar,
  alCancelar,
}: {
  material?: Material
  alTerminar: (m?: Material) => void
  alCancelar: () => void
}) {
  const cliente = useQueryClient()
  const unidades = useQuery(consultasCatalogo.unidades)
  const categorias = useQuery(consultasCatalogo.categorias)
  const [clave, setClave] = useState(material?.clave ?? '')
  const [nombre, setNombre] = useState(material?.nombre ?? '')
  const [categoria, setCategoria] = useState(aTexto(material?.categoriaId))
  const [unidad, setUnidad] = useState(aTexto(material?.unidadControlId))
  const [precio, setPrecio] = useState(aTexto(material?.precioReferencia))
  const [activo, setActivo] = useState(material?.activo ?? true)
  const precioNumero = numero(precio)

  const guardar = useMutation({
    mutationFn: async () => {
      const body = {
        clave: texto(clave),
        nombre: texto(nombre) ?? '',
        categoriaId: id(categoria),
        unidadControlId: id(unidad) ?? 0,
        precioReferencia: precioNumero,
        activo,
      }
      return material
        ? exigir(
            await api.PATCH('/api/v1/materiales/{id}', {
              params: { path: { id: material.id }, header: siCoincide(material.version) },
              body,
            }),
          )
        : exigir(await api.POST('/api/v1/materiales', { body }))
    },
    onSuccess: (guardado) => {
      void cliente.invalidateQueries({ queryKey: ['materiales'] })
      if (material) {
        alTerminar(guardado)
      } else {
        setClave('')
        setNombre('')
        setPrecio('')
      }
    },
    onError: (error) => {
      if (error instanceof ErrorApi && error.codigo === 'VERSION_DESACTUALIZADA') {
        void cliente.invalidateQueries({ queryKey: ['materiales'] })
        alCancelar()
      }
    },
  })
  const errores = guardar.error instanceof ErrorApi ? guardar.error.campos : {}

  const enviar = (evento: FormEvent) => {
    evento.preventDefault()
    if (!Number.isNaN(precioNumero)) guardar.mutate()
  }

  return (
    <Tarjeta>
      <h2 className="text-lg font-medium">
        {material ? `Cambiar ${material.clave}` : 'Nuevo material'}
      </h2>
      <form noValidate onSubmit={enviar} className="mt-4 grid gap-4 md:grid-cols-2">
        <Campo
          etiqueta="Clave"
          autoCapitalize="characters"
          ayuda={material ? undefined : 'Vacía: se asigna la siguiente (001, 002…)'}
          value={clave}
          error={errores.clave}
          onChange={(e) => setClave(e.target.value)}
        />
        <Seleccion
          etiqueta="Unidad de control"
          vacio="Elige la unidad"
          opciones={(unidades.data ?? [])
            .filter((u) => u.activa)
            .map((u) => ({ valor: u.id, texto: `${u.clave} · ${u.nombre}` }))}
          value={unidad}
          error={errores.unidadControlId}
          onChange={(e) => setUnidad(e.target.value)}
        />
        <div className="md:col-span-2">
          <Campo
            etiqueta="Nombre"
            value={nombre}
            error={errores.nombre}
            onChange={(e) => setNombre(e.target.value)}
          />
        </div>
        <Seleccion
          etiqueta="Categoría"
          vacio="Sin categoría"
          opciones={(categorias.data ?? [])
            .filter((c) => c.activa)
            .map((c) => ({ valor: c.id, texto: c.nombre }))}
          value={categoria}
          onChange={(e) => setCategoria(e.target.value)}
        />
        <Campo
          etiqueta="Precio de referencia sin IVA (opcional)"
          inputMode="decimal"
          value={precio}
          error={
            Number.isNaN(precioNumero) ? 'Solo el número, como 210.50' : errores.precioReferencia
          }
          onChange={(e) => setPrecio(e.target.value)}
        />
        {material && (
          <label className="flex min-h-11 items-center gap-2 text-sm">
            <input
              type="checkbox"
              className="size-4"
              checked={activo}
              onChange={(e) => setActivo(e.target.checked)}
            />
            Activo
          </label>
        )}
        <div className="flex flex-col gap-3 md:col-span-2">
          {guardar.isError && <Aviso>{mensajeDe(guardar.error)}</Aviso>}
          {guardar.isSuccess && (
            <Aviso tipo="exito">
              {guardar.data.clave} · {guardar.data.nombre} guardado.
            </Aviso>
          )}
          <div className="flex gap-2">
            <Boton type="submit" disabled={guardar.isPending}>
              {material ? 'Guardar cambios' : 'Dar de alta'}
            </Boton>
            {material && (
              <Boton variante="secundario" onClick={alCancelar}>
                Nuevo material
              </Boton>
            )}
          </div>
        </div>
      </form>
    </Tarjeta>
  )
}

function Conversiones({ material, edita }: { material: Material; edita: boolean }) {
  const cliente = useQueryClient()
  const consulta = consultasCatalogo.conversiones(material.id)
  const conversiones = useQuery(consulta)
  const unidades = useQuery(consultasCatalogo.unidades)
  const [origen, setOrigen] = useState('')
  const [factor, setFactor] = useState('')
  const factorNumero = numero(factor)

  const crear = useMutation({
    mutationFn: async () =>
      exigir(
        await api.POST('/api/v1/conversiones', {
          body: {
            materialId: material.id,
            origenId: id(origen) ?? 0,
            destinoId: material.unidadControlId,
            factor: factorNumero ?? 0,
          },
        }),
      ),
    onSuccess: () => {
      setFactor('')
      void cliente.invalidateQueries({ queryKey: consulta.queryKey })
    },
  })

  const enviar = (evento: FormEvent) => {
    evento.preventDefault()
    if (factorNumero !== undefined && !Number.isNaN(factorNumero)) crear.mutate()
  }

  const propias = (conversiones.data ?? []).filter((c) => c.materialId === material.id)
  const generales = (conversiones.data ?? []).filter((c) => c.materialId == null)

  return (
    <Tarjeta>
      <h2 className="text-lg font-medium">Conversiones de {material.nombre}</h2>
      <p className="mt-1 text-sm text-tenue">
        Para capturar en otra unidad y que el sistema lo pase a {material.unidadControl}.
      </p>
      <ul className="mt-3 flex flex-col gap-1 text-sm">
        {propias.map((c) => (
          <li key={c.id}>
            1 {c.origen} = {formatearNumero(c.factor)} {c.destino}
          </li>
        ))}
        {propias.length === 0 && <li className="text-tenue">Sin conversiones propias.</li>}
      </ul>
      {generales.length > 0 && (
        <p className="mt-2 text-xs text-tenue">
          Generales:{' '}
          {generales
            .map((c) => `1 ${c.origen} = ${formatearNumero(c.factor)} ${c.destino}`)
            .join(' · ')}
        </p>
      )}
      {edita && (
        <form noValidate onSubmit={enviar} className="mt-4 grid gap-4 md:grid-cols-2">
          <Seleccion
            etiqueta="1 …"
            vacio="Unidad"
            opciones={(unidades.data ?? [])
              .filter((u) => u.id !== material.unidadControlId && u.activa)
              .map((u) => ({ valor: u.id, texto: `${u.clave} · ${u.nombre}` }))}
            value={origen}
            onChange={(e) => setOrigen(e.target.value)}
          />
          <Campo
            etiqueta={`… equivale a (${material.unidadControl})`}
            inputMode="decimal"
            value={factor}
            error={Number.isNaN(factorNumero) ? 'Solo el número' : undefined}
            onChange={(e) => setFactor(e.target.value)}
          />
          <div className="flex flex-col gap-3 md:col-span-2">
            {crear.isError && <Aviso>{mensajeDe(crear.error)}</Aviso>}
            <Boton
              type="submit"
              variante="secundario"
              className="self-start"
              disabled={crear.isPending || !origen}
            >
              Agregar conversión
            </Boton>
          </div>
        </form>
      )}
    </Tarjeta>
  )
}

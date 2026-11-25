import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '@/api/cliente'
import { exigir } from '@/api/errores'
import { CatalogoSimple } from '@/components/CatalogoSimple'
import { EstadoActivo, Insignia } from '@/components/ui/insignia'
import { cn } from '@/lib/clases'
import { aTexto, casilla, id, texto } from '@/lib/valores'
import { consultasCatalogo } from '@/features/catalogo/consultas'
import { PERMISOS, puede, useSesion } from '@/sesion/sesion'
import { consultas, siCoincide } from './consultas'

const PESTANAS = ['Clientes', 'Frentes', 'Prototipos', 'Unidades de medida', 'Categorías'] as const
type Pestana = (typeof PESTANAS)[number]

const MAGNITUDES = [
  { valor: 'conteo', texto: 'Conteo (pieza, caja, saco…)' },
  { valor: 'masa', texto: 'Masa' },
  { valor: 'volumen', texto: 'Volumen' },
  { valor: 'longitud', texto: 'Longitud' },
  { valor: 'superficie', texto: 'Superficie' },
]

/** Catálogos chicos de la estructura y del almacén, uno por pestaña. */
export function PantallaCatalogos() {
  const sesion = useSesion()
  const [pestana, setPestana] = useState<Pestana>('Clientes')
  const administra = puede(sesion, PERMISOS.administrar)
  const editaCatalogo = puede(sesion, PERMISOS.catalogoEditar)

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold">Catálogos</h1>
        <p className="text-tenue">
          Quién paga la obra, dónde se construye, qué prototipos hay y cómo se mide el material.
        </p>
      </header>
      <div
        role="tablist"
        aria-label="Catálogos"
        className="flex gap-1 overflow-x-auto border-b border-borde"
      >
        {PESTANAS.map((nombre) => (
          <button
            key={nombre}
            type="button"
            role="tab"
            aria-selected={pestana === nombre}
            onClick={() => setPestana(nombre)}
            className={cn(
              'min-h-11 border-b-2 px-4 text-sm whitespace-nowrap',
              pestana === nombre
                ? 'border-marca font-medium text-marca'
                : 'border-transparent text-tenue hover:text-texto',
            )}
          >
            {nombre}
          </button>
        ))}
      </div>
      <div role="tabpanel" aria-label={pestana}>
        {pestana === 'Clientes' && <Clientes puedeEditar={administra} />}
        {pestana === 'Frentes' && <Frentes puedeEditar={administra} />}
        {pestana === 'Prototipos' && <Prototipos puedeEditar={administra} />}
        {pestana === 'Unidades de medida' && <Unidades puedeEditar={editaCatalogo} />}
        {pestana === 'Categorías' && <Categorias puedeEditar={editaCatalogo} />}
      </div>
    </div>
  )
}

function Clientes({ puedeEditar }: { puedeEditar: boolean }) {
  const consulta = useQuery(consultas.clientes)
  return (
    <CatalogoSimple
      titulo="Clientes"
      descripcion="Quien paga la obra: Ruba, gobierno, una cadena."
      consulta={consulta}
      claveConsulta={consultas.clientes.queryKey}
      nombre={(c) => c.nombre}
      columnas={[
        { titulo: 'Nombre', celda: (c) => c.nombre },
        { titulo: 'RFC', celda: (c) => <span className="font-mono">{c.rfc ?? '—'}</span> },
        { titulo: 'Estado', celda: (c) => <EstadoActivo activo={c.activo} /> },
      ]}
      campos={[
        { nombre: 'nombre', etiqueta: 'Nombre' },
        { nombre: 'rfc', etiqueta: 'RFC (opcional)' },
        { nombre: 'activo', etiqueta: 'Activo', tipo: 'casilla', soloEdicion: true },
      ]}
      inicial={{ nombre: '', rfc: '', activo: true }}
      aValores={(c) => ({ nombre: c.nombre, rfc: aTexto(c.rfc), activo: c.activo })}
      textoAlta="Nuevo cliente"
      puedeEditar={puedeEditar}
      guardar={async (v, editando) => {
        const body = { nombre: texto(v.nombre) ?? '', rfc: texto(v.rfc), activo: casilla(v.activo) }
        return editando
          ? exigir(
              await api.PATCH('/api/v1/clientes/{id}', {
                params: { path: { id: editando.id }, header: siCoincide(editando.version) },
                body,
              }),
            )
          : exigir(await api.POST('/api/v1/clientes', { body }))
      }}
    />
  )
}

function Frentes({ puedeEditar }: { puedeEditar: boolean }) {
  const consulta = useQuery(consultas.frentes)
  const clientes = useQuery(consultas.clientes)
  return (
    <CatalogoSimple
      titulo="Frentes"
      descripcion="Fraccionamiento u obra de un cliente. La clave corta (CORCELES) encabeza las claves de lote al importar."
      consulta={consulta}
      claveConsulta={consultas.frentes.queryKey}
      nombre={(f) => f.nombre}
      columnas={[
        { titulo: 'Clave', celda: (f) => <span className="font-mono">{f.clave}</span> },
        { titulo: 'Nombre', celda: (f) => f.nombre },
        { titulo: 'Cliente', celda: (f) => f.cliente },
        { titulo: 'Estado', celda: (f) => <EstadoActivo activo={f.activo} /> },
      ]}
      campos={[
        {
          nombre: 'clienteId',
          etiqueta: 'Cliente',
          tipo: 'seleccion',
          vacio: 'Elige el cliente',
          opciones: (clientes.data ?? []).map((c) => ({ valor: c.id, texto: c.nombre })),
        },
        { nombre: 'clave', etiqueta: 'Clave corta', ayuda: 'De 2 a 12 letras o números' },
        { nombre: 'nombre', etiqueta: 'Nombre' },
        { nombre: 'activo', etiqueta: 'Activo', tipo: 'casilla', soloEdicion: true },
      ]}
      inicial={{ clienteId: '', clave: '', nombre: '', activo: true }}
      aValores={(f) => ({
        clienteId: String(f.clienteId),
        clave: f.clave,
        nombre: f.nombre,
        activo: f.activo,
      })}
      textoAlta="Nuevo frente"
      puedeEditar={puedeEditar}
      guardar={async (v, editando) => {
        const body = {
          clienteId: id(v.clienteId) ?? 0,
          clave: texto(v.clave) ?? '',
          nombre: texto(v.nombre) ?? '',
          activo: casilla(v.activo),
        }
        return editando
          ? exigir(
              await api.PATCH('/api/v1/frentes/{id}', {
                params: { path: { id: editando.id }, header: siCoincide(editando.version) },
                body,
              }),
            )
          : exigir(await api.POST('/api/v1/frentes', { body }))
      }}
    />
  )
}

function Prototipos({ puedeEditar }: { puedeEditar: boolean }) {
  const consulta = useQuery(consultas.prototipos)
  return (
    <CatalogoSimple
      titulo="Prototipos"
      descripcion="Modelos de vivienda. Cada lote lleva su prototipo."
      consulta={consulta}
      claveConsulta={consultas.prototipos.queryKey}
      nombre={(p) => p.clave}
      columnas={[
        { titulo: 'Clave', celda: (p) => <span className="font-mono">{p.clave}</span> },
        { titulo: 'Nombre', celda: (p) => p.nombre },
        { titulo: 'Descripción', celda: (p) => p.descripcion ?? '—' },
        { titulo: 'Estado', celda: (p) => <EstadoActivo activo={p.activo} /> },
      ]}
      campos={[
        { nombre: 'clave', etiqueta: 'Clave', ayuda: 'Como P-65' },
        { nombre: 'nombre', etiqueta: 'Nombre' },
        { nombre: 'descripcion', etiqueta: 'Descripción (opcional)' },
        { nombre: 'activo', etiqueta: 'Activo', tipo: 'casilla', soloEdicion: true },
      ]}
      inicial={{ clave: '', nombre: '', descripcion: '', activo: true }}
      aValores={(p) => ({
        clave: p.clave,
        nombre: p.nombre,
        descripcion: aTexto(p.descripcion),
        activo: p.activo,
      })}
      textoAlta="Nuevo prototipo"
      puedeEditar={puedeEditar}
      guardar={async (v, editando) => {
        const body = {
          clave: texto(v.clave) ?? '',
          nombre: texto(v.nombre) ?? '',
          descripcion: texto(v.descripcion),
          activo: casilla(v.activo),
        }
        return editando
          ? exigir(
              await api.PATCH('/api/v1/prototipos/{id}', {
                params: { path: { id: editando.id }, header: siCoincide(editando.version) },
                body,
              }),
            )
          : exigir(await api.POST('/api/v1/prototipos', { body }))
      }}
    />
  )
}

function Unidades({ puedeEditar }: { puedeEditar: boolean }) {
  const consulta = useQuery(consultasCatalogo.unidades)
  return (
    <CatalogoSimple
      titulo="Unidades de medida"
      descripcion="Las del sistema no se cambian. Agrega una propia solo si ninguna sirve; las conversiones de un material (1 caja = 4 piezas) se capturan en el material."
      consulta={consulta}
      claveConsulta={consultasCatalogo.unidades.queryKey}
      nombre={(u) => u.clave}
      columnas={[
        { titulo: 'Clave', celda: (u) => <span className="font-mono">{u.clave}</span> },
        { titulo: 'Nombre', celda: (u) => u.nombre },
        { titulo: 'Magnitud', celda: (u) => u.magnitud },
        { titulo: 'Fracciones', celda: (u) => (u.fraccionable ? 'Sí' : 'No') },
        {
          titulo: 'Origen',
          celda: (u) =>
            u.delSistema ? (
              <Insignia>Del sistema</Insignia>
            ) : (
              <Insignia tono="apagado">Propia</Insignia>
            ),
        },
      ]}
      campos={[
        { nombre: 'clave', etiqueta: 'Clave', ayuda: 'De 1 a 6 letras, como JGO' },
        { nombre: 'nombre', etiqueta: 'Nombre' },
        { nombre: 'magnitud', etiqueta: 'Magnitud', tipo: 'seleccion', opciones: MAGNITUDES },
        { nombre: 'fraccionable', etiqueta: 'Admite fracciones (0.5)', tipo: 'casilla' },
        { nombre: 'activa', etiqueta: 'Activa', tipo: 'casilla', soloEdicion: true },
      ]}
      inicial={{ clave: '', nombre: '', magnitud: 'conteo', fraccionable: false, activa: true }}
      aValores={(u) => ({
        clave: u.clave,
        nombre: u.nombre,
        magnitud: u.magnitud,
        fraccionable: u.fraccionable,
        activa: u.activa,
      })}
      textoAlta="Nueva unidad"
      puedeEditar={puedeEditar}
      editable={(u) => !u.delSistema}
      guardar={async (v, editando) => {
        const body = {
          clave: texto(v.clave) ?? '',
          nombre: texto(v.nombre) ?? '',
          magnitud: texto(v.magnitud) ?? 'conteo',
          fraccionable: casilla(v.fraccionable),
          activa: casilla(v.activa),
        }
        return editando
          ? exigir(
              await api.PATCH('/api/v1/unidades-medida/{id}', {
                params: { path: { id: editando.id }, header: siCoincide(editando.version) },
                body,
              }),
            )
          : exigir(await api.POST('/api/v1/unidades-medida', { body }))
      }}
    />
  )
}

function Categorias({ puedeEditar }: { puedeEditar: boolean }) {
  const consulta = useQuery(consultasCatalogo.categorias)
  return (
    <CatalogoSimple
      titulo="Categorías"
      descripcion="Agrupan los materiales para buscarlos y para los reportes."
      consulta={consulta}
      claveConsulta={consultasCatalogo.categorias.queryKey}
      nombre={(c) => c.nombre}
      columnas={[
        { titulo: 'Nombre', celda: (c) => c.nombre },
        { titulo: 'Estado', celda: (c) => <EstadoActivo activo={c.activa} /> },
      ]}
      campos={[
        { nombre: 'nombre', etiqueta: 'Nombre' },
        { nombre: 'activa', etiqueta: 'Activa', tipo: 'casilla', soloEdicion: true },
      ]}
      inicial={{ nombre: '', activa: true }}
      aValores={(c) => ({ nombre: c.nombre, activa: c.activa })}
      textoAlta="Nueva categoría"
      puedeEditar={puedeEditar}
      guardar={async (v, editando) => {
        const body = { nombre: texto(v.nombre) ?? '', activa: casilla(v.activa) }
        return editando
          ? exigir(
              await api.PATCH('/api/v1/categorias/{id}', {
                params: { path: { id: editando.id }, header: siCoincide(editando.version) },
                body,
              }),
            )
          : exigir(await api.POST('/api/v1/categorias', { body }))
      }}
    />
  )
}

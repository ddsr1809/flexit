import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '@/api/cliente'
import { exigir } from '@/api/errores'
import { CatalogoSimple } from '@/components/CatalogoSimple'
import { Campo } from '@/components/ui/campo'
import { EstadoActivo } from '@/components/ui/insignia'
import { useDiferido } from '@/lib/diferido'
import { aTexto, casilla, numero, texto } from '@/lib/valores'
import { siCoincide } from '@/features/estructura/consultas'
import { PERMISOS, puede, useSesion } from '@/sesion/sesion'
import { consultasCatalogo } from './consultas'

const TASAS = [
  { valor: '0.16', texto: '16 %' },
  { valor: '0.08', texto: '8 % (región fronteriza)' },
  { valor: '0', texto: '0 %' },
]

/** Proveedores de material, con RFC y tasa de IVA por defecto. */
export function PantallaProveedores() {
  const sesion = useSesion()
  const [busqueda, setBusqueda] = useState('')
  const q = useDiferido(busqueda.trim(), 250)
  const opciones = consultasCatalogo.proveedores(q || undefined)
  const consulta = useQuery(opciones)

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold">Proveedores</h1>
        <p className="text-tenue">
          Con RFC para no repetirlos y la tasa de IVA con la que facturan.
        </p>
      </header>
      <div className="max-w-md">
        <Campo
          etiqueta="Buscar"
          type="search"
          placeholder="Nombre o RFC"
          value={busqueda}
          onChange={(e) => setBusqueda(e.target.value)}
        />
      </div>
      <CatalogoSimple
        titulo="Proveedores"
        consulta={consulta}
        claveConsulta={['proveedores']}
        nombre={(p) => p.nombre}
        columnas={[
          { titulo: 'Nombre', celda: (p) => p.nombre },
          { titulo: 'RFC', celda: (p) => <span className="font-mono">{p.rfc ?? '—'}</span> },
          {
            titulo: 'Contacto',
            celda: (p) => [p.contacto, p.telefono].filter(Boolean).join(' · ') || '—',
          },
          {
            titulo: 'IVA',
            celda: (p) => (p.tasaIva == null ? '—' : `${Math.round(p.tasaIva * 100)} %`),
          },
          {
            titulo: 'Crédito',
            celda: (p) => (p.diasCredito == null ? '—' : `${p.diasCredito} días`),
          },
          { titulo: 'Estado', celda: (p) => <EstadoActivo activo={p.activo} /> },
        ]}
        campos={[
          { nombre: 'nombre', etiqueta: 'Nombre o razón social' },
          { nombre: 'rfc', etiqueta: 'RFC (opcional)' },
          { nombre: 'contacto', etiqueta: 'Contacto (opcional)' },
          { nombre: 'telefono', etiqueta: 'Teléfono (opcional)' },
          { nombre: 'correo', etiqueta: 'Correo (opcional)' },
          {
            nombre: 'tasaIva',
            etiqueta: 'IVA',
            tipo: 'seleccion',
            vacio: 'Sin definir',
            opciones: TASAS,
          },
          { nombre: 'diasCredito', etiqueta: 'Días de crédito (opcional)', tipo: 'numero' },
          { nombre: 'activo', etiqueta: 'Activo', tipo: 'casilla', soloEdicion: true },
        ]}
        inicial={{
          nombre: '',
          rfc: '',
          contacto: '',
          telefono: '',
          correo: '',
          tasaIva: '0.16',
          diasCredito: '',
          activo: true,
        }}
        aValores={(p) => ({
          nombre: p.nombre,
          rfc: aTexto(p.rfc),
          contacto: aTexto(p.contacto),
          telefono: aTexto(p.telefono),
          correo: aTexto(p.correo),
          tasaIva: aTexto(p.tasaIva),
          diasCredito: aTexto(p.diasCredito),
          activo: p.activo,
        })}
        textoAlta="Nuevo proveedor"
        puedeEditar={puede(sesion, PERMISOS.catalogoEditar)}
        guardar={async (v, editando) => {
          const body = {
            nombre: texto(v.nombre) ?? '',
            rfc: texto(v.rfc),
            contacto: texto(v.contacto),
            telefono: texto(v.telefono),
            correo: texto(v.correo),
            tasaIva: numero(v.tasaIva),
            diasCredito: numero(v.diasCredito),
            activo: casilla(v.activo),
          }
          return editando
            ? exigir(
                await api.PATCH('/api/v1/proveedores/{id}', {
                  params: { path: { id: editando.id }, header: siCoincide(editando.version) },
                  body,
                }),
              )
            : exigir(await api.POST('/api/v1/proveedores', { body }))
        }}
      />
    </div>
  )
}

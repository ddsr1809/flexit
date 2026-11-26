import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Pencil } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { api } from '@/api/cliente'
import { ErrorApi, exigir, mensajeDe } from '@/api/errores'
import { Tabla } from '@/components/Tabla'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { EstadoActivo, Insignia } from '@/components/ui/insignia'
import { Tarjeta } from '@/components/ui/tarjeta'
import { siCoincide } from '@/features/estructura/consultas'
import { consultasMigracion, type Persona } from '@/features/migracion/consultas'
import { useDiferido } from '@/lib/diferido'
import { aTexto, texto } from '@/lib/valores'

/**
 * Personas: trabajadores del SUA y del destajo, solicitantes del almacén. Son datos personales:
 * solo los ve administración. Las que entraron desde un apodo quedan "para revisar" hasta que
 * alguien escriba el nombre completo.
 */
export function PantallaPersonas() {
  const [busqueda, setBusqueda] = useState('')
  const [soloRevisar, setSoloRevisar] = useState(false)
  const [elegida, setElegida] = useState<Persona>()
  const q = useDiferido(busqueda.trim(), 250)
  const personas = useQuery(consultasMigracion.personas(q || undefined, soloRevisar))

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <header>
        <h1 className="text-2xl font-semibold">Personas</h1>
        <p className="text-tenue">
          Las que vienen del SUA, del destajo y del almacén, ligadas por la migración. Aquí se
          completa el nombre y se captura NSS o CURP cuando falte.
        </p>
      </header>
      <div className="grid gap-4 md:grid-cols-[2fr_auto] md:items-end">
        <Campo
          etiqueta="Buscar"
          type="search"
          placeholder="Nombre, NSS o CURP"
          value={busqueda}
          onChange={(e) => setBusqueda(e.target.value)}
        />
        <label className="flex min-h-11 items-center gap-2 text-sm">
          <input
            type="checkbox"
            className="size-4"
            checked={soloRevisar}
            onChange={(e) => setSoloRevisar(e.target.checked)}
          />
          Solo para revisar
        </label>
      </div>
      {elegida && (
        <Editar key={elegida.id} persona={elegida} alTerminar={() => setElegida(undefined)} />
      )}
      <Tarjeta className="p-0">
        {personas.isError && (
          <div className="p-4">
            <Aviso>{mensajeDe(personas.error)}</Aviso>
          </div>
        )}
        <Tabla
          filas={personas.data}
          clave={(p) => p.id}
          seleccionada={elegida?.id}
          vacio="No hay personas con esa búsqueda."
          columnas={[
            {
              titulo: 'Nombre',
              celda: (p) => (
                <span className="flex flex-wrap items-center gap-2">
                  {p.nombre}
                  {p.revisar && <Insignia>Revisar</Insignia>}
                </span>
              ),
            },
            { titulo: 'NSS', celda: (p) => <span className="font-mono">{p.nss ?? '—'}</span> },
            { titulo: 'CURP', celda: (p) => <span className="font-mono">{p.curp ?? '—'}</span> },
            { titulo: 'Nota', celda: (p) => <span className="text-tenue">{p.nota ?? ''}</span> },
            { titulo: 'Estado', celda: (p) => <EstadoActivo activo={p.activa} /> },
          ]}
          acciones={(p) => (
            <Boton
              variante="fantasma"
              aria-label={`Cambiar ${p.nombre}`}
              onClick={() => setElegida(p)}
            >
              <Pencil className="size-4" />
            </Boton>
          )}
        />
      </Tarjeta>
    </div>
  )
}

function Editar({ persona, alTerminar }: { persona: Persona; alTerminar: () => void }) {
  const cliente = useQueryClient()
  const [valores, setValores] = useState({
    nombre: persona.nombre,
    nss: aTexto(persona.nss),
    curp: aTexto(persona.curp),
    nota: aTexto(persona.nota),
    revisar: persona.revisar,
    activa: persona.activa,
  })
  const guardar = useMutation({
    mutationFn: async () =>
      exigir(
        await api.PATCH('/api/v1/personas/{id}', {
          params: { path: { id: persona.id }, header: siCoincide(persona.version) },
          body: {
            nombre: valores.nombre.trim(),
            nss: texto(valores.nss),
            curp: texto(valores.curp),
            nota: texto(valores.nota),
            revisar: valores.revisar,
            activa: valores.activa,
          },
        }),
      ),
    onSuccess: () => {
      void cliente.invalidateQueries({ queryKey: ['personas'] })
      alTerminar()
    },
  })
  const campos = guardar.error instanceof ErrorApi ? guardar.error.campos : {}
  const enviar = (evento: FormEvent) => {
    evento.preventDefault()
    guardar.mutate()
  }
  const poner = (campo: keyof typeof valores, valor: string | boolean) =>
    setValores({ ...valores, [campo]: valor })

  return (
    <Tarjeta>
      <form aria-label="Cambiar persona" onSubmit={enviar} className="flex flex-col gap-4">
        <h2 className="text-lg font-medium">{persona.nombre}</h2>
        <div className="grid gap-4 md:grid-cols-3">
          <Campo
            etiqueta="Nombre completo"
            value={valores.nombre}
            error={campos.nombre}
            onChange={(e) => poner('nombre', e.target.value)}
          />
          <Campo
            etiqueta="NSS (11 dígitos)"
            inputMode="numeric"
            value={valores.nss}
            error={campos.nss}
            onChange={(e) => poner('nss', e.target.value)}
          />
          <Campo
            etiqueta="CURP"
            value={valores.curp}
            error={campos.curp}
            onChange={(e) => poner('curp', e.target.value)}
          />
        </div>
        <Campo
          etiqueta="Nota"
          value={valores.nota}
          error={campos.nota}
          onChange={(e) => poner('nota', e.target.value)}
        />
        <div className="flex flex-wrap gap-6 text-sm">
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              className="size-4"
              checked={valores.revisar}
              onChange={(e) => poner('revisar', e.target.checked)}
            />
            Para revisar
          </label>
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              className="size-4"
              checked={valores.activa}
              onChange={(e) => poner('activa', e.target.checked)}
            />
            Activa
          </label>
        </div>
        {guardar.isError && <Aviso>{mensajeDe(guardar.error)}</Aviso>}
        <div className="flex gap-2">
          <Boton type="submit" disabled={guardar.isPending || !valores.nombre.trim()}>
            Guardar
          </Boton>
          <Boton variante="secundario" onClick={alTerminar}>
            Cancelar
          </Boton>
        </div>
      </form>
    </Tarjeta>
  )
}

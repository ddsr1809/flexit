import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { api } from '@/api/cliente'
import { ErrorApi, exigir, mensajeDe } from '@/api/errores'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Seleccion } from '@/components/ui/seleccion'
import { aTexto, id, numero, texto, type Valores } from '@/lib/valores'
import { consultas, siCoincide, type Contrato } from './consultas'

const ESTADOS = [
  { valor: 'activo', texto: 'Activo' },
  { valor: 'terminado', texto: 'Terminado' },
  { valor: 'cancelado', texto: 'Cancelado' },
]

const vacio: Valores = {
  clienteId: '',
  frenteId: '',
  codigo: '',
  numeroObra: '',
  descripcion: '',
  inicio: '',
  fin: '',
  monto: '',
  estado: 'activo',
}

const desdeContrato = (c: Contrato): Valores => ({
  clienteId: String(c.clienteId),
  frenteId: aTexto(c.frenteId),
  codigo: c.codigo,
  numeroObra: aTexto(c.numeroObra),
  descripcion: aTexto(c.descripcion),
  inicio: aTexto(c.inicio),
  fin: aTexto(c.fin),
  monto: aTexto(c.monto),
  estado: c.estado,
})

/**
 * Alta (sin `contrato`) o cambio de un contrato. El frente se elige entre los del cliente; el
 * código se acepta con "/" y se guarda con "-".
 */
export function FormularioContrato({
  contrato,
  alGuardar,
}: {
  contrato?: Contrato
  alGuardar?: (guardado: Contrato) => void
}) {
  const cliente = useQueryClient()
  const clientes = useQuery(consultas.clientes)
  const frentes = useQuery(consultas.frentes)
  const [valores, setValores] = useState<Valores>(contrato ? desdeContrato(contrato) : vacio)
  const cambiar = (nombre: string, valor: string) =>
    setValores((anteriores) => ({
      ...anteriores,
      [nombre]: valor,
      ...(nombre === 'clienteId' ? { frenteId: '' } : {}),
    }))

  const guardar = useMutation({
    mutationFn: async () => {
      const monto = numero(valores.monto)
      const body = {
        clienteId: id(valores.clienteId) ?? 0,
        frenteId: id(valores.frenteId),
        codigo: texto(valores.codigo) ?? '',
        numeroObra: texto(valores.numeroObra),
        descripcion: texto(valores.descripcion),
        inicio: texto(valores.inicio),
        fin: texto(valores.fin),
        monto: monto === undefined || Number.isNaN(monto) ? undefined : monto,
        estado: texto(valores.estado),
      }
      return contrato
        ? exigir(
            await api.PATCH('/api/v1/contratos/{id}', {
              params: { path: { id: contrato.id }, header: siCoincide(contrato.version) },
              body,
            }),
          )
        : exigir(await api.POST('/api/v1/contratos', { body }))
    },
    onSuccess: (guardado) => {
      void cliente.invalidateQueries({ queryKey: ['contratos'] })
      if (!contrato) setValores(vacio)
      alGuardar?.(guardado)
    },
    onError: (error) => {
      if (error instanceof ErrorApi && error.codigo === 'VERSION_DESACTUALIZADA') {
        void cliente.invalidateQueries({ queryKey: ['contratos'] })
      }
    },
  })

  const errores = guardar.error instanceof ErrorApi ? guardar.error.campos : {}
  const montoInvalido = Number.isNaN(numero(valores.monto))
  const clienteElegido = id(valores.clienteId)
  const frentesDelCliente = (frentes.data ?? []).filter((f) => f.clienteId === clienteElegido)
  const conUnidades = (contrato?.unidades ?? 0) > 0

  const enviar = (evento: FormEvent) => {
    evento.preventDefault()
    if (!montoInvalido) guardar.mutate()
  }

  return (
    <form noValidate onSubmit={enviar} className="grid gap-4 md:grid-cols-2">
      <Seleccion
        etiqueta="Cliente"
        vacio="Elige el cliente"
        opciones={(clientes.data ?? [])
          .filter((c) => c.activo)
          .map((c) => ({ valor: c.id, texto: c.nombre }))}
        value={String(valores.clienteId)}
        disabled={conUnidades}
        error={errores.clienteId}
        onChange={(e) => cambiar('clienteId', e.target.value)}
      />
      <Seleccion
        etiqueta="Frente"
        vacio={clienteElegido ? 'Sin frente (obra pública)' : 'Primero elige el cliente'}
        opciones={frentesDelCliente.map((f) => ({ valor: f.id, texto: f.nombre }))}
        value={String(valores.frenteId)}
        disabled={conUnidades || !clienteElegido}
        error={errores.frenteId}
        onChange={(e) => cambiar('frenteId', e.target.value)}
      />
      <Campo
        etiqueta="Código del contrato"
        autoCapitalize="characters"
        placeholder="60-KZ7-MF6-C002-2026"
        value={String(valores.codigo)}
        error={errores.codigo}
        onChange={(e) => cambiar('codigo', e.target.value)}
      />
      <Campo
        etiqueta="No. de obra (IMSS)"
        autoCapitalize="characters"
        placeholder="C1269209"
        value={String(valores.numeroObra)}
        error={errores.numeroObra}
        onChange={(e) => cambiar('numeroObra', e.target.value)}
      />
      <Campo
        etiqueta="Inicio"
        type="date"
        value={String(valores.inicio)}
        error={errores.inicio}
        onChange={(e) => cambiar('inicio', e.target.value)}
      />
      <Campo
        etiqueta="Fin"
        type="date"
        value={String(valores.fin)}
        error={errores.fin}
        onChange={(e) => cambiar('fin', e.target.value)}
      />
      <Campo
        etiqueta="Monto (opcional)"
        inputMode="decimal"
        value={String(valores.monto)}
        error={montoInvalido ? 'Escribe solo el número, como 4882033.11' : errores.monto}
        onChange={(e) => cambiar('monto', e.target.value)}
      />
      {contrato ? (
        <Seleccion
          etiqueta="Estado"
          opciones={ESTADOS}
          value={String(valores.estado)}
          onChange={(e) => cambiar('estado', e.target.value)}
        />
      ) : (
        <div />
      )}
      <div className="md:col-span-2">
        <Campo
          etiqueta="Descripción (opcional)"
          value={String(valores.descripcion)}
          error={errores.descripcion}
          onChange={(e) => cambiar('descripcion', e.target.value)}
        />
      </div>
      <div className="flex flex-col gap-3 md:col-span-2">
        {conUnidades && (
          <p className="text-sm text-tenue">
            El contrato ya tiene lotes: el cliente y el frente ya no se pueden cambiar.
          </p>
        )}
        {guardar.isError && <Aviso>{mensajeDe(guardar.error)}</Aviso>}
        {guardar.isSuccess && <Aviso tipo="exito">Contrato {guardar.data.codigo} guardado.</Aviso>}
        <Boton type="submit" disabled={guardar.isPending} className="self-start">
          {guardar.isPending ? 'Guardando…' : contrato ? 'Guardar cambios' : 'Dar de alta'}
        </Boton>
      </div>
    </form>
  )
}

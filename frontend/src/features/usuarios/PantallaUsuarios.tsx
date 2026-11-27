import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { api } from '@/api/cliente'
import { ErrorApi, exigir, mensajeDe } from '@/api/errores'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Tarjeta } from '@/components/ui/tarjeta'
import type { components } from '@/api/esquema'
import { useSesion } from '@/sesion/sesion'

const esquema = z.object({
  usuario: z
    .string()
    .trim()
    .regex(/^[A-Za-z0-9._-]{3,60}$/, 'De 3 a 60 letras, números, punto o guion'),
  nombre: z.string().trim().min(1, 'Escribe el nombre').max(120),
  correo: z.union([z.literal(''), z.email('Correo inválido')]),
  contrasena: z.string().min(10, 'Al menos 10 caracteres'),
  roles: z.array(z.string()).min(1, 'Elige al menos un rol'),
})

type Datos = z.infer<typeof esquema>

const consultaUsuarios = ['usuarios'] as const

export function PantallaUsuarios() {
  const cliente = useQueryClient()
  const [creado, setCreado] = useState<string>()
  const usuarios = useQuery({
    queryKey: consultaUsuarios,
    queryFn: async () => exigir(await api.GET('/api/v1/usuarios')),
  })
  const roles = useQuery({
    queryKey: ['roles'],
    queryFn: async () => exigir(await api.GET('/api/v1/usuarios/roles')),
    staleTime: Infinity,
  })
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors },
  } = useForm<Datos>({
    resolver: zodResolver(esquema),
    defaultValues: { usuario: '', nombre: '', correo: '', contrasena: '', roles: [] },
  })

  const crear = useMutation({
    mutationFn: async (datos: Datos) =>
      exigir(
        await api.POST('/api/v1/usuarios', {
          body: { ...datos, correo: datos.correo || undefined },
        }),
      ),
    onSuccess: (usuario) => {
      setCreado(usuario.usuario)
      reset()
      void cliente.invalidateQueries({ queryKey: consultaUsuarios })
    },
    onError: (error) => {
      if (error instanceof ErrorApi) {
        for (const [campo, mensaje] of Object.entries(error.campos)) {
          setError(campo as keyof Datos, { message: mensaje })
        }
      }
    },
  })

  return (
    <div className="flex max-w-5xl flex-col gap-6">
      <h1 className="text-2xl font-semibold">Usuarios</h1>

      <Tarjeta className="overflow-x-auto p-0">
        {usuarios.isError && (
          <div className="p-4">
            <Aviso>{mensajeDe(usuarios.error)}</Aviso>
          </div>
        )}
        <table className="w-full text-left text-sm">
          <thead className="border-b border-borde bg-fondo text-tenue">
            <tr>
              <th className="px-4 py-3 font-medium">Usuario</th>
              <th className="px-4 py-3 font-medium">Nombre</th>
              <th className="px-4 py-3 font-medium">Roles</th>
              <th className="px-4 py-3 font-medium">Estado</th>
              <th className="px-4 py-3 font-medium">Segundo factor</th>
            </tr>
          </thead>
          <tbody>
            {usuarios.data?.map((u) => (
              <tr key={u.id} className="border-b border-borde last:border-0">
                <td className="px-4 py-3 font-mono">{u.usuario}</td>
                <td className="px-4 py-3">{u.nombre}</td>
                <td className="px-4 py-3">{u.roles.map((r) => r.nombre).join(', ')}</td>
                <td className="px-4 py-3">{u.activo ? 'Activo' : 'Inactivo'}</td>
                <td className="px-4 py-3">
                  <SegundoFactor usuario={u} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Tarjeta>

      <Tarjeta>
        <h2 className="text-lg font-medium">Dar de alta</h2>
        <form
          noValidate
          onSubmit={handleSubmit((datos) => crear.mutate(datos))}
          className="mt-4 grid gap-4 md:grid-cols-2"
        >
          <Campo
            etiqueta="Usuario"
            autoCapitalize="none"
            error={errors.usuario?.message}
            {...register('usuario')}
          />
          <Campo
            etiqueta="Nombre completo"
            error={errors.nombre?.message}
            {...register('nombre')}
          />
          <Campo
            etiqueta="Correo (opcional)"
            type="email"
            error={errors.correo?.message}
            {...register('correo')}
          />
          <Campo
            etiqueta="Contraseña inicial"
            type="password"
            autoComplete="new-password"
            error={errors.contrasena?.message}
            {...register('contrasena')}
          />
          <fieldset className="md:col-span-2">
            <legend className="text-sm font-medium">Roles</legend>
            <div className="mt-2 flex flex-wrap gap-4">
              {roles.data?.map((rol) => (
                <label key={rol.clave} className="flex min-h-11 items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    value={rol.clave}
                    className="size-4"
                    {...register('roles')}
                  />
                  {rol.nombre}
                </label>
              ))}
            </div>
            {errors.roles && <p className="mt-1 text-sm text-peligro">{errors.roles.message}</p>}
          </fieldset>
          <div className="flex flex-col gap-3 md:col-span-2">
            {crear.isError && <Aviso>{mensajeDe(crear.error)}</Aviso>}
            {creado && !crear.isError && <Aviso tipo="exito">Se dio de alta a {creado}.</Aviso>}
            <Boton type="submit" disabled={crear.isPending} className="self-start">
              {crear.isPending ? 'Guardando…' : 'Dar de alta'}
            </Boton>
          </div>
        </form>
      </Tarjeta>
    </div>
  )
}

type Usuario = components['schemas']['UsuarioDto']

/**
 * Si el usuario tiene segundo factor y, si perdió el teléfono, el botón para restablecerlo: en su
 * siguiente inicio de sesión lo vuelve a dar de alta. El propio lo restablece otra persona.
 */
function SegundoFactor({ usuario }: { usuario: Usuario }) {
  const sesion = useSesion()
  const cliente = useQueryClient()
  const [confirmando, setConfirmando] = useState(false)
  const restablecer = useMutation({
    mutationFn: async () =>
      exigir(
        await api.DELETE('/api/v1/usuarios/{id}/segundo-factor', {
          params: { path: { id: usuario.id } },
        }),
      ),
    onSuccess: () => {
      setConfirmando(false)
      void cliente.invalidateQueries({ queryKey: consultaUsuarios })
    },
  })

  if (!usuario.segundoFactor) {
    return (
      <span className="text-tenue">
        {restablecer.isSuccess ? 'Restablecido: lo da de alta al entrar' : 'Sin dar de alta'}
      </span>
    )
  }
  if (sesion?.usuarioId === usuario.id) return <span>Activo</span>
  return (
    <div className="flex flex-wrap items-center gap-2">
      <span>Activo</span>
      {confirmando ? (
        <>
          <Boton
            variante="secundario"
            disabled={restablecer.isPending}
            onClick={() => restablecer.mutate()}
          >
            Sí, restablecer
          </Boton>
          <Boton variante="fantasma" onClick={() => setConfirmando(false)}>
            No
          </Boton>
        </>
      ) : (
        <Boton
          variante="fantasma"
          onClick={() => setConfirmando(true)}
          aria-label={`Restablecer el segundo factor de ${usuario.usuario}`}
        >
          Restablecer
        </Boton>
      )}
      {restablecer.isError && <Aviso>{mensajeDe(restablecer.error)}</Aviso>}
    </div>
  )
}

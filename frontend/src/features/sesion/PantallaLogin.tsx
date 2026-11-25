import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { mensajeDe } from '@/api/errores'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Tarjeta } from '@/components/ui/tarjeta'
import { useIniciarSesion } from '@/sesion/sesion'
import { PieVersion } from './PieVersion'

const esquema = z.object({
  usuario: z.string().trim().min(1, 'Escribe tu usuario'),
  contrasena: z.string().min(1, 'Escribe tu contraseña'),
})

type Datos = z.infer<typeof esquema>

export function PantallaLogin({ alEntrar }: { alEntrar: () => void }) {
  const iniciar = useIniciarSesion()
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<Datos>({ resolver: zodResolver(esquema) })

  const enviar = handleSubmit((datos) => iniciar.mutate(datos, { onSuccess: alEntrar }))

  return (
    <main className="flex min-h-full flex-col items-center justify-center gap-6 p-4">
      <Tarjeta className="w-full max-w-sm">
        <h1 className="text-xl font-semibold text-marca">Sistema para constructora</h1>
        <p className="mt-1 text-sm text-tenue">Inicia sesión con tu usuario de la empresa.</p>
        <form onSubmit={enviar} noValidate className="mt-6 flex flex-col gap-4">
          <Campo
            etiqueta="Usuario"
            autoComplete="username"
            autoCapitalize="none"
            autoFocus
            error={errors.usuario?.message}
            {...register('usuario')}
          />
          <Campo
            etiqueta="Contraseña"
            type="password"
            autoComplete="current-password"
            error={errors.contrasena?.message}
            {...register('contrasena')}
          />
          {iniciar.isError && <Aviso>{mensajeDe(iniciar.error)}</Aviso>}
          <Boton type="submit" disabled={iniciar.isPending}>
            {iniciar.isPending ? 'Entrando…' : 'Entrar'}
          </Boton>
        </form>
      </Tarjeta>
      <PieVersion />
    </main>
  )
}

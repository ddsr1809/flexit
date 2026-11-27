import { zodResolver } from '@hookform/resolvers/zod'
import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { mensajeDe } from '@/api/errores'
import { Aviso } from '@/components/ui/aviso'
import { Boton } from '@/components/ui/boton'
import { Campo } from '@/components/ui/campo'
import { Tarjeta } from '@/components/ui/tarjeta'
import {
  type PasoSegundoFactor,
  useAltaSegundoFactor,
  useConfirmarAltaSegundoFactor,
  useIniciarSesion,
  useVerificarSegundoFactor,
} from '@/sesion/sesion'
import { PieVersion } from './PieVersion'

// El generador de QR solo lo necesita quien da de alta su segundo factor: se carga aparte.
const CodigoQr = lazy(() => import('./CodigoQr').then((m) => ({ default: m.CodigoQr })))

const esquema = z.object({
  usuario: z.string().trim().min(1, 'Escribe tu usuario'),
  contrasena: z.string().min(1, 'Escribe tu contraseña'),
})

type Datos = z.infer<typeof esquema>

const esquemaCodigo = z.object({
  codigo: z
    .string()
    .transform((texto) => texto.replace(/\s/g, ''))
    .pipe(z.string().regex(/^\d{6}$/, 'Escribe los 6 dígitos que muestra la app')),
})

type DatosCodigo = z.input<typeof esquemaCodigo>

export function PantallaLogin({ alEntrar }: { alEntrar: () => void }) {
  const [paso, setPaso] = useState<PasoSegundoFactor>()

  return (
    <main className="flex min-h-full flex-col items-center justify-center gap-6 p-4">
      <Tarjeta className="w-full max-w-sm">
        <h1 className="text-xl font-semibold text-marca">Sistema para constructora</h1>
        {paso === undefined && (
          <PasoContrasena alEntrar={alEntrar} alPedirSegundoFactor={setPaso} />
        )}
        {paso === 'codigo' && (
          <PasoCodigo alEntrar={alEntrar} alVolver={() => setPaso(undefined)} />
        )}
        {paso === 'alta' && <PasoAlta alEntrar={alEntrar} alVolver={() => setPaso(undefined)} />}
      </Tarjeta>
      <PieVersion />
    </main>
  )
}

function PasoContrasena({
  alEntrar,
  alPedirSegundoFactor,
}: {
  alEntrar: () => void
  alPedirSegundoFactor: (paso: PasoSegundoFactor) => void
}) {
  const iniciar = useIniciarSesion()
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<Datos>({ resolver: zodResolver(esquema) })

  const enviar = handleSubmit((datos) =>
    iniciar.mutate(datos, {
      onSuccess: (resultado) =>
        resultado.tipo === 'sesion' ? alEntrar() : alPedirSegundoFactor(resultado.paso),
    }),
  )

  return (
    <>
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
    </>
  )
}

/** Campo de 6 dígitos con el botón para enviarlo. */
function FormularioCodigo({
  enviando,
  error,
  boton,
  alEnviar,
}: {
  enviando: boolean
  error: unknown
  boton: string
  alEnviar: (codigo: string) => void
}) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<DatosCodigo, unknown, z.output<typeof esquemaCodigo>>({
    resolver: zodResolver(esquemaCodigo),
  })
  return (
    <form
      onSubmit={handleSubmit((datos) => alEnviar(datos.codigo))}
      noValidate
      className="flex flex-col gap-4"
    >
      <Campo
        etiqueta="Código de 6 dígitos"
        inputMode="numeric"
        autoComplete="one-time-code"
        autoFocus
        maxLength={7}
        error={errors.codigo?.message}
        {...register('codigo')}
      />
      {error !== null && error !== undefined && <Aviso>{mensajeDe(error)}</Aviso>}
      <Boton type="submit" disabled={enviando}>
        {enviando ? 'Revisando…' : boton}
      </Boton>
    </form>
  )
}

function PasoCodigo({ alEntrar, alVolver }: { alEntrar: () => void; alVolver: () => void }) {
  const verificar = useVerificarSegundoFactor()
  const vencido = verificar.error && esVencido(verificar.error)
  return (
    <div className="mt-4 flex flex-col gap-4">
      <p className="text-sm text-tenue">
        Abre la app de autenticación de tu teléfono y escribe el código de{' '}
        <strong>Constructora</strong>.
      </p>
      <FormularioCodigo
        enviando={verificar.isPending}
        error={verificar.error}
        boton="Entrar"
        alEnviar={(codigo) => verificar.mutate(codigo, { onSuccess: alEntrar })}
      />
      <Boton type="button" variante={vencido ? 'principal' : 'secundario'} onClick={alVolver}>
        Volver a escribir la contraseña
      </Boton>
    </div>
  )
}

function PasoAlta({ alEntrar, alVolver }: { alEntrar: () => void; alVolver: () => void }) {
  const alta = useAltaSegundoFactor()
  const confirmar = useConfirmarAltaSegundoFactor()
  const { mutate: pedirAlta } = alta
  // Una sola vez: cada alta genera un secreto nuevo y deja sin valor el QR anterior.
  const pedida = useRef(false)

  useEffect(() => {
    if (pedida.current) return
    pedida.current = true
    pedirAlta()
  }, [pedirAlta])

  return (
    <div className="mt-4 flex flex-col gap-4">
      <p className="text-sm">
        Tu rol pide un <strong>segundo factor</strong>: además de la contraseña, un código que
        cambia cada 30 segundos en tu teléfono.
      </p>
      <ol className="list-decimal space-y-1 pl-5 text-sm text-tenue">
        <li>Instala Google Authenticator, Microsoft Authenticator o similar.</li>
        <li>En la app, agrega una cuenta y escanea este código.</li>
        <li>Escribe el código de 6 dígitos que aparece.</li>
      </ol>
      {alta.isPending && <p className="text-sm text-tenue">Preparando…</p>}
      {alta.isError && <Aviso>{mensajeDe(alta.error)}</Aviso>}
      {alta.data && (
        <>
          <div className="flex min-h-52 justify-center">
            <Suspense fallback={<p className="text-sm text-tenue">Dibujando el código…</p>}>
              <CodigoQr texto={alta.data.uri} etiqueta="Código QR para la app de autenticación" />
            </Suspense>
          </div>
          <details className="text-sm">
            <summary className="cursor-pointer text-tenue">¿No puedes escanear?</summary>
            <p className="mt-2">Escribe esta clave en la app (tipo: basada en tiempo):</p>
            <p className="mt-1 font-mono break-all select-all" data-testid="clave-segundo-factor">
              {agrupar(alta.data.secreto)}
            </p>
          </details>
          <FormularioCodigo
            enviando={confirmar.isPending}
            error={confirmar.error}
            boton="Activar y entrar"
            alEnviar={(codigo) => confirmar.mutate(codigo, { onSuccess: alEntrar })}
          />
        </>
      )}
      <Boton type="button" variante="secundario" onClick={alVolver}>
        Cancelar
      </Boton>
    </div>
  )
}

function agrupar(clave: string) {
  return clave.replace(/(.{4})/g, '$1 ').trim()
}

function esVencido(error: unknown) {
  return (
    typeof error === 'object' &&
    error !== null &&
    'codigo' in error &&
    ['SEGUNDO_FACTOR_VENCIDO', 'CUENTA_BLOQUEADA'].includes(String(error.codigo))
  )
}

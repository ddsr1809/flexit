import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from '@tanstack/react-router'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { ErrorApi } from '@/api/errores'
import { crearEnrutador } from './rutas'
import './estilos.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Un 401 o 403 no se reintenta: no va a cambiar solo.
      retry: (intentos, error) =>
        !(error instanceof ErrorApi && error.estado < 500) && intentos < 2,
      refetchOnWindowFocus: false,
    },
  },
})

const enrutador = crearEnrutador(queryClient)

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={enrutador} />
    </QueryClientProvider>
  </StrictMode>,
)

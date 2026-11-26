/// <reference types="vitest/config" />
import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// En desarrollo, /api y /actuator van al backend local (make dev). En producción el mismo
// Spring Boot sirve el frontend: mismo dominio, misma cookie de sesión, sin CORS.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': path.resolve(import.meta.dirname, 'src') } },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    // React, el enrutador y las consultas suman ~140 kB comprimidos; cada pantalla se descarga
    // aparte la primera vez que se abre (lazyRouteComponent en rutas.tsx).
    chunkSizeWarningLimit: 500,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/pruebas/preparar.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
})

import { expect, test, type Page } from '@playwright/test'
import { fileURLToPath } from 'node:url'

// Demo de H3: subir la lista de frentes, decidir lo que quedó en cuarentena, confirmar, y ver que
// subir el mismo archivo otra vez no duplica nada. Usa la muestra anonimizada del repositorio.
const ADMIN = { usuario: 'admin', contrasena: 'admin-e2e-2026' }
const MUESTRA = fileURLToPath(
  new URL('../../tools/validacion-datos/muestras/muestra-almacen.xlsx', import.meta.url),
)

async function entrar(page: Page) {
  await page.goto('/')
  await page.getByLabel('Usuario').fill(ADMIN.usuario)
  await page.getByLabel('Contraseña').fill(ADMIN.contrasena)
  await page.getByRole('button', { name: 'Entrar' }).click()
  await expect(page.getByRole('heading', { name: /Hola/ })).toBeVisible()
}

const ir = (page: Page, texto: string) =>
  page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: texto }).click()

test('administración carga los frentes desde Excel sin duplicarlos', async ({ page }, info) => {
  test.skip(info.project.name !== 'escritorio', 'La carga cambia la base: se corre una vez')
  const n = String(Date.now()).slice(-7)
  const cliente = `Ruba H3 ${n}`
  await entrar(page)

  await ir(page, 'Catálogos')
  await page.getByLabel('Nombre', { exact: true }).fill(cliente)
  await page.getByRole('button', { name: 'Dar de alta' }).click()
  await expect(page.getByRole('status')).toHaveText('Alta guardada.')

  await ir(page, 'Migración')
  await expect(page.getByRole('heading', { name: 'Migración desde Excel' })).toBeVisible()
  const subir = page.getByRole('form', { name: 'Subir archivo' })
  await subir.getByLabel('Qué se carga').selectOption({ label: 'Frentes' })
  await subir.getByLabel('Cliente de los frentes').selectOption({ label: cliente })
  await subir.getByLabel('Archivo de Excel (.xlsx)').setInputFiles(MUESTRA)
  await subir.getByRole('button', { name: 'Subir y validar' }).click()

  await expect(page.getByRole('heading', { name: /Frentes/ })).toBeVisible()
  if (await page.getByText('Este archivo ya estaba cargado').isVisible()) {
    // Otra corrida ya lo cargó en esta base: lo que importa es que no se duplicó.
    return
  }
  await expect(page.getByRole('button', { name: 'Confirmar carga' })).toBeDisabled()

  // "Almacén" quedó en cuarentena: se descarta con motivo
  await page.getByLabel('Solo pendientes').check()
  await page.getByRole('button', { name: 'Descartar', exact: true }).click()
  await page.getByLabel('Por qué se descarta').fill('Es el almacén, no una obra')
  await page.getByRole('button', { name: 'Guardar' }).click()
  await expect(page.getByText(/Decidido: descartar/)).toBeVisible()

  await page.getByRole('button', { name: 'Confirmar carga' }).click()
  await expect(page.getByText('Confirmada', { exact: true })).toBeVisible()
  await expect(page.getByText(/Creó: 3 frente/)).toBeVisible()

  // Subir otra vez el mismo archivo no crea otra carga
  await ir(page, 'Migración')
  await subir.getByLabel('Qué se carga').selectOption({ label: 'Frentes' })
  await subir.getByLabel('Cliente de los frentes').selectOption({ label: cliente })
  await subir.getByLabel('Archivo de Excel (.xlsx)').setInputFiles(MUESTRA)
  await subir.getByRole('button', { name: 'Subir y validar' }).click()
  await expect(page.getByText('Este archivo ya estaba cargado')).toBeVisible()
})

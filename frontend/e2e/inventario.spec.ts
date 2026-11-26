import { expect, test, type Page } from '@playwright/test'

// H4 de punta a punta con administración: el almacén de un frente nuevo, sus existencias (vacías)
// y las pantallas de documentos y cierre de mes. Los flujos de captura, autorización y aplicación
// los prueba CriterioH4Test por HTTP real con almacenista, residente y coordinación.
const ADMIN = { usuario: 'admin', contrasena: 'admin-e2e-2026' }

async function entrar(page: Page) {
  await page.goto('/')
  await page.getByLabel('Usuario').fill(ADMIN.usuario)
  await page.getByLabel('Contraseña').fill(ADMIN.contrasena)
  await page.getByRole('button', { name: 'Entrar' }).click()
  await expect(page.getByRole('heading', { name: /Hola/ })).toBeVisible()
}

const ir = (page: Page, texto: string) =>
  page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: texto }).click()

test('administración da de alta el almacén de un frente', async ({ page }) => {
  const n = String(Date.now()).slice(-7)
  await entrar(page)

  await ir(page, 'Catálogos')
  await page.getByLabel('Nombre', { exact: true }).fill(`Cliente H4 ${n}`)
  await page.getByRole('button', { name: 'Dar de alta' }).click()
  await expect(page.getByRole('status')).toHaveText('Alta guardada.')
  await page.getByRole('tab', { name: 'Frentes' }).click()
  await page.getByLabel('Cliente').selectOption({ label: `Cliente H4 ${n}` })
  await page.getByLabel('Clave corta').fill(`F${n}`)
  await page.getByLabel('Nombre', { exact: true }).fill(`Frente H4 ${n}`)
  await page.getByRole('button', { name: 'Dar de alta' }).click()
  await expect(page.getByRole('cell', { name: `F${n}`, exact: true })).toBeVisible()

  await ir(page, 'Existencias')
  const alta = page.getByRole('form', { name: 'Alta de almacén' })
  await alta.getByLabel('Frente').selectOption({ label: `Frente H4 ${n}` })
  await expect(alta.getByLabel('Clave')).toHaveValue(`F${n}`)
  await alta.getByRole('button', { name: 'Dar de alta' }).click()
  await expect(alta.getByText(`Frente H4 ${n}`)).toBeVisible()

  await page
    .getByLabel('Almacén', { exact: true })
    .selectOption({ label: `F${n} · Almacén Frente H4 ${n}` })
  await expect(page.getByText('El almacén está vacío.')).toBeVisible()

  await ir(page, 'Documentos')
  await expect(page.getByRole('heading', { name: 'Documentos del almacén' })).toBeVisible()
  await ir(page, 'Cierre de mes')
  await expect(page.getByRole('heading', { name: 'Cierre de mes' })).toBeVisible()
})

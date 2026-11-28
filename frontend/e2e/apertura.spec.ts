import { expect, test, type Page } from '@playwright/test'

// H8: administración abre un almacén nuevo para el corte y llega a su apertura (conteo a ciegas,
// préstamos y materiales). El flujo completo, con almacén, coordinación y las dos firmas, lo
// prueba CriterioH8Test por HTTP real.
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

test('administración abre la apertura de un almacén nuevo', async ({ page }) => {
  const n = String(Date.now()).slice(-7)
  await entrar(page)

  await ir(page, 'Catálogos')
  await page.getByLabel('Nombre', { exact: true }).fill(`Cliente H8 ${n}`)
  await page.getByRole('button', { name: 'Dar de alta' }).click()
  await expect(page.getByRole('status')).toHaveText('Alta guardada.')
  await page.getByRole('tab', { name: 'Frentes' }).click()
  await page.getByLabel('Cliente').selectOption({ label: `Cliente H8 ${n}` })
  await page.getByLabel('Clave corta').fill(`P${n}`)
  await page.getByLabel('Nombre', { exact: true }).fill(`Frente H8 ${n}`)
  await page.getByRole('button', { name: 'Dar de alta' }).click()
  await expect(page.getByRole('cell', { name: `P${n}`, exact: true })).toBeVisible()

  await ir(page, 'Existencias')
  const alta = page.getByRole('form', { name: 'Alta de almacén' })
  await alta.getByLabel('Frente').selectOption({ label: `Frente H8 ${n}` })
  await alta.getByRole('button', { name: 'Dar de alta' }).click()
  await expect(alta.getByText(`Frente H8 ${n}`)).toBeVisible()

  await ir(page, 'Apertura')
  await expect(page.getByRole('heading', { name: 'Apertura de almacén' })).toBeVisible()
  const abrir = page.getByRole('form', { name: 'Abrir almacén' })
  await abrir.getByLabel('Almacén').selectOption({ label: `P${n} · Almacén Frente H8 ${n}` })
  await abrir.getByRole('button', { name: 'Abrir' }).click()

  await expect(page.getByRole('heading', { name: /Apertura APE-/ })).toBeVisible()
  await expect(page.getByText('Contando')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Préstamos abiertos al corte' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Materiales' })).toBeVisible()
  await expect(page.getByRole('link', { name: /Conteo CON-/ })).toBeVisible()

  await ir(page, 'Apertura')
  await expect(page.getByRole('cell', { name: `P${n}` })).toBeVisible()
})

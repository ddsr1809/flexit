import { expect, test, type Page } from '@playwright/test'

// H6 con administración: los reportes de la junta y su descarga en Excel, y la lista de pedidos.
// El ciclo requisición → pedido → recepción → vale con residente, coordinación, compras y almacén
// lo prueba CriterioH6Test por HTTP real.
const ADMIN = { usuario: 'admin', contrasena: 'admin-e2e-2026' }
const XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'

async function entrar(page: Page) {
  await page.goto('/')
  await page.getByLabel('Usuario').fill(ADMIN.usuario)
  await page.getByLabel('Contraseña').fill(ADMIN.contrasena)
  await page.getByRole('button', { name: 'Entrar' }).click()
  await expect(page.getByRole('heading', { name: /Hola/ })).toBeVisible()
}

const ir = (page: Page, texto: string) =>
  page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: texto }).click()

test('los reportes se ven y se descargan en Excel', async ({ page }) => {
  await entrar(page)
  await ir(page, 'Reportes')
  await expect(page.getByRole('heading', { name: 'Reportes' })).toBeVisible()

  const excel = page.getByRole('link', { name: 'Descargar Excel' })
  const ruta = await excel.getAttribute('href')
  expect(ruta).toContain('formato=xlsx')
  const descarga = await page.request.get(ruta!)
  expect(descarga.status()).toBe(200)
  expect(descarga.headers()['content-type']).toContain(XLSX)

  await page.getByRole('button', { name: 'Costo agrupado' }).click()
  await page.getByLabel('Agrupar por').selectOption({ label: 'Mes' })
  await expect(page.getByRole('columnheader', { name: 'Mes' })).toBeVisible()

  await page.getByRole('button', { name: 'Valor del inventario' }).click()
  await expect(page.getByText(/Valor al costo promedio/)).toBeVisible()

  await page.getByRole('button', { name: 'Avisos de presupuesto' }).click()
  await expect(page.getByRole('columnheader', { name: 'Restante' })).toBeVisible()
})

test('administración ve los pedidos pero no arma pedidos', async ({ page }) => {
  await entrar(page)
  await ir(page, 'Pedidos')
  await expect(page.getByRole('heading', { name: 'Pedidos a proveedor' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Falta comprar' })).toHaveCount(0)
  await expect(page.getByRole('navigation', { name: 'Principal' })).not.toContainText(
    'Requisiciones',
  )
})

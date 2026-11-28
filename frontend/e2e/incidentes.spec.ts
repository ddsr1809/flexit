import { expect, test, type Page } from '@playwright/test'

// H9: cualquiera reporta un problema desde el marco y administración lo resuelve desde Incidentes.
// Los permisos (quién ve qué, quién reabre) y el reporte de estabilización los prueba
// CriterioH9Test por HTTP real.
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

test('se reporta un problema y administración lo resuelve', async ({ page }) => {
  const n = String(Date.now()).slice(-7)
  await entrar(page)

  await page.getByRole('button', { name: 'Reportar un problema' }).click()
  const dialogo = page.getByRole('dialog', { name: 'Reportar un problema' })
  await dialogo.getByLabel('¿Qué tan grave es?').selectOption('critica')
  await dialogo.getByLabel('En pocas palabras').fill(`Prueba e2e ${n}`)
  await dialogo.getByLabel('Qué pasó y qué estabas haciendo').fill('No deja aplicar el vale')
  await dialogo.getByRole('button', { name: 'Enviar' }).click()
  await expect(dialogo.getByText(/Quedó como INC-/)).toBeVisible()
  await dialogo.getByRole('button', { name: 'Listo' }).click()

  await ir(page, 'Incidentes')
  await expect(page.getByRole('heading', { name: 'Incidentes' })).toBeVisible()
  const fila = page.getByRole('row').filter({ hasText: `Prueba e2e ${n}` })
  await fila.getByRole('button', { name: /INC-/ }).click()
  await page.getByLabel('Nota, resolución o motivo').fill(`Corregido en la prueba ${n}`)
  await page.getByRole('button', { name: 'Resolver' }).click()
  await expect(page.getByRole('button', { name: 'Reabrir' })).toBeVisible()
  await expect(page.getByText(`Corregido en la prueba ${n}`).first()).toBeVisible()

  await ir(page, 'Estabilización')
  await expect(page.getByRole('heading', { name: 'Estabilización del piloto' })).toBeVisible()
})

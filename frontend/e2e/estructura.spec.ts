import { expect, test, type Page } from '@playwright/test'

// Demo de H2 de punta a punta: administración da de alta un contrato con 3 lotes y 5 materiales
// a mano, y encuentra un material por nombre parcial. Corre contra la app completa (CI).
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

test('administración captura un contrato con 3 lotes y 5 materiales', async ({ page }) => {
  const n = String(Date.now()).slice(-7)
  const cliente = `Ruba ${n}`
  const frente = `C${n}`
  const prototipo = `P-${n}`
  const codigo = `60-E2E-${n}-C002-2026`

  await entrar(page)

  // Catálogos: cliente, frente y prototipo
  await ir(page, 'Catálogos')
  await page.getByLabel('Nombre', { exact: true }).fill(cliente)
  await page.getByRole('button', { name: 'Dar de alta' }).click()
  await expect(page.getByRole('status')).toHaveText('Alta guardada.')

  await page.getByRole('tab', { name: 'Frentes' }).click()
  await page.getByLabel('Cliente').selectOption({ label: cliente })
  await page.getByLabel('Clave corta').fill(frente)
  await page.getByLabel('Nombre', { exact: true }).fill(`Frente ${n}`)
  await page.getByRole('button', { name: 'Dar de alta' }).click()
  await expect(page.getByRole('cell', { name: frente.toUpperCase(), exact: true })).toBeVisible()

  await page.getByRole('tab', { name: 'Prototipos' }).click()
  await page.getByLabel('Clave', { exact: true }).fill(prototipo)
  await page.getByLabel('Nombre', { exact: true }).fill(`Prototipo ${n}`)
  await page.getByRole('button', { name: 'Dar de alta' }).click()
  await expect(page.getByRole('cell', { name: prototipo, exact: true })).toBeVisible()

  // Contrato: el código se escribe con "/" y se guarda con "-"
  await ir(page, 'Contratos')
  await page.getByLabel('Cliente').selectOption({ label: cliente })
  await page.getByLabel('Frente').selectOption({ label: `Frente ${n}` })
  await page
    .getByLabel('Código del contrato')
    .fill(codigo.toLowerCase().replace('-C002-', '/C002/'))
  await page.getByLabel('No. de obra (IMSS)').fill('C1269209')
  await page.getByRole('button', { name: 'Dar de alta' }).click()
  await expect(page.getByRole('heading', { name: codigo })).toBeVisible()

  // Tres lotes de una vez
  const agregar = page.getByRole('form', { name: 'Agregar unidades' })
  await agregar.getByLabel('Manzana').fill('20')
  await agregar.getByLabel('Lotes').fill('29-31')
  await expect(page.getByText('Se agregarán 3 lotes')).toBeVisible()
  await agregar
    .getByLabel('Prototipo', { exact: true })
    .selectOption({ label: `${prototipo} · Prototipo ${n}` })
  await agregar.getByRole('button', { name: 'Agregar', exact: true }).click()
  await expect(page.getByText('Se agregaron 3 unidades.')).toBeVisible()
  for (const lote of ['M20-L29', 'M20-L30', 'M20-L31']) {
    await expect(page.getByRole('cell', { name: lote, exact: true })).toBeVisible()
  }

  // Cinco materiales y búsqueda por nombre parcial
  await ir(page, 'Materiales')
  const materiales = [
    `Cemento gris ${n}`,
    `Adeblock ${n}`,
    `Abrazadera omega ${n}`,
    `Cable THW ${n}`,
    `Lavamanos ${n}`,
  ]
  for (const nombre of materiales) {
    await page.getByLabel('Nombre', { exact: true }).fill(nombre)
    await page.getByLabel('Unidad de control').selectOption({ label: 'PZA · Pieza' })
    await page.getByRole('button', { name: 'Dar de alta' }).click()
    await expect(page.getByRole('status')).toContainText(nombre)
  }
  await page.getByLabel('Buscar').fill(`cement GRIS ${n}`)
  await expect(page.getByRole('cell', { name: `Cemento gris ${n}`, exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: `Lavamanos ${n}` })).toHaveCount(0)
})

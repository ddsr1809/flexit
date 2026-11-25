import { expect, test } from '@playwright/test'

// Credenciales del administrador inicial de compose.e2e.yml (solo existen en CI).
const ADMIN = { usuario: 'admin', contrasena: 'admin-e2e-2026' }

async function entrar(page: import('@playwright/test').Page, usuario: string, contrasena: string) {
  await page.goto('/')
  await expect(page).toHaveURL(/\/login$/)
  await page.getByLabel('Usuario').fill(usuario)
  await page.getByLabel('Contraseña').fill(contrasena)
  await page.getByRole('button', { name: 'Entrar' }).click()
}

test('una contraseña equivocada muestra el motivo y no entra', async ({ page }) => {
  await entrar(page, ADMIN.usuario, 'no-es-la-contrasena')
  await expect(page.getByRole('alert')).toHaveText('Usuario o contraseña incorrectos.')
  await expect(page).toHaveURL(/\/login$/)
})

test('administración da de alta un almacenista y este entra con otro menú', async ({
  page,
}, info) => {
  const almacenista = `almacen.${info.project.name}.${Date.now()}`

  await entrar(page, ADMIN.usuario, ADMIN.contrasena)
  await expect(page.getByRole('heading', { name: /Hola/ })).toBeVisible()
  await page.getByRole('navigation').getByRole('link', { name: 'Usuarios' }).click()

  await page.getByLabel('Usuario').fill(almacenista)
  await page.getByLabel('Nombre completo').fill('Almacenista de prueba')
  await page.getByLabel('Contraseña inicial').fill('almacen-e2e-2026')
  await page.getByLabel('Almacenista').check()
  await page.getByRole('button', { name: 'Dar de alta' }).click()
  await expect(page.getByRole('status')).toContainText(almacenista)
  await expect(page.getByRole('cell', { name: almacenista })).toBeVisible()

  await page
    .getByRole('button', { name: /Cerrar sesión|Salir/ })
    .first()
    .click()
  await expect(page).toHaveURL(/\/login$/)

  await entrar(page, almacenista, 'almacen-e2e-2026')
  const menu = page.getByRole('navigation', { name: 'Principal' })
  await expect(menu.getByText('Existencias')).toBeVisible()
  await expect(menu.getByRole('link', { name: 'Usuarios' })).toHaveCount(0)

  // Aunque escriba la ruta a mano, no entra a Usuarios.
  await page.goto('/usuarios')
  await expect(page).toHaveURL(/\/$/)
})

test('la versión desplegada se ve sin iniciar sesión', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByText(/Versión .+ · /)).toBeVisible()
})

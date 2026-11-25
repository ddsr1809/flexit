import type { Sesion } from '@/sesion/sesion'
import { menuPara } from './menu'

const sesion = (permisos: string[]): Sesion => ({
  usuarioId: 1,
  usuario: 'ana',
  nombre: 'Ana',
  empresaId: 1,
  empresa: 'RM Alemán',
  roles: [],
  permisos,
})

describe('menú por permisos', () => {
  it('el almacenista no ve Usuarios', () => {
    const textos = menuPara(sesion(['movimiento:capturar', 'existencias:ver'])).map((e) => e.texto)
    expect(textos).toContain('Existencias')
    expect(textos).not.toContain('Usuarios')
  })

  it('administración ve Usuarios', () => {
    const textos = menuPara(sesion(['usuarios:administrar'])).map((e) => e.texto)
    expect(textos).toContain('Usuarios')
  })
})

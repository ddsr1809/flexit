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

  it('el residente ve Contratos y Materiales, pero no Paquetes ni Catálogos', () => {
    const textos = menuPara(sesion(['requisicion:crear', 'costo:ver-propio'])).map((e) => e.texto)
    expect(textos).toEqual(expect.arrayContaining(['Contratos', 'Materiales']))
    expect(textos).not.toContain('Paquetes')
    expect(textos).not.toContain('Catálogos')
  })

  it('compras ve Proveedores', () => {
    const textos = menuPara(sesion(['catalogo:editar', 'estructura:ver-todo'])).map((e) => e.texto)
    expect(textos).toEqual(expect.arrayContaining(['Proveedores', 'Catálogos', 'Paquetes']))
  })

  it('el almacén ve existencias, documentos, préstamos y conteo; no autorizaciones ni cierre', () => {
    const textos = menuPara(sesion(['movimiento:capturar', 'existencias:ver'])).map((e) => e.texto)
    expect(textos).toEqual(
      expect.arrayContaining(['Existencias', 'Documentos', 'Préstamos', 'Conteo físico']),
    )
    expect(textos).not.toContain('Autorizaciones')
    expect(textos).not.toContain('Cierre de mes')
  })

  it('el residente y coordinación ven la bandeja de autorizaciones', () => {
    const residente = menuPara(sesion(['vale:autorizar', 'existencias:ver'])).map((e) => e.texto)
    expect(residente).toContain('Autorizaciones')
    const coordinacion = menuPara(sesion(['documento:autorizar'])).map((e) => e.texto)
    expect(coordinacion).toContain('Autorizaciones')
  })

  it('administración ve Usuarios', () => {
    const textos = menuPara(sesion(['usuarios:administrar'])).map((e) => e.texto)
    expect(textos).toContain('Usuarios')
  })

  it('la migración y las personas son solo de administración', () => {
    const admin = menuPara(sesion(['sistema:administrar', 'personas:administrar'])).map(
      (e) => e.texto,
    )
    expect(admin).toEqual(expect.arrayContaining(['Migración', 'Personas']))
    const compras = menuPara(sesion(['catalogo:editar', 'estructura:ver-todo'])).map((e) => e.texto)
    expect(compras).not.toContain('Migración')
    expect(compras).not.toContain('Personas')
  })
})

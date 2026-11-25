/** Formatos de México: moneda MXN, números con separador de miles y fechas dd/mm/aaaa. */
const moneda = new Intl.NumberFormat('es-MX', { style: 'currency', currency: 'MXN' })
const numero = new Intl.NumberFormat('es-MX', { maximumFractionDigits: 4 })
const fecha = new Intl.DateTimeFormat('es-MX', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  timeZone: 'America/Mexico_City',
})

export const formatearMoneda = (valor: number) => moneda.format(valor)
export const formatearNumero = (valor: number) => numero.format(valor)
export const formatearFecha = (valor: Date | string) =>
  fecha.format(typeof valor === 'string' ? new Date(valor) : valor)

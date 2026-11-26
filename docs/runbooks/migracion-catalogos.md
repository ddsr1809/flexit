# Migración de catálogos (H3 · issue 20)

Carga de los datos maestros desde los tres Excel en preproducción, y validación con coordinación.
Lo hace administración desde **Migración** (menú lateral); no hace falta un programador.

## Antes

- Respaldo de la base de preproducción (`docs/runbooks/revertir.md`).
- Dar de alta el cliente **Ruba** en *Catálogos › Clientes* (la carga de frentes lo pide).
- Tener a mano: *Control de almacén*, *SUA RM ALEMAN* (el del mes) y *Destajo Nomina RM Aleman*.
  Subir las copias H0 (estabilizadas) o las originales: se leen solo las celdas capturadas.

## Orden

| # | Qué se carga | Archivo | Qué decidir en la cuarentena |
| --- | --- | --- | --- |
| 1 | Frentes | Almacén | "Almacén" no es una obra: descartar |
| 2 | Contratos | SUA | Confirmar el frente de "CORCELES FRACCIONAMIENTO", "QUINTA GRANADA" y "PALERMO RESIDENCIAL" (una vez; queda alias) |
| 3 | Lotes y prototipos | Destajo | Confirmar el frente de cada hoja de pagos; **elegir el contrato de cada lote por manzana** (coordinación); corregir "M716" |
| 4 | Paquetes y precios de destajo | Destajo | Nombre de los precios sin concepto o descartarlos |
| 5 | Materiales | Almacén | Unidades que no se reconocen; ligar o dar de alta los materiales que solo aparecen en movimientos (Lija 221 → ¿Lija 220?); precio de los que no tienen entradas |
| 6 | Proveedores | Almacén | Posibles duplicados |
| 7 | Personas del SUA | SUA | Posibles duplicados (mismo nombre con otra letra u otro orden) |
| 8 | Trabajadores del destajo | Destajo | Ligar cada apodo a su persona del SUA, darlo de alta para revisión o descartarlo |
| 9 | Solicitantes del almacén | Almacén | Igual que el destajo |

En cada carga: filtrar por regla, resolver en grupo, revisar que la **diferencia** de la
conciliación sea 0 en cada hoja y **Confirmar carga**.

Lo que se esperaba con los archivos de noviembre (simulación con los Excel reales):

| Carga | Renglones | En cuarentena al subir | Se crean |
| --- | --- | --- | --- |
| Frentes | 5 | 1 | 3 o 4 |
| Contratos | 10 | 10 (3 frentes por confirmar) | 10 |
| Lotes | 249 | 249 (frente) → 248 sin contrato + 1 ilegible | 248 lotes, 6 prototipos |
| Paquetes | 877 | 5 precios sin concepto | ≈390 paquetes, ≈550 precios |
| Materiales | 355 | 56 que solo aparecen en movimientos | 299 + los que se den de alta |
| Proveedores | 29 | 0 | 29 |
| Personas del SUA | 131 | 12 posibles duplicados | ≈120 |
| Trabajadores del destajo | 234 | 228 apodos | los que se den de alta |
| Solicitantes del almacén | 63 | 44 apodos | los que se den de alta |

## Criterio de aceptación (H3)

- [ ] 10 de 10 contratos activos, y todos sus lotes con contrato y prototipo.
- [ ] 299 de 299 materiales con unidad de control. Precio: 143 lo traen de Entradas; los 156 sin
  entradas quedan sin precio de referencia salvo que se capture en la cuarentena (decisión
  pendiente: ¿se capturan o entran sin precio hasta el conteo de H8?).
- [ ] Cero renglones en cuarentena en las cargas confirmadas.
- [ ] Cada persona del SUA y del destajo ligada a una persona o marcada "para revisar"
  (*Personas › Solo para revisar*).

## Demo

1. Subir otra vez el Excel de almacén como *Frentes*: el sistema dice que ya estaba cargado y no
   crea nada.
2. Revertir la carga de *Proveedores*: los proveedores desaparecen completos. Volver a subir el
   archivo y confirmar.

## Si algo sale mal

- **"Otros registros ya usan lo que creó esta carga"**: revertir primero las cargas posteriores
  (lotes antes que contratos, contratos antes que frentes).
- **"La hoja … no tiene el formato esperado"**: cambiaron encabezados o nombres de hoja. Corregir el
  Excel o avisar para ajustar el perfil del importador.
- **Un alias quedó mal**: *Migración › Ver alias*, cambiarlo u olvidarlo, y *Revalidar* la carga.

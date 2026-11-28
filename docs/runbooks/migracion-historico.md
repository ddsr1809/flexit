# Migración del histórico (H5 · issues 46 a 51)

Carga de todo lo capturado en los Excel desde junio de 2026 al esquema `historico`, desde
**Migración**. Lo hace administración; las decisiones de lote las toma coordinación. Requiere que
las cargas de catálogos de H3 estén confirmadas (`migracion-catalogos.md`).

## Antes

- Respaldo de la base de preproducción (`docs/runbooks/revertir.md`).
- Confirmadas las 9 cargas de H3. Si faltan lotes o materiales, darlos de alta y revalidar.
- Opcional, para que salgan valuadas más salidas: capturar el precio de referencia de los
  materiales que no tienen entradas (*Catálogos › Materiales*). Sin precio entran sin costo.

## Orden

| # | Qué se carga | Archivo | Parámetro | Qué decidir en la cuarentena |
| --- | --- | --- | --- | --- |
| 10 | Histórico: entradas | Almacén | — | Fecha de las recepciones sin fecha; valor de las fórmulas con referencias |
| 11 | Histórico: salidas | Almacén | — | **Lote de cada "Mza / Lote" que no existe** (una decisión vale para todos los renglones que lo escriben igual): elegir el lote, escribir uno o `SIN ASIGNAR`; varias manzanas en una celda; valor de las fórmulas con referencias |
| 12 | Histórico: Semanal y faltantes | Almacén | — | Materiales y frentes que no se reconocen |
| 13 | Histórico: préstamos | Almacén | — | Nada (las herramientas que no son material quedan con su nombre) |
| 14 | Histórico: foto de Existencias (H8) | Almacén | — | Inventario inicial con fórmula que depende de otra celda (escribir el valor); materiales que no se reconocen |
| 15 | Histórico: SUA del mes | SUA del mes | Mes (`2026-10`) | Contratos que no existen; personas sin ligar |
| 16 | Histórico: pagos de destajo | Destajo | Año de las semanas | Lote de la columna `M716` (una decisión vale para toda la columna); números escritos en la columna Trabajador; montos ilegibles |

En cada carga, además de la cuarentena:

1. **Advertencias que conviene ligar antes de confirmar** (no bloquean): filtrar por
   `PAQUETE_SIN_LIGAR` y ligar cada paquetería del almacén a su paquete (queda alias); por
   `SOLICITANTE_SIN_LIGAR` para los solicitantes. Lo que no se ligue entra con el texto original.
2. Revisar **Totales contra el Excel**: cada diferencia tiene que ser 0. Lo que no se carga
   aparece como cuarentena o descartado.
3. **Confirmar carga.**

Para cargar un archivo más nuevo del mismo tipo, primero se revierte la carga confirmada (el
sistema no deja confirmar dos veces el mismo histórico). Para revertir entradas, primero se
revierten las salidas (se valuaron con ellas).

## Lo que se espera con los archivos de noviembre

Simulación con los tres Excel reales, por los servicios reales contra PostgreSQL:

| Carga | Renglones | Al subir en cuarentena | Decisiones | Se escriben |
| --- | --- | --- | --- | --- |
| Entradas | 596 | 11 (7 sin fecha, 2 sin cantidad, 2 fórmulas con referencias) | 11 | 585 entradas; 77 con precio estimado, 95 sin valuar |
| Salidas | 4,153 | 318 (294 lote inexistente, 11 sin manzana, 7 fórmulas, 3 sin cantidad, 2 varias manzanas, 1 sin frente) | **81 de lote** + 12 | 4,237 salidas (11 renglones repartidos); 159 al frente sin lote |
| Semanal y faltantes | 1,293 | 10 | 10 | 1,283 renglones |
| Préstamos | 25 | 0 | 0 | 25 (17 siguen abiertos) |
| SUA de octubre | 332 | 0 | 0 | 172 altas, 1,368 marcas de asistencia (6 dobles), 73 asignaciones |
| Foto de Existencias (H8) | 299 | 0 (9 inventarios iniciales con fórmula simple se evalúan; 2 fechas ilegibles no se guardan) | 0 | 299 materiales; inventario inicial 13,194.2 en total |
| Destajo | 4,422 celdas | 42 (26 de la columna M716, 10 números en Trabajador, 4 montos ilegibles…) | ≈18 | 4,957 pagos, $8.9 millones; 777 con "sobre importe" |

Advertencias de salidas: unas 30 paqueterías distintas sin paquete y 1,092 renglones sin costo
(materiales que no entraron por el Excel ni tienen precio de referencia).

## Criterio de aceptación (H5)

- [ ] Cada renglón de cada hoja está como válido, corregido (con la regla) o en cuarentena (con el
  motivo): la columna *Diferencia* de la conciliación por hoja es 0.
- [ ] Cantidades por material y mes de Entradas y Salidas: diferencia 0 en *Totales contra el
  Excel*.
- [ ] Destajo pagado por hoja y lote: diferencia 0 (al peso).
- [ ] Cero renglones de histórico sin material, sin frente (salvo los del propio almacén) o sin
  persona en SUA y destajo.
- [ ] Muestra de 30 renglones al azar verificada a mano contra el Excel por administración (la
  columna *Origen* de cada renglón dice hoja y fila).
- [ ] Diferencias de costo por lote contra las hojas por frente revisadas y firmadas por
  coordinación.

## Demo

Abrir el lote M16-L34 de Palermo desde su contrato (*Contratos › el contrato del lote › M16-L34*):
en la simulación salen 45 vales con costo, 32 pagos de destajo y las altas del IMSS de su
contrato. Con el usuario de coordinación, los importes se ven
pero no los nombres ni las altas.

## Si algo sale mal

- **"Ya está confirmada la carga N"**: se subió otra versión del mismo histórico. Revertir la carga
  anterior y confirmar la nueva.
- **"El histórico ya usa esta carga"**: revertir primero las cargas del histórico que la usan.
- **Muchas salidas a un lote que no existe**: falta el lote en la estructura. Darlo de alta (o
  cargar la plantilla "Lotes") y *Revalidar*.
- **Una salida quedó al lote equivocado**: revertir la carga, corregir la decisión y volver a
  subir. El histórico no se edita renglón por renglón.

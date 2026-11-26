# ADR-008 · Lector de .xlsx propio y carga en la misma petición

**Contexto.** El plan por hitos preveía la API de eventos de Apache POI, el archivo en S3 y el
proceso en segundo plano con avance por eventos del servidor. La migración tiene que leer el valor
exacto de cada celda capturada, saber si una celda es fórmula para no leerla nunca, y no confiar en
los valores guardados (el Excel de almacén se guardó sin terminar de calcular). El
`XSSFSheetXMLHandler` de POI entrega el texto ya formateado y no dice si la celda era fórmula.
S3 llega hasta H4.

**Decisión.**

- `importacion.dominio.xlsx.LibroXlsx` lee el .xlsx con StAX, en streaming y sin dependencias:
  valores exactos (`1072615.5299999984`, no `1,072,615.53`), fechas en ISO por el formato de la
  celda, fórmulas marcadas (también las compartidas y de matriz) y errores (`#REF!`) como errores.
  Defensas: sin DTD ni entidades externas, máximo de entradas, de tamaño descomprimido y de
  proporción de compresión. La hoja CORCELES (12 MB descomprimida) se lee en 0.3 s con 128 MB.
- El archivo se guarda en `migracion.archivo` (bytea) con su sha256, hasta que exista S3.
- Subir, validar, decidir, confirmar y revertir corren en la misma petición y en una transacción.
  La carga más grande de H3 (paquetes, 877 renglones) valida en menos de un segundo. El proceso en
  segundo plano con avance entra en H5, cuando lleguen las 4,153 salidas y los 4,100 pagos.
- Revalidar no vuelve a abrir el archivo: usa el renglón crudo guardado en `migracion.fila`.

**Consecuencias.** Sin Apache POI en el classpath (ni sus CVE). El lector no evalúa fórmulas; la
regla de H5 para pagos de destajo capturados como `=2500+2500` usará el texto de la fórmula que el
lector ya entrega. Si un archivo trae algo que el lector no entiende, la carga falla al subir con
el nombre de la hoja, nunca a medias.

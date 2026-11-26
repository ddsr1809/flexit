# ADR-011 · Histórico del Excel aparte del kardex

**Contexto.** H5 tiene que dejar en el sistema todo lo capturado en los Excel desde junio de 2026
(entradas, salidas, Semanal, faltantes, préstamos, SUA y destajo) sin heredar sus errores: el
Excel tiene existencias negativas, salidas antes de su entrada, salidas sin lote, fechas con el
año mal capturado y cantidades escritas como fórmula (`=1/3`, un bote para tres lotes). El
inventario vivo (H4) arranca del conteo físico de H8.

**Decisión.**

- **Esquema `historico` (V008) y módulo `historico`.** Una tabla por hoja, ligada a catálogos
  reales con FK compuestas. Cada renglón sabe de qué carga, hoja y fila salió, y si entró
  *válido* o *corregido*; lo que queda en cuarentena o se descarta no llega aquí. La app solo
  inserta y un trigger impide `UPDATE` incluso al dueño; solo `historico.revertir_carga` borra,
  y solo lo de una carga de la empresa en curso. El módulo `importacion` escribe por
  `ImportacionHistorico` (regla 14).
- **El histórico no entra al kardex.** Los reportes suman las dos fuentes por
  `reportes.v_costo_unidad` (salidas entregadas del histórico + `inventario.cargo`) y
  `reportes.v_destajo_unidad`. Las vistas llevan `security_invoker`, para que apliquen el RLS de
  quien consulta y no el del dueño.
- **Valuación de las salidas: ponderado acumulado.** El costo de una salida es el promedio
  ponderado, sin IVA y neto de descuento, de *todas* las entradas del material hasta la fecha de la
  salida. No se restan salidas (el Excel tiene existencias negativas), así que el resultado no
  depende del orden de captura. Antes de la primera entrada se usa el ponderado de todas sus
  entradas, y si el material nunca entró por el Excel, su precio de referencia; los dos casos van
  marcados como "costo estimado". Una entrada sin precio se valora con el ponderado de las
  entradas del material en las 4 semanas cercanas (o de todas, o el precio de referencia),
  marcada "precio estimado".
- **"Sin asignar" es `unidad_obra_id` nulo.** Una salida sin lote suma a su frente (y a su
  contrato si toda la manzana es de uno) pero a ningún lote. No se crean unidades ficticias en
  `estructura`. Una salida para el propio almacén queda sin frente.
- **Fórmulas que son captura.** Solo se evalúa aritmética de números escritos (`=1/3`,
  `=206.95/30`, `=3100+7232`); en el destajo cada sumando es un pago parcial. Cualquier fórmula
  con referencias o funciones va a la cuarentena, donde una persona escribe el valor; ese valor es
  el que se concilia.
- **Decisiones que valen para un grupo.** Elegir el lote de "Mza 17 / Lote 25" una vez lo
  resuelve en todos los renglones que lo escriben igual (en el destajo, en toda la columna del
  lote). Los nombres (material, persona, paquete, contrato) se resuelven con alias, como en H3.
- **Paquetería y personas del almacén no bloquean.** La paquetería que no corresponde a un paquete
  del catálogo y el solicitante sin alias entran como advertencia, con el texto original
  guardado; se pueden ligar en grupo antes de confirmar. Los trabajadores del destajo y las
  personas del SUA sí son obligatorios.
- **Una carga vigente por tipo.** El histórico de almacén y de destajo se carga una vez; un
  archivo más nuevo se carga revirtiendo antes la carga confirmada (si no, cada renglón quedaría
  dos veces). El SUA, una vez por mes. Una carga que el histórico usa (entradas para valuar
  salidas, materiales, paquetes) no se revierte mientras siga confirmado lo que depende de ella.
- **Conciliación por totales.** Además del conteo por hoja, cada carga del histórico concilia
  totales del Excel contra lo que se escribe (cantidad por material y mes, por frente y lote,
  pagado por hoja y lote y por trabajador, altas por contrato, marcas por persona). Lo cargado se
  calcula con el mismo código que confirma, así que repartir lotes o separar pagos parciales no
  puede perder ni un centavo sin que se vea.
- **La carga sigue en la misma petición** (ADR-008). Con los Excel reales: Salidas (4,153
  renglones) valida en 1.8 s y confirma en 1.9 s; destajo (4,422 celdas) en 0.6 s y 1 s. El
  segundo plano no hace falta todavía.

**Consecuencias.**

- El costo de un lote se ve completo desde junio: `v_costo_unidad` suma las dos fuentes. Lo que
  no tiene costo (materiales sin entradas ni precio de referencia) entra sin costo y se ve en la
  conciliación; capturar el precio de referencia y volver a cargar lo valúa.
- El histórico es de solo lectura. Corregir algo es revertir la carga completa y volver a subir.
  En H8 se decide desde cuándo ya no se revierte (después del corte).
- Las salidas a lotes que no están en el catálogo (unos 80 grupos con los Excel de noviembre)
  dependen de que la estructura de H3 esté completa; si falta un lote, se da de alta y se
  revalida la carga.

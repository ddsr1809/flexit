# Cierre de mes del inventario

Lo hace administración el primer día hábil del mes siguiente, en *Cierre de mes*. Un mes cerrado no
acepta movimientos con fecha en él y no se reabre desde la aplicación. Decisiones en ADR-015.

## Antes (los últimos días del mes)

- Coordinación autoriza o rechaza lo que espera autorización (*Autorizaciones*).
- El almacén aplica o cancela lo que capturó: un borrador olvidado detiene el cierre.
- Si hay un conteo físico abierto, se cierra o se cancela. Una apertura de un frente nuevo se
  firma o se cancela antes de cerrar el mes de su fecha.
- Compras pide las facturas de las recepciones sin folio: si el precio cambia, el ajuste de costo
  ya entra con fecha del mes siguiente.

## El cierre

1. Abrir *Cierre de mes*. La pantalla revisa el siguiente mes por cerrar:
   - **Lo que impide cerrar**: el mes no ha terminado, un mes anterior está abierto, documentos sin
     aplicar ni cancelar, conteos o aperturas abiertos. Cada punto lista los folios.
   - **Avisos (no impiden cerrar)**: préstamos que debían regresar, recepciones sin factura y
     requisiciones autorizadas sin surtir o sin autorizar. Se revisan con quien corresponde; si
     está bien así, se cierra igual.
2. Sin bloqueos aparece *Cerrar {mes}*. Se cierra.
3. En la tabla de meses cerrados, descargar el **acta** (*Excel*). En la hoja *Valor por almacén*,
   la columna *Diferencia* debe ser 0 en todos los almacenes; si no, se reporta un incidente
   crítico.
4. Guardar el acta con las del mes (fuera del repositorio) y mandarla a dirección y al contador.

## Si algo no cuadra

- Un documento con fecha equivocada que ya está aplicado no se mueve de mes: se cancela y se vuelve
  a capturar con la fecha correcta, antes del cierre.
- Después del cierre, cualquier corrección va con fecha del mes en curso.
- Reabrir un mes no se hace desde la aplicación. Si fuera indispensable, se decide con dirección y
  se documenta como incidente; la app no tiene permiso para borrar `inventario.periodo`.

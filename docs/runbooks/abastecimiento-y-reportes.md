# Abastecimiento y reportes (H6)

Con esto se deja de usar la hoja Semanal y las hojas por frente. Requiere H4 operando (almacenes,
residentes asignados) y, para los reportes con histórico, las cargas de H5 confirmadas.

## Antes de empezar (una sola vez)

1. **Presupuesto de material** (coordinación o dirección): *Reportes › Por contrato › el contrato
   › Presupuesto de material*. Se asigna por prototipo (todos sus lotes) o por lista de lotes
   (`Mza 20`, `1-18, 20`), monto por lote sin IVA.
2. **Proveedores con IVA** (compras): en *Proveedores*, la tasa de cada uno. Si un renglón del
   pedido no la trae ni el proveedor la tiene, se usa 8%.

## El ciclo

| Paso | Dónde | Quién |
| --- | --- | --- |
| Pedir material para uno o varios lotes | Requisiciones → Nueva requisición → Guardar y enviar | Residente |
| Autorizar (aparta lo que hay en el almacén) | Requisiciones → Por autorizar → Autorizar y apartar | Coordinación (nunca quien la pidió) |
| Ver lo que falta comprar, por proveedor | Pedidos → Falta comprar | Compras |
| Armar y enviar el pedido | Pedidos → Armar pedido → Guardar y marcar enviado | Compras |
| Recibir lo que llegó (se aparta para la requisición más urgente) | El pedido → Recibir | Almacén |
| Surtir con un vale ligado | La requisición → Surtir (vale) | Almacén |
| Autorizar y entregar el vale | Autorizaciones; el vale → Entregar y aplicar | Residente o coordinación; almacén |
| Ya no se necesita lo que falta | La requisición → Cerrar (libera lo apartado) | Residente o coordinación |
| El proveedor ya no entrega el resto | El pedido → Cerrar con lo recibido (vuelve a faltantes) | Compras |

Notas:

- Un vale suelto (*Documentos › Vale de salida*) no puede tomar lo apartado: si la existencia libre
  no alcanza, el sistema lo rechaza con "existencia insuficiente".
- Si llegó material sin pedido para una requisición autorizada, *Apartar lo que llegó* en la
  requisición aparta lo que haya libre.
- Un vale ligado o una recepción contra pedido no se corrigen: se cancelan y se vuelve a surtir o
  a recibir.

## La junta del miércoles

*Reportes* (cada pestaña tiene **Descargar Excel**):

- **Por contrato:** material del histórico + sistema, destajo, presupuesto y lotes en aviso.
  El nombre del contrato abre el **costo por lote**, que reemplaza la hoja del frente.
- **Costo agrupado:** por contrato, frente, lote, paquete, razón, material o mes, con filtro de
  fechas y de origen (histórico o sistema).
- **Avisos de presupuesto:** lotes al 80% o más.
- **Requisición vs surtido** y **Valor del inventario**.

## Conciliar un contrato contra la hoja de su frente

*Reportes › el contrato › Conciliación contra la hoja del frente › Conciliar* (coordinación,
dirección o administración). Toma el archivo de la última carga confirmada de Salidas, recalcula la
hoja del frente desde las celdas capturadas y explica la diferencia con el sistema:

| Motivo | Qué significa |
| --- | --- |
| IVA | La hoja valora con el precio con IVA; el sistema, sin IVA |
| Ponderación | La hoja usa el promedio simple de las entradas; el sistema, el ponderado |
| Salidas sin columna | El lote no tiene columna en la hoja: la hoja no lo suma |
| Reparto / lote corregido | La hoja suma la salida en otro lote que el sistema (lote ilegible, corregido en la migración) |
| Material, razón, frente | El texto de la salida no coincide con el criterio del `SUMIFS` |
| No entregado | El vale no se entregó: el sistema no lo cuenta |
| Solo en la hoja | El renglón quedó en cuarentena o se descartó en la migración |
| Sin precio | El material no tiene entradas con precio en la hoja |

Resultado con el Excel real de almacén (arnés, PostgreSQL 16):

| Frente | Lotes | Sistema | Hoja | IVA | Ponderación | Sin columna | Tiempo |
| --- | --- | --- | --- | --- | --- | --- | --- |
| CORCELES | 28 | 125,859.20 | 135,638.32 | 9,551.78 | 525.69 | −298.30 | 2.2 s |
| PALERMO | 86 | 616,909.19 | 656,441.38 | 51,152.27 | −1,778.14 | −9,841.92 | 0.7 s |
| GRANADA | 62 | 332,229.02 | 328,083.89 | 26,235.89 | −404.85 | −29,976.17 | 0.5 s |

Sistema + efectos = hoja en cada lote, salvo centavos de redondeo que se reportan aparte. Ocho
celdas del archivo (`G482`, `G483`, `H4105`–`H4110`) son copias de `=2/8` sin texto de fórmula: se
usa su valor guardado y la conciliación lo avisa. (Esas mismas seis cantidades de Salidas quedan
en cuarentena en la carga de H5; resolverlas ahí las mete al sistema.)

## Prueba de carga

```
make dev          # en otra terminal
make carga        # 20 usuarios, 1 minuto; falla si p95 ≥ 500 ms o hay errores
BASE=https://preproduccion… USUARIO=… CONTRASENA=… VUS=30 DURACION=3m make carga
```

Solo lee; se puede correr contra preproducción con los datos migrados. En el arnés, cada reporte
tarda 28–97 ms con un usuario sobre los 4,182 renglones del histórico de Salidas.

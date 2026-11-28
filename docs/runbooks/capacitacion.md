# Capacitación para el arranque del piloto (H8)

Una sesión por rol, en **preproducción** (nunca en producción), la semana antes del corte. Cada
quien entra con su usuario y hace el ejercicio completo de su rol; al final, el ejercicio de todos
juntos. Datos: los de preproducción (la migración de H3 y H5 ya está cargada ahí).

Preparar antes: un almacén de práctica sin movimientos para el ejercicio de la apertura (el del
piloto en preproducción sirve si todavía no se abrió), usuarios de cada rol y el segundo factor de
administración y dirección dado de alta.

## Almacén (90 minutos)

| Ejercicio | Dónde | Se termina cuando |
| --- | --- | --- |
| Entrar y ver las existencias del almacén | Existencias | Encuentra un material por nombre parcial |
| Abrir la apertura del almacén de práctica | Apertura → Abrir | Ve el conteo con todos los materiales |
| Contar 10 materiales y marcar en 0 lo no encontrado | Conteo físico | Todo contado |
| Recibir una compra con su factura | Documentos → Recepción de compra | Recepción aplicada |
| Hacer un vale de un bote para varios lotes (`1-18, 20`) | Documentos → Vale de salida | Enviado a autorizar |
| Entregar e imprimir el vale | El vale → Entregar y aplicar → Imprimir | Vale impreso |
| Devolver lo que sobró de un vale | El vale → Devolución | Devolución aplicada |
| Prestar una herramienta y registrar que regresó | Préstamos | Préstamo devuelto |
| Qué hacer si el sistema no responde | `corte.md` › Seguir o regresar | Sabe dónde están los vales de papel |

Puntos que se repiten: lo prestado no se cuenta (entra por su préstamo); toda salida lleva contrato,
lote, paquete y razón; nunca se fuerza una existencia negativa, se captura primero la recepción.

## Residente (30 minutos)

| Ejercicio | Dónde |
| --- | --- |
| Ver los lotes y el gasto de sus contratos | Contratos, Reportes |
| Autorizar en grupo los vales de sus contratos | Autorizaciones |
| Hacer una requisición por lote | Requisiciones |

## Coordinación (45 minutos)

| Ejercicio | Dónde |
| --- | --- |
| Autorizar vales, traspasos, mermas y reasignaciones | Autorizaciones |
| Revisar la apertura: materiales con existencia, sin costo y con diferencia contra el Excel | Apertura |
| Firmar la apertura (primera firma) y retirar las firmas | Apertura → Firmar |
| Presupuesto por lote y el reporte de costo | Reportes |

La firma de la apertura congela lo que se firma: si después hay que corregir algo, se retiran las
firmas y se vuelve a firmar. Quien abrió el conteo o contó no firma.

## Administración (60 minutos)

| Ejercicio | Dónde |
| --- | --- |
| Entrar con el segundo factor | Inicio de sesión |
| Resolver los préstamos de la apertura: ligar material, dar de alta una herramienta, descartar con motivo | Apertura → Préstamos |
| Capturar los costos que faltan y corregir uno propuesto | Apertura → Materiales, filtro *Sin costo* |
| Firmar y aplicar (segunda firma) | Apertura → Firmar y aplicar |
| Descargar el acta y el plan de reversa en formato Salidas | Apertura |
| Cerrar un conteo físico normal y el mes | Conteo físico, Cierre de mes |
| Resolver la cuarentena de una carga y ver la conciliación | Migración |

Administración no captura lo contado en la apertura que va a firmar.

## Dirección (20 minutos)

| Ejercicio | Dónde |
| --- | --- |
| La junta del miércoles con el reporte de costo por contrato y lote | Reportes |
| El valor del inventario y la apertura aplicada | Reportes, Apertura |
| Criterio de las 12:00 del lunes | `corte.md` |

## Ejercicio de todos (45 minutos)

1. Almacén abre la apertura del almacén de práctica, cuenta y marca en 0.
2. Administración resuelve los préstamos y los costos.
3. Coordinación firma; administración firma y aplica.
4. Almacén hace el primer vale; el residente lo autoriza; el almacén lo entrega e imprime.
5. Administración descarga el plan de reversa y lo abre en Excel.

Al terminar, en preproducción se cancela (o se deja) la apertura de práctica; producción no se toca.

## Reportar un problema (todos, 10 minutos · H9)

- El botón *Reportar un problema* está en todas las pantallas. Se usa en el momento, desde la
  pantalla donde pasó: lleva sola la pantalla y, si algo acaba de fallar, el identificador de la
  petición.
- Se elige qué tan grave es: **crítica** si no se pueden registrar entradas o salidas o quedó mal
  una existencia o un costo; **mayor** si se puede seguir con un rodeo; **menor** para lo demás.
- En *Incidentes* cada quien ve los suyos y su seguimiento. Si se resolvió pero sigue pasando, se
  reabre con una nota.
- Ejercicio: reportar un problema menor de prueba en preproducción y verlo en *Incidentes*.

## Lista de asistencia

| Rol | Nombre | Fecha | Hizo el ejercicio |
| --- | --- | --- | --- |
| Almacén | | | |
| Residente | | | |
| Coordinación | | | |
| Administración | | | |
| Dirección | | | |

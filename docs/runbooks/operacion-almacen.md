# Operación del almacén (H4)

Cómo se trabaja en el sistema a partir del arranque del frente piloto. Lo hace el almacenista; lo
autoriza el residente o coordinación; lo cierra administración.

## Antes de empezar (una sola vez, administración)

1. En **Existencias**, dar de alta el almacén de cada frente (CORCELES, PALERMO, GRANADA).
2. En **Contratos**, revisar que cada contrato tenga a su residente asignado: el residente solo
   autoriza vales de sus contratos.
3. Saldo de apertura (H8): **Conteo físico** → abrir el conteo del almacén vacío, capturar lo
   contado y el costo de cada material. Administración lo cierra (no quien lo abrió) y los
   sobrantes entran como existencia valuada.

## Día a día

| Qué pasa | Dónde | Quién |
| --- | --- | --- |
| Llega material del proveedor | Documentos → Recepción de compra → Guardar y aplicar | Almacén |
| Sale material a obra | Documentos → Vale de salida → Guardar y enviar a autorizar | Almacén |
| Se autoriza el vale | Autorizaciones (varios a la vez) | Residente del contrato o coordinación |
| Se entrega el vale | El vale → Entregar y aplicar → Imprimir para firma | Almacén |
| Regresa material de obra | El vale → Devolución | Almacén |
| Material a otro almacén | Traspaso entre almacenes; lo autoriza coordinación | Almacén / coordinación |
| El costo se cargó al lote equivocado | Reasignación de lote; lo autoriza coordinación | Almacén / coordinación |
| Material roto o perdido | Merma, con foto; la autoriza coordinación | Almacén / coordinación |
| La factura trae otro precio | Ajuste de costo (factura); lo autoriza coordinación | Almacén / coordinación |
| Se presta una herramienta | Préstamos → Prestar; al regresar, "Regresó" | Almacén |

Un vale de un bote para varios lotes se captura en un solo renglón: manzana y lotes `1-18, 20`.
El sistema reparte la cantidad exacta (el último lote se lleva el residuo).

## Errores que van a aparecer

- **"La salida … supera lo disponible"**: no hay existencia o está prestada. Capturar la
  recepción pendiente o regresar el préstamo; nunca se fuerza una existencia negativa.
- **"Quien captura un documento no lo autoriza"**: lo tiene que autorizar otra persona.
- **"El almacén tiene un conteo físico abierto"**: terminar o cancelar el conteo.
- **"Ese mes ya está cerrado"**: usar una fecha del mes en curso.
- **"El lote … es de otro frente"**: el material sale del almacén de ese frente; traspasar
  primero.

## Cierre de mes (administración)

1. En **Documentos**, filtrar el mes y estado "Borrador", "Por autorizar" y "Autorizado": aplicar o
   cancelar cada uno.
2. **Cierre de mes** → Cerrar. Queda la foto del inventario valuado por almacén.
3. Un mes cerrado no se reabre desde la aplicación. Si hace falta, se decide con el responsable
   técnico y se hace en la base con el rol migrador, con bitácora.

# ADR-012 · Abastecimiento ligado al inventario y reportes sobre vistas

**Contexto.** H6 tiene que apagar la hoja Semanal y las hojas por frente: la requisición del
residente, lo que el almacén aparta y surte, lo que compras pide y lo que llega, y los reportes de
costo de la junta del miércoles, que hoy salen de `SUMIFS` en cada hoja de frente. El inventario de
H4 ya tiene `saldo.reservado` (vacío) y la regla de que toda salida lleva contrato, lote, paquete y
razón.

**Decisión.**

- **Módulo `abastecimiento` (V009):** `requisicion` y `requisicion_linea` (un renglón por lote; con
  varios lotes la cantidad se reparte exacta, como el vale), `pedido`, `pedido_linea` y
  `pedido_requisicion` (qué parte de un renglón de pedido es para qué renglón de requisición). Todas
  con RLS, bitácora, `version` y FK compuestas; la requisición apunta al almacén de su frente.
- **La reserva vive en inventario.** `inventario.reserva` guarda lo apartado por renglón de
  requisición; un trigger mantiene `saldo.reservado` como su suma. Abastecimiento aparta y libera
  solo por `InventarioApi`, dentro de la transacción del que llama y con el saldo bloqueado. Lo
  disponible para un vale suelto es existencia − reservado − prestado.
- **Ligas por renglón, no por documento.** `movimiento_linea` gana `requisicion_linea_id` (vale) y
  `pedido_linea_id` (recepción), con FK que también exigen el mismo material. Lo surtido y lo
  recibido se calculan de los documentos aplicados, netos de devoluciones; no hay contadores que se
  desincronicen.
- **Ciclo.** Autorizar una requisición (coordinación; nunca quien la capturó) aparta lo que hay,
  renglón por renglón en orden de material. Lo que falta es faltante: lo pendiente que no está
  apartado, en un vale abierto ni pedido. Compras lo ve agrupado por el último proveedor que surtió
  el material (sistema, y si no, el histórico del Excel) y arma el pedido. Recibir contra el pedido
  crea y aplica la recepción con el precio pedido y aparta lo recibido para los renglones ligados,
  el más urgente primero. Surtir crea el vale ligado (con contrato, lote, paquete y razón del
  renglón) que sigue el flujo normal de autorización; al aplicarse consume primero lo apartado.
  Cerrar libera lo apartado; cerrar un pedido regresa lo no recibido a faltantes.
- **El conteo físico recorta reservas.** Si un faltante de conteo deja la existencia por debajo de
  lo apartado, se recortan las reservas más recientes primero.
- **Presupuesto por lote.** Se usa `unidad_obra.presupuesto_material` (ya existía) y se asigna en
  bloque por prototipo o lista de lotes (permiso nuevo `presupuesto:editar`: coordinación, dirección
  y administración). Solo consume la razón Programa. Semáforo: verde abajo de 80%, amarillo de 80% a
  menos de 100%, rojo desde 100%. Es aviso, no bloqueo.
- **Reportes de lectura sobre vistas `security_invoker`** (`v_consumo_unidad`,
  `v_presupuesto_unidad`, `v_existencia_valorizada`, más las de H5). Sin vistas materializadas:
  PostgreSQL no aplica RLS a una vista materializada. El residente ve solo sus contratos.
- **Excel propio.** `LibroExcel` escribe el .xlsx con texto en línea y formatos de número, sin
  fórmulas (un texto que empieza con `=` nunca se vuelve fórmula). Todo reporte acepta
  `?formato=xlsx`.
- **Conciliación contra la hoja del frente.** Se recalcula la hoja del frente con las celdas
  capturadas del mismo archivo de la carga confirmada de Salidas (no se leen valores guardados de
  fórmulas), con la semántica de `SUMIFS` y el promedio de `AVERAGEIFS` con IVA. Cada renglón se
  descompone en motivos (sin columna, reparto, lote corregido, material, razón, frente, no
  entregado, cantidad, solo en la hoja, sin precio, ponderación, IVA) y sistema + efectos = hoja,
  lote por lote. Excepción: una fórmula compartida copiada sin texto (`=2/8` arrastrado) toma su
  valor guardado y lo dice en los avisos.

**Consecuencias.** Un vale ligado o una recepción contra pedido no se corrigen: se cancelan y se
vuelve a surtir o recibir. El IVA del pedido es el del renglón, el del proveedor o 8%.
Los reportes pesados se calculan en cada consulta; si crecen, el siguiente paso es una tabla de
resumen por carga, no una vista materializada.

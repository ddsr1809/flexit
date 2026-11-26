# ADR-010 · Valuación por valor, cargos a obra y ajuste de costo por factura

**Contexto.** El plan técnico guardaba en el saldo la existencia y el costo promedio, y
calculaba el costo de un lote sumando `cantidad × costo_unitario` de las líneas de vale. Con ese
modelo se pierden centavos: el promedio se redondea a 6 decimales y la última salida no deja el
valor exactamente en cero. Además, no hay dónde registrar una devolución, una reasignación ni una
corrección de factura sin tocar el costo ya congelado de un vale (regla 3).

**Decisión.**

- **El saldo guarda existencia y valor.** El promedio es `valor ÷ existencia`. Una entrada suma
  su importe exacto (con descuento y flete prorrateado), y una salida al promedio resta
  `cantidad × promedio`. Si la salida se lleva toda la existencia, se lleva todo el valor. Con
  existencia en cero, el promedio conserva el último costo. La base exige `existencia >= 0`,
  `valor >= 0` y `valor = 0` cuando la existencia es cero.
- **El kardex guarda la cantidad y el importe con signo**, y la existencia, el valor y el promedio
  después de cada renglón. Para cada material y almacén, la existencia es Σ cantidad y el valor
  es Σ importe; la foto del cierre de mes se calcula así.
- **El costo de obra vive en `inventario.cargo`**, un renglón con signo por contrato, lote,
  paquete y clasificación:
  - El vale suma su cargo.
  - La devolución resta del cargo original.
  - La reasignación resta en un lote y suma en otro.
  - El ajuste de costo agrega un importe sin cantidad.
  - `linea_base_id` liga cada cargo con su línea de vale (o de reasignación). Lo que queda de esa
    línea en obra es la suma de sus cargos, y limita cuánto se puede devolver o reasignar.
- **Reparto:** las líneas de un mismo renglón (un bote para 18 lotes) salen juntas. Se calcula el
  importe del total y se prorratea, con el residuo en la última, así cuadran cantidad e importe.
- **Ajuste de costo por diferencia de factura**, según la decisión de H4:
  - La diferencia es `(costo nuevo − costo vigente) × cantidad recibida`.
  - La parte que sigue en el almacén, `diferencia × min(existencia, cantidad) ÷ cantidad`, ajusta
    el valor y con él el promedio.
  - El resto se carga como ajuste a los vales del material que salieron del almacén después de la
    recepción, en proporción a lo que cada uno conserva en obra.
  - Si ya no hay existencia ni vales posteriores (todo salió por traspaso o merma), el ajuste se
    rechaza (`AJUSTE_SIN_DESTINO`).

**Consecuencias.**

- Los reportes de costo por lote (H6) suman `inventario.cargo`, no las líneas de vale. La vista
  que une histórico y vivo usa `historico.salida` y `inventario.cargo`.
- Una devolución y una reasignación salen al costo efectivo de lo que queda: importe restante ÷
  cantidad restante, con los ajustes de factura incluidos.
- El reparto de un ajuste entre vales posteriores es una aproximación, como lo es el costo
  promedio. Lo que se reasignó a otro lote antes del ajuste ya no recibe su parte.

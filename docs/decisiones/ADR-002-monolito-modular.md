# ADR-002 · Monolito modular con Spring Modulith

**Contexto.** Una salida de almacén toca kardex, saldo, presupuesto y bitácora en la misma
transacción. Hay un equipo de una o dos personas.

**Decisión.** Un solo proceso y un solo despliegue, con un paquete por módulo. Cada
`package-info.java` declara qué módulos puede usar; `ModularidadTest` rompe el build si alguien
cruza una frontera o usa un subpaquete interno de otro módulo. `ArquitecturaTest` (ArchUnit) añade
reglas de capas: los controladores no abren transacciones y viven en `web`.

**Consecuencias.** Si algún día un módulo debe salir como servicio, sus fronteras ya están limpias.
Mientras tanto, una transacción de base de datos basta donde los microservicios exigirían sagas.

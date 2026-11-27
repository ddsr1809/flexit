# Sistema para constructora

ERP modular para una constructora: inventario, costo por lote y abastecimiento, y después personal y
destajo. Qué está hecho y qué sigue: `docs/estado.md`.

- Plan por hitos y migración desde Excel: ver `docs/planes.md`
- Plan técnico completo (arquitectura, esquema, API, seguridad, CI/CD): https://claude.ai/artifact/6TW8x99G6zW3S4rjgTE6Ke
- Decisiones: `docs/decisiones/` · Operación: `docs/runbooks/`

## Stack
Java 25, Spring Boot 4.1, Spring Modulith 2.1, Maven (wrapper), PostgreSQL 18, Flyway (API, con
rol migrador), JdbcClient, Spring Security con sesión JDBC, React 19 + TypeScript + Vite,
TanStack Query y Router, React Hook Form + Zod, Tailwind 4, Testcontainers, Playwright,
Docker Compose, Caddy, Terraform (Vultr + Cloudflare), GitHub Actions + GHCR.

## Comandos
- `make dev`  base local + backend en :8080 (perfil dev; usuario admin / admin-local-2026)
- `make front` frontend en :5173
- `make test` todas las pruebas (Docker para Testcontainers)
- `make lint` / `make fmt`
- `make api`  regenera `frontend/openapi.json` y los tipos desde el backend corriendo
- `make sql`  pruebas de RLS, permisos, bitácora y folios directo en PostgreSQL

## Reglas que nunca se rompen
1. El kardex es inmutable: nunca UPDATE ni DELETE; se corrige con un movimiento inverso.
2. La existencia nunca queda negativa: `SELECT … FOR UPDATE` en saldo, en orden de material_id.
3. El costo se congela en la línea al aplicar; nunca se recalcula el pasado.
4. Toda salida lleva contrato, unidad de obra, paquete y clasificación.
5. Quien captura o solicita no autoriza su propio documento.
6. Dinero y cantidades en BigDecimal; nunca double.
7. Ningún módulo toca tablas de otro: usar su API pública. `ModularidadTest` debe pasar.
8. Migraciones solo nuevas (`V0NN__`), compatibles hacia atrás; nunca se edita una aplicada.
9. Nada de datos reales ni secretos en el repositorio.
10. Toda tabla con `empresa_id` lleva RLS (`ENABLE` + `FORCE` + política `por_empresa`), permisos
    explícitos para el rol `app` y, si es de negocio, el trigger de bitácora.
11. Las acciones de GitHub se fijan a un commit (SHA), nunca a una etiqueta.
12. Las FK entre tablas con `empresa_id` son compuestas (`empresa_id, id`): PostgreSQL revisa las
    FK sin RLS. Todo registro editable lleva `version` y su `PATCH` exige `If-Match` (ADR-007).
13. El residente solo ve sus contratos (`estructura.contrato_usuario`); cualquier consulta nueva
    de contratos, lotes o costos aplica el mismo alcance (ADR-006).

14. La migración (módulo `importacion`) nunca escribe en tablas de otro módulo: crea por
    `ImportacionEstructura`, `ImportacionCatalogo` y `Personas`, con `origen_carga` para poder
    revertir. Nunca lee una celda que sea fórmula. Cada regla nueva lleva su prueba con una muestra
    anonimizada y, si cambia resultados, sube `Comun.VERSION_REGLAS`.
15. El costo de obra se lee de `inventario.cargo`, nunca de las líneas de vale: devolver,
    reasignar o corregir una factura agrega cargos, no cambia los anteriores (ADR-010).
16. El histórico del Excel (esquema `historico`) solo lo escribe la migración por
    `ImportacionHistorico`, nunca se edita (se revierte la carga completa) y no entra al kardex.
    Un reporte de costo suma histórico y vivo con `reportes.v_costo_unidad` (ADR-011).
17. Lo apartado para una requisición vive en `inventario.reserva` (un renglón por renglón de
    requisición); `saldo.reservado` es su suma y solo lo mueve el trigger. Un vale suelto nunca toma
    lo apartado; solo el vale ligado a la requisición lo consume, al aplicarse (ADR-012).
18. Lo surtido y lo recibido se calculan de los documentos aplicados ligados por renglón
    (`movimiento_linea.requisicion_linea_id` y `pedido_linea_id`), nunca se guardan aparte. Un
    documento ligado no se corrige: se cancela y se vuelve a surtir o recibir.

## Cómo funciona la seguridad por empresa
`ContextoEmpresa` (sesión web o `ContextoEmpresa.como(...)`) → `DataSourceConEmpresa` fija
`app.empresa_id` y `app.usuario_id` en cada conexión que sale del pool → las políticas de RLS
filtran y la bitácora registra al usuario. Sin contexto, la base no muestra filas. Las consultas
además filtran por `empresa_id` en el código (doble filtro).

## Definición de terminado
Pruebas nuevas en verde, `make test` y `make lint` en verde, `frontend/openapi.json` regenerado si
cambió la API, `docs/estado.md` actualizado, PR que cierra el issue.

## Cómo trabajar
- Antes de escribir código, propón el plan en pocas líneas y espera confirmación si toca costeo,
  existencias, permisos, RLS o migraciones.
- Si una decisión no está en los docs, pregunta; no inventes reglas de negocio.
- Muestras de datos: solo `tools/validacion-datos/muestras/` (anonimizadas).

# ADR-007 · Catálogos con JdbcClient y concurrencia optimista por versión

**Contexto.** El plan técnico preveía Spring Data JPA para los catálogos. H1 se construyó con
JdbcClient y las defensas en la base (RLS, FK compuestas con `empresa_id`, bitácora por trigger).

**Decisión.** H2 sigue con JdbcClient: las consultas filtran por empresa a la vista, las
columnas se llaman como los campos de los `record` y JdbcClient los llena solo. Cada registro
editable lleva `version`; la respuesta la expone (campo `version` y `ETag` en los GET por id) y
todo `PATCH` exige `If-Match`. El `UPDATE … WHERE version = :version` que no toca filas responde
412 (`VERSION_DESACTUALIZADA`); sin `If-Match`, 428.

**Consecuencias.** Un solo estilo de acceso a datos en todo el sistema, sin sesión de Hibernate
que interfiera con los bloqueos de existencia de H4. Más SQL escrito a mano, cubierto por
`CriterioH2Test` y por `tools/pruebas-sql/estructura-catalogo.sql`.

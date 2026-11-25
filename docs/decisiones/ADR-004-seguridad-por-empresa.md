# ADR-004 · Empresa filtrada en la aplicación y en PostgreSQL

**Contexto.** El sistema puede atender a más de una empresa (RM Alemán y otras razones sociales).
Un `WHERE empresa_id = ?` olvidado no debe exponer datos de otra.

**Decisión.** Cada tabla con `empresa_id` tiene RLS con `FORCE` y la política `por_empresa`, que
compara contra `compartido.empresa_actual()`. `DataSourceConEmpresa` fija `app.empresa_id` y
`app.usuario_id` en cada conexión que entrega el pool, siempre (también vacíos), a partir de
`ContextoEmpresa`. Sin contexto la base no muestra nada. El inicio de sesión, que ocurre antes de
saber la empresa, pasa por funciones `SECURITY DEFINER` que exponen solo lo necesario.

**Consecuencias.** Cambiar de empresa dentro de una transacción abierta no tiene efecto: el
contexto se fija al tomar la conexión. Las tareas en segundo plano usan `ContextoEmpresa.como`.
Las pruebas corren como el rol `app` (un superusuario se saltaría las políticas).

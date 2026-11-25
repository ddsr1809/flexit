# ADR-003 · La app no puede cambiar el esquema

**Contexto.** Si la app tiene permisos de DDL, un error o una inyección pueden borrar tablas.

**Decisión.** Dos roles. `migrador` es dueño de los esquemas y corre Flyway; `app` solo tiene los
permisos que cada migración le otorga, tabla por tabla. Flyway se usa por su API
(`Migraciones.java`), no por la autoconfiguración de Spring Boot:

- `desplegar.sh` corre la misma imagen con el perfil `migrar` (rol migrador) y después arranca la app.
- La app arranca en modo `validar`: si falta una migración, no levanta.
- Local y pruebas usan el modo `migrar` al arrancar.

**Consecuencias.** La contraseña del migrador está en el `.env` del servidor pero solo la usa el
paso de migración. Toda migración debe ser compatible hacia atrás para poder revertir la imagen sin
revertir la base.

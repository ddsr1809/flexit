# ADR-001 · PostgreSQL 18 en lugar de MariaDB

**Contexto.** La propuesta de agosto de 2026 usaba MariaDB. El sistema atiende a varias empresas,
necesita auditoría con el antes y el después de cada cambio, y la migración del Excel guarda
renglones crudos de forma flexible.

**Decisión.** PostgreSQL 18: seguridad por fila (RLS) para separar empresas en la propia base,
`jsonb` para bitácora y renglones de migración, `INSERT … ON CONFLICT … RETURNING` para folios sin
huecos y consultas recursivas para paquetes.

**Consecuencias.** RDS, Cloud SQL y Aurora ofrecen PostgreSQL administrado, así que la ruta a la
nube queda abierta. El documento de arquitectura de agosto (MariaDB, Thymeleaf) queda superado en
esos dos puntos por el Plan técnico completo.

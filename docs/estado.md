# Estado del proyecto

Última actualización: 25 nov 2026 · Hito en curso: **H1 · Plataforma lista** (semanas 1 y 2)

## H1 · issues 1 a 11

| # | Issue | Estado | Dónde se comprueba |
| --- | --- | --- | --- |
| 1 | Repositorio, protección de main, plantillas, CODEOWNERS, Dependabot | Hecho en el código; falta crear el repositorio y correr `tools/github/configurar-repo.sh` | `.github/`, runbook `primer-arranque.md` |
| 2 | Esqueleto Spring Boot 4.1 + Modulith + wrapper + ModularidadTest | Hecho | `ModularidadTest`, `ArquitecturaTest` |
| 3 | Docker Compose local y Makefile | Hecho | `make dev`, `make sql` |
| 4 | Flyway con roles app y migrador; V001 y V002 | Hecho | `Migraciones.java`, `tools/pruebas-sql` |
| 5 | CI: formato, pruebas con Testcontainers, cobertura, gitleaks | Hecho | `ci.yml` |
| 6 | Terraform de preproducción, firewall, DNS; cloud-init endurecido | Hecho en el código; falta el primer `apply` | `infra/terraform/` |
| 7 | Dockerfile, imagen en GHCR con Trivy, despliegue automático a preproducción | Hecho en el código; falta el primer despliegue | `Dockerfile`, `release.yml`, `desplegar.sh` |
| 8 | Login, sesión JDBC, CSRF, Argon2, roles y permisos | Hecho | `AutenticacionTest` |
| 9 | Seguridad por empresa: RLS, contexto por conexión, pruebas con dos empresas | Hecho | `SeguridadPorEmpresaTest`, `tools/pruebas-sql` |
| 10 | Bitácora con trigger JSONB; folios por tipo y año | Hecho | `BitacoraYFoliosTest`, `tools/pruebas-sql` |
| 11 | Frontend: Vite, rutas, login, marco, cliente OpenAPI | Hecho | `npm test`, Playwright en CI |

### Qué ya se verificó y qué no

- **Verificado:** las migraciones y las defensas de la base (15 pruebas SQL contra PostgreSQL 16:
  RLS en lectura y escritura, permisos del rol app, bitácora sin contraseñas, folios sin huecos,
  bloqueo a los 5 intentos); el frontend (tipos, lint, 11 pruebas, build) y los selectores de
  Playwright contra una API simulada; Dockerfile, scripts, compose y workflows con sus linters.
- **Se verifica por primera vez en CI:** la compilación y las pruebas del backend Java (el entorno
  donde se escribió no llegaba a Maven Central) y el flujo completo de Playwright. `terraform
  validate` y `terraform fmt` también corren en CI.
- **Criterios de aceptación de H1 pendientes de demostrar en preproducción:** PR fusionado visible
  en menos de 15 minutos; prueba de dos empresas en verde; `ModularidadTest` en verde.

## Decisiones de esta etapa

- Flyway por su API con rol migrador y app en modo validar (ADR-003).
- Acciones de GitHub fijadas por SHA tras el ataque a trivy-action (ADR-005).
- Login con usuario y contraseña; segundo factor (TOTP) para administración y dirección queda
  para H7, antes de producción.
- Sin JPA todavía: H1 usa JdbcClient. Spring Data JPA entra en H2 con los catálogos.
- `traceId` en logs: por ahora un identificador por petición (`X-Request-Id`); el rastreo con
  OpenTelemetry entra con la observabilidad del mes 2.
- Almacenamiento de objetos (S3) y respaldos con restic: en H4 (fotos de merma) y H7.

## Siguiente: H2 · Catálogos y estructura (issues 12 a 16)

Módulos `estructura` y `catalogo`: cliente, frente, contrato, unidad de obra, prototipo, paquete y
equivalencia Ruba ↔ RM, unidades de medida con conversión, materiales y proveedores, con API y
pantallas; `usuario_contrato` para el alcance del residente.

# Sistema para constructora

Inventario, costo por lote y abastecimiento para una constructora de vivienda. Monolito modular en
Spring Boot que sirve la API y el frontend React, sobre PostgreSQL, desplegado con Docker Compose.

## Levantarlo en 5 minutos

Requisitos: Docker, Java 25 (Temurin), Node LTS, `make`.

```bash
make dev      # PostgreSQL + backend en http://localhost:8080 (migra y crea el usuario admin)
make front    # en otra terminal: frontend en http://localhost:5173
```

Entra con `admin` / `admin-local-2026` (solo existe en local).

## Estructura

| Carpeta | Qué hay |
| --- | --- |
| `backend/` | Spring Boot 4.1, un paquete por módulo (`organizacion`, `estructura`, `catalogo`, `inventario`, `abastecimiento`, `reportes`, `importacion`, `compartido`) |
| `backend/src/main/resources/db/migration/` | Migraciones de Flyway |
| `frontend/` | React + TypeScript; `openapi.json` es el contrato con el backend |
| `infra/compose/` | Docker Compose de producción, local y e2e; Caddyfile |
| `infra/terraform/` | Servidores en Vultr y DNS en Cloudflare |
| `infra/scripts/` | `desplegar.sh`, `revertir.sh` |
| `tools/` | Pruebas SQL, configuración del repositorio en GitHub, validación de datos |
| `docs/` | Estado, plan por hitos, decisiones y runbooks |

## Cómo llega un cambio a preproducción

PR → CI (backend contra PostgreSQL 18, frontend, Playwright, Terraform, pruebas SQL, gitleaks) →
fusión a `main` → `release.yml` construye la imagen, la escanea con Trivy, la publica en GHCR con
su SHA, migra con el rol `migrador` y la despliega. El paso final comprueba desde internet que
`/api/v1/version` responde con ese SHA.

Más detalle en `CLAUDE.md` y `docs/`.

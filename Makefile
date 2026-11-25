# Atajos del día a día. Requiere Docker, Java 25 y Node LTS.
.PHONY: ayuda db dev back front test test-back test-front lint fmt api sql e2e imagen limpiar

COMPOSE_DEV = MIGRADOR_LOCAL=migrador-local APP_LOCAL=app-local docker compose -f infra/compose/compose.dev.yml
COMPOSE_E2E = MIGRADOR_E2E=migrador-e2e APP_E2E=app-e2e docker compose -f infra/compose/compose.e2e.yml

ayuda:
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  make %-8s %s\n", $$1, $$2}'

db: ## Levanta PostgreSQL 18 local con los roles app y migrador
	$(COMPOSE_DEV) up -d --wait db

dev: db ## Base + backend (perfil dev, migra y crea admin/admin-local-2026); el frontend va en otra terminal: make front
	cd backend && SPRING_PROFILES_ACTIVE=dev ./mvnw -q spring-boot:run

back: dev

front: ## Frontend con recarga en caliente en http://localhost:5173 (usa el backend de make dev)
	cd frontend && npm install && npm run dev

test: test-back test-front sql ## Todas las pruebas (requiere Docker para Testcontainers)

test-back: ## Pruebas del backend contra PostgreSQL real
	cd backend && ./mvnw -B verify

test-front: ## Pruebas, tipos y build del frontend
	cd frontend && npm test -- --run && npm run build

lint: ## Formato y análisis estático
	cd backend && ./mvnw -B -q spotless:check
	cd frontend && npm run lint

fmt: ## Aplica el formato (Java y TypeScript)
	cd backend && ./mvnw -B -q spotless:apply
	cd frontend && npm run formato

api: ## Regenera frontend/openapi.json y los tipos desde el backend corriendo (make dev)
	curl -fsS http://localhost:8080/v3/api-docs | jq . > frontend/openapi.json
	cd frontend && npm run gen:api

sql: db ## Pruebas de RLS, permisos, bitácora y folios directo en la base
	PGHOST=localhost PGUSER=postgres PGPASSWORD=postgres-local tools/pruebas-sql/correr.sh

e2e: ## La app completa en Docker + Playwright (igual que CI)
	$(COMPOSE_E2E) build migrar
	$(COMPOSE_E2E) up -d --wait db
	$(COMPOSE_E2E) run --rm migrar
	$(COMPOSE_E2E) up -d --wait app
	cd frontend && npx playwright test; status=$$?; cd .. && $(COMPOSE_E2E) down -v; exit $$status

imagen: ## Construye la imagen de producción localmente
	docker build -t constructora:local --build-arg APP_VERSION=$$(git rev-parse --short HEAD) .

limpiar: ## Borra la base local y lo construido
	$(COMPOSE_DEV) down -v
	rm -rf backend/target frontend/dist

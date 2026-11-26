# Estado del proyecto

Última actualización: 26 nov 2026 · Hito en curso: **H3 · Centro de migración y datos maestros** (semanas 4 y 5)

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

## H2 · issues 12 a 16

Hito en curso: **H2 · Catálogos y estructura** (semanas 3 y 4).

| # | Issue | Estado | Dónde se comprueba |
| --- | --- | --- | --- |
| 12 | estructura: cliente, frente, contrato (API + pantallas) | Hecho | `CriterioH2Test`, pantalla Contratos y Catálogos |
| 13 | estructura: unidad de obra, tipo de unidad, prototipo; alcance por contrato | Hecho | `CriterioH2Test.elResidenteSoloVeLosLotesDeSuContrato`, `ClaveUnidadTest` |
| 14 | estructura: paquetes, contrato_paquete y equivalencia Ruba ↔ RM | Hecho | `CriterioH2Test`, pantalla Paquetes |
| 15 | catalogo: unidades de medida, conversiones, categorías | Hecho | `ConversionesTest`, `tools/pruebas-sql` |
| 16 | catalogo: materiales con búsqueda y proveedores | Hecho | `CriterioH2Test`, e2e `estructura.spec.ts` |

### Qué ya se verificó y qué no

- **Verificado:** migraciones V003 a V005 y 19 pruebas SQL nuevas contra PostgreSQL 16 (FK
  compuestas que impiden ligar datos de otra empresa, un lote por frente, equivalencia solo cliente
  ↔ propio, unidades del sistema de solo lectura, búsqueda con pg_trgm, bitácora); cada consulta
  de los repositorios corrida a mano contra una base con datos; el código nuevo compilado contra
  firmas de las APIs; la lógica de claves de lote y conversiones; frontend (tipos, lint, 19 pruebas,
  build) y el e2e del demo en Chrome contra una API simulada, en escritorio y teléfono.
- **Se verifica por primera vez en CI:** compilación y pruebas del backend con Spring
  (`CriterioH2Test`, `ModularidadTest`, `ContratoApiTest`), formato con spotless y el e2e contra
  la app real. `frontend/openapi.json` se escribió a mano con la forma que publica springdoc: correr
  `make api` con el backend arriba y revisar que el diff sea vacío.
- **Criterio de aceptación de H2 pendiente de demostrar en preproducción:** capturar a mano un
  contrato con 3 lotes y 5 materiales; el residente solo ve los lotes de su contrato. Demo: alta
  del contrato 60-KZ7-MF6-C002-2026 con dos lotes y búsqueda de un material por nombre parcial.

### Corrección de H1 encontrada al correr las pruebas por primera vez

Con una sesión abierta, cada petición agotaba el pool de conexiones: leer el usuario en sesión
carga la sesión de la base, esa conexión preguntaba otra vez por el usuario y así en cadena hasta
tomar las 20 conexiones (`CannotGetJdbcConnectionException`, y de ahí 27 pruebas en rojo).
`ContextoEmpresa.actual()` ya no vuelve a leer el contexto mientras lo está leyendo; la prueba es
`ContextoEmpresaTest`. `make sql` usa el cliente de la imagen de PostgreSQL si la máquina no
tiene `psql`.

Después, dos ajustes más para Spring Boot 4 / Spring 7: `ManejadorErrores` va antes que el
manejador de Problem Details de Boot (si no, los errores de validación salían sin `codigo` ni
`campos`), y las pruebas de RLS y permisos revisan el mensaje de PostgreSQL en la causa raíz,
porque Spring 7 ya no lo copia en la excepción traducida.

### Decisiones de esta etapa

- `usuario_contrato` quedó como `estructura.contrato_usuario` (ADR-006).
- JdbcClient también para catálogos; versión e `If-Match` en todo cambio (ADR-007).
- Permisos nuevos: `catalogo:editar` (compras y administración) y `estructura:ver-todo` (todos
  menos residente). La estructura la escribe `sistema:administrar`.
- Unidades de medida y conversiones generales son del sistema (`empresa_id` NULL), visibles para
  todas las empresas y de solo lectura para la app; cada empresa agrega las suyas.
- Clasificaciones de costo: las 8 razones del almacén, como catálogo fijo del sistema.
- Nombres de materiales, clientes, proveedores y paquetes se comparan normalizados
  (`compartido.normalizar`: sin acentos, mayúsculas, espacios simples): "CEMENTO  GRÍS" es el mismo
  material que "Cemento gris".

## H3 · issues 17 a 20 y 42 a 45

| # | Issue | Estado | Dónde se comprueba |
| --- | --- | --- | --- |
| 17 | importacion: motor de carga (subir, filas, reporte, confirmar) | Hecho | `CriterioH3Test`, pantalla Migración |
| 42 | Esquema `migracion`, perfiles por hoja y lectura en streaming | Hecho | V006, `LibroXlsxTest`, `tools/pruebas-sql/migracion.sql` |
| 43 | Motor de reglas: normalización, alias, cuarentena, versión de reglas, idempotencia y reversa | Hecho | `ImportadoresTest`, `CriterioH3Test` |
| 44 | Pantallas: cargas, orden, cuarentena, alias, bitácora | Hecho | e2e `migracion.spec.ts` |
| 18 | Importadores de frentes, contratos, lotes, prototipos y paquetes | Hecho | `ImportadoresTest` |
| 19 | Importadores de materiales y proveedores | Hecho | `ImportadoresTest`, `CriterioH3Test` |
| 45 | Importador de personas (SUA, destajo, almacén) con sugerencias de parecido | Hecho | `ImportadoresTest`, pantalla Personas |
| 20 | Carga de los datos en preproducción y validación con coordinación | Pendiente: lo hace administración | `docs/runbooks/migracion-catalogos.md` |

### Qué ya se verificó y qué no

- **Verificado:** V006 y 13 pruebas SQL nuevas contra PostgreSQL 16 (RLS de `migracion` y
  `persona`, idempotencia por sha256, reversa que no toca otra empresa ni borra si algo usa lo
  creado, sin empresa no corre); el dominio (lector, reglas e importadores, Java puro) con 24
  pruebas sobre las muestras anonimizadas; el orden de carga completo contra los **tres Excel
  reales** en una simulación en memoria (resultados en el runbook), y que subirlos por segunda vez
  no crea nada; el código de aplicación compilado contra firmas de las APIs de Spring; frontend
  (tipos, lint, 25 pruebas, build) y las pantallas en Chrome contra una API simulada, en
  escritorio y a 412 px.
- **Se verifica por primera vez en CI:** `CriterioH3Test` (por HTTP real: orden de carga, subir el
  mismo archivo dos veces, cuarentena, confirmar, revertir, `CARGA_EN_USO`, permisos y otra
  empresa), el formato con spotless y el e2e contra la app real. `frontend/openapi.json` se escribió
  a mano: correr `make api` y revisar el diff.

### Decisiones de esta etapa

- Lector de .xlsx propio (StAX), archivo en `migracion.archivo` hasta S3, proceso en la misma
  petición; el segundo plano llega en H5 (ADR-008).
- `persona` en `organizacion`, permiso `personas:administrar` (solo administración); reversa por
  `origen_carga` con funciones `SECURITY DEFINER` (ADR-009).
- La relación lote → contrato no está en ningún Excel: la decide coordinación en la cuarentena, por
  manzana, o con una hoja "Lotes" (frente, contrato, manzana, lote, prototipo).
- Paquetes: la equivalencia Ruba ↔ RM es por sección (los renglones de las dos listas no se
  corresponden). Un concepto repetido en la misma sección es otro paquete ("… (2)").
- Precio de referencia de materiales: ponderado sin IVA y neto de descuento desde Entradas. Los que
  no tienen entradas con precio entran sin él (advertencia), salvo que se capture en la cuarentena.
- Un apodo del destajo o del almacén nunca se da de alta solo; uno del SUA sí, salvo que se parezca
  a otra persona (≥ 0.85).

## Siguiente: H4 · Inventario operando en preproducción (issues 21 a 31)

Almacenes, saldo y kardex, recepción con costo promedio, vale con bloqueo y reparto. Usa los
materiales, lotes y personas que dejó H3.

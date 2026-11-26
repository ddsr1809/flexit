# Estado del proyecto

Última actualización: 26 nov 2026 · Hito en curso: **H5 · Histórico del Excel migrado** (semanas 8 a 11)

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

## H4 · issues 21 a 31

| # | Issue | Estado | Dónde se comprueba |
| --- | --- | --- | --- |
| 21 | Almacenes, saldo y kardex (esquema y repositorios) | Hecho | V007, `tools/pruebas-sql/inventario.sql` |
| 22 | Recepción con costo promedio, conversión de unidad, descuento y flete | Hecho | `CosteoTest`, `CriterioH4Test` |
| 23 | Vale con bloqueo de fila y costo congelado; autorización del residente o coordinación | Hecho | `CriterioH4Test` |
| 24 | Prueba de concurrencia de 50 salidas simultáneas | Hecho | `CriterioH4Test.cincuentaSalidasSimultaneasNoDejanNegativo` |
| 25 | Reparto entre varias unidades | Hecho | `CosteoTest`, `CriterioH4Test.unBoteRepartidoEntre18LotesSumaExacto`, `reparto.test.ts` |
| 26 | Devolución de obra y devolución a proveedor | Hecho | `CriterioH4Test.devolverYCancelarRegresanExistenciaYCosto` |
| 27 | Traspaso entre almacenes y reasignación entre lotes, con autorización | Hecho | pantalla Documentos |
| 28 | Merma con foto y préstamo de herramienta | Hecho | pantalla Préstamos; `compartido.archivo` |
| 29 | Ajuste de costo por diferencia de factura | Hecho | `CosteoTest.elAjusteDeFacturaSeDivideEntreAlmacenYVales` |
| 30 | Conteo físico y ajuste; cierre de mes | Hecho | pantallas Conteo físico y Cierre de mes |
| 31 | Pantallas de almacén | Hecho | e2e `inventario.spec.ts`, `Buscador.test.tsx` |

### Qué ya se verificó y qué no

- **Verificado:**
  - V007 y 29 pruebas SQL nuevas contra PostgreSQL 16: existencia nunca negativa, kardex que ni
    el dueño de las tablas modifica, salida sin contrato, lote de otro contrato, quien captura no
    autoriza, documento aplicado inmutable, mes cerrado, conteo, merma sin foto y RLS.
  - El dominio de costeo (Java puro) con 15 pruebas.
  - **Los servicios reales contra PostgreSQL**, sin Spring, con un JdbcClient mínimo sobre JDBC
    (fuera del repositorio): 18 escenarios, de la recepción al cierre de mes. Incluye 60 vales
    de 1 saco contra 50 sacos en hilos simultáneos (pool de 20 conexiones): aplican 50, se
    rechazan 10, la existencia queda en 0 y el kardex suma 0, en tres corridas. Al final, para
    cada material y almacén, existencia = Σ kardex y valor = Σ importes.
  - El código compilado contra firmas de las APIs de Spring y con google-java-format 1.28.
  - Frontend: tipos, lint, 34 pruebas y build. Pantallas en Chrome contra una API simulada, en
    escritorio y a 412 px.
- **Se verifica por primera vez en CI:** `CriterioH4Test` por HTTP real, con Spring
  (`@PreAuthorize`, transacciones y el pool de Hikari), `ModularidadTest` con las APIs nuevas, el
  formato con spotless y el e2e `inventario.spec.ts`. `frontend/openapi.json` se generó desde los
  records de Java con las convenciones de springdoc: correr `make api` y revisar el diff.
- **Criterio de aceptación de H4 pendiente de demostrar en preproducción:** el almacenista captura
  una semana real del Excel (unos 500 movimientos) en menos de 4 horas.

### Decisiones de esta etapa

- **Un almacén por frente** (CORCELES, PALERMO, GRANADA). Un vale solo va a lotes del frente
  de su almacén. Si el material está en otro almacén, se traspasa primero.
- **El vale lo autoriza el residente del contrato o coordinación** (permiso nuevo
  `vale:autorizar`), en grupo desde la bandeja. Traspaso, reasignación, merma y ajuste de costo
  los autoriza coordinación o dirección. Nadie autoriza lo que capturó (regla 5, también en la
  base).
- **Ajuste de costo por factura:** lo que queda en el almacén ajusta el promedio; lo que ya salió
  se carga como ajuste a los lotes de los vales posteriores a la recepción, en proporción a lo que
  cada uno conserva (ADR-010).
- Valuación con valor y existencia en el saldo; el costo de obra en `inventario.cargo`. Es la
  fuente de los reportes de H6 (ADR-010).
- Préstamo: aparta existencia (`saldo.prestado`). Una pérdida crea una merma que coordinación
  autoriza.
- Conteo a ciegas: quien cuenta no ve la existencia del sistema. Lo cierra administración, y no
  quien lo abrió. Mientras está abierto, el almacén no aplica movimientos.
- Cierre de mes en orden, sin documentos pendientes del mes. No se reabre desde la app.
- Fotos de merma en `compartido.archivo` (base de datos), detrás de `compartido.Almacenamiento`.
  Pasan a S3 en H7.
- Cancelar un vale o una recepción aplicados crea su inverso (devolución o devolución al
  proveedor). Los demás documentos aplicados se corrigen a mano con su inverso.
- Rendimiento del frontend: cada pantalla se carga bajo demanda (`lazyRouteComponent`). El JS
  inicial baja de 590 a 440 kB (de 180 a 140 kB comprimido).

## H5 · issues 46 a 51

| # | Issue | Estado | Dónde se comprueba |
| --- | --- | --- | --- |
| 46 | Esquema `historico` con RLS y la vista de costo que une histórico y vivo | Hecho | V008, `tools/pruebas-sql/historico.sql` |
| 47 | Histórico de entradas y valuación ponderada sin IVA, con precios estimados | Hecho | `ImportadoresHistoricoTest`, `CriterioH5Test` |
| 48 | Histórico de salidas: reparto de lotes, "Sin asignar", devoluciones, reasignaciones | Hecho | `ImportadoresHistoricoTest`, `CriterioH5Test` |
| 49 | Histórico de Semanal, faltantes diarios y préstamos | Hecho | `ImportadoresHistoricoTest` |
| 50 | Histórico del SUA: altas, asistencia diaria, asignaciones, doble asistencia | Hecho | `ImportadoresHistoricoTest`, `CriterioH5Test` |
| 51 | Histórico de destajo: pagos parciales, montos compartidos, "sobre importe" | Hecho | `ImportadoresHistoricoTest`, `CriterioH5Test` |
| — | Conciliación de totales y la historia del lote (demo) | Hecho | pantalla de la carga, *Contratos › lote* |
| — | Carga de los Excel en preproducción y validación con administración | Pendiente: lo hace administración | `docs/runbooks/migracion-historico.md` |

### Qué ya se verificó y qué no

- **Verificado:**
  - V008 y 21 pruebas SQL nuevas contra PostgreSQL 16: histórico inmutable incluso para el dueño,
    solo la reversa borra (y solo lo de su empresa, nunca sin empresa), FK compuestas (lote de otro
    contrato, carga o persona de otra empresa), cuarentena que no entra, vistas de costo con el RLS
    de quien consulta.
  - El dominio (Java puro): 9 pruebas nuevas sobre las muestras `muestra-historico-*.xlsx`, una por
    regla; las 24 de H3 siguen en verde.
  - **El orden de carga completo (H3 + H5) con los tres Excel reales, por los servicios reales
    contra PostgreSQL** como rol `app` con RLS, sin Spring (un JdbcClient mínimo sobre JDBC, fuera
    del repositorio): 15 cargas, más de 13,000 renglones de histórico, todas las conciliaciones de
    totales en 0, la demo del lote, permisos que ocultan importes y datos personales, la reversa
    que respeta dependencias y el archivo que se vuelve a subir. Resultados en el runbook.
  - El escenario de `CriterioH5Test` corrido por el servicio real contra PostgreSQL da los mismos
    conteos que la prueba espera.
  - El código compilado contra firmas de las APIs de Spring y con google-java-format 1.28.
  - Frontend: tipos, lint, 40 pruebas (6 nuevas) y build.
- **Se verifica por primera vez en CI:** `CriterioH5Test` por HTTP real con Spring,
  `ModularidadTest` con el módulo nuevo `historico`, spotless y los e2e existentes.
  `frontend/openapi.json` se escribió a mano con las convenciones de springdoc: correr `make api`
  y revisar el diff.
- **Criterio de aceptación de H5 pendiente de demostrar en preproducción:** cargar los Excel,
  resolver la cuarentena (unas 120 decisiones), verificar 30 renglones al azar y la demo del lote
  Mza 16 Lote 34.

### Decisiones de esta etapa

- **El histórico vive aparte del kardex**, en el esquema `historico` y su módulo; los reportes
  suman histórico y vivo por `reportes.v_costo_unidad` (ADR-011).
- **Valuación del histórico: ponderado acumulado** de las entradas hasta la fecha de la salida,
  sin IVA. Antes de la primera entrada o sin entradas, costo estimado (ADR-011).
- **"Sin asignar" es la salida sin lote** (`unidad_obra_id` nulo), con el contrato si la manzana es
  de uno solo. Las salidas al propio almacén quedan sin frente.
- **Una decisión de lote vale para el grupo**: todos los renglones con el mismo frente, manzana y
  lote escritos igual (en el destajo, toda la columna).
- **Paquetería y solicitantes del almacén no bloquean**: advertencias que se ligan con alias
  (`paquete` es una entidad de alias nueva). Trabajadores del destajo y personas del SUA sí.
- **Una carga vigente por tipo de histórico** (el SUA, por mes). Una carga que el histórico usa no
  se revierte mientras siga confirmado lo que depende de ella.
- El SUA se sube con su mes y el destajo con el año de sus semanas; el mes del SUA tiene que
  coincidir con el de sus hojas.
- Se migran también los vales no entregados (153), marcados: no suman al costo.
- Además de las reglas de transformación del plan, dos que salieron de los Excel reales: las
  cantidades de Salidas capturadas como fórmula (`=1/3`, 413 renglones) se evalúan igual que los
  pagos del destajo, y un renglón de subtotales `=SUM(…)` en el destajo no es un pago.
- La carga sigue en la misma petición: Salidas valida en 1.8 s y confirma en 1.9 s.

## Siguiente: H6 · Abastecimiento y reportes (issues 32 a 38 y 52)

Requisición, pedido y recepción contra pedido; reportes de costo por contrato, lote, paquete y
clasificación sobre `reportes.v_costo_unidad` (histórico + vivo).

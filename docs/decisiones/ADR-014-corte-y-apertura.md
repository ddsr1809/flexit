# ADR-014 · Corte del Excel y apertura del almacén

**Contexto.** En H8 el frente piloto deja de capturar en Excel. El kardex del sistema tiene que
arrancar de un saldo confiable: el Excel tiene existencias negativas y salidas sin lote, así que el
histórico (ADR-011) no se reproduce en el kardex. El plan pide: Excel congelado, carga final con el
mismo motor, préstamos abiertos como préstamos vivos, conteo físico, saldo de apertura valuado y
firmado por coordinación y administración, y un plan de reversa.

Decisiones de la constructora (27 nov 2026):

- Cada frente tiene su bodega: se cuenta solo la del frente piloto.
- La herramienta prestada que no es material del catálogo se da de alta como material con
  categoría Herramienta.
- El costo de apertura es el ponderado sin IVA de las entradas del Excel hasta la fecha del corte;
  si no hay, el precio de referencia; si tampoco, lo captura administración.

**Decisión.**

- **La apertura es un documento propio** (`inventario.apertura`, folio `APE`), uno vigente por
  almacén. Junta el conteo físico del almacén vacío, los préstamos abiertos al corte, los costos
  capturados y las dos firmas. Solo nace si el almacén no tiene kardex, y mientras no se aplica el
  almacén no aplica ningún otro movimiento (trigger).
- **El saldo entra con un movimiento nuevo, `apertura`** (folio `SAP`), que ServicioAplicacion
  aplica igual que un sobrante de conteo: lo contado más lo prestado, al costo firmado. Se prefirió
  a reutilizar `ajuste_entrada` para que el kardex y los reportes distingan el saldo con que arrancó
  el almacén de un sobrante de un conteo posterior. Solo lo genera la apertura con sus dos firmas.
- **Conteo a ciegas con el catálogo completo.** El conteo de la apertura es un conteo normal de H4
  (se captura en la misma pantalla), pero se cierra con las firmas y se cancela con la apertura;
  nunca genera ajustes. Lo que no se encuentra se marca en 0 con un botón, de forma explícita.
- **Lo prestado es del almacén aunque no esté en el anaquel.** Cada préstamo abierto del Excel
  (`historico.prestamo` del frente, `devuelto = false`) se trae a la apertura; administración lo
  deja *listo* (material y responsable) o *descartado* (con motivo). Al aplicar, la existencia es
  contado + prestado y cada préstamo listo se vuelve un `inventario.prestamo` vivo que aparta lo
  prestado. La liga con el histórico es por id sin FK: el inventario no depende de una carga que se
  puede revertir antes del corte.
- **Dos firmas, la primera congela.** Firman coordinación (`documento:autorizar`) y administración
  (`sistema:administrar`), dos personas distintas y ninguna de las dos abrió el conteo ni capturó
  lo contado (regla 5, también en la base). Con la primera firma se escribe lo que se firma en
  `inventario.apertura_linea` y ya no cambian el conteo, los préstamos ni los costos; para corregir,
  se retiran las firmas. Con la segunda se aplica todo en una transacción: movimiento, cierre del
  conteo y préstamos vivos.
- **Se firma lo que se vio.** Cada firma manda la huella (SHA-256) de lo que muestra la pantalla:
  fecha, por material lo contado, lo prestado, el costo y su origen, y cada préstamo listo. Si algo
  cambió entre que se revisó y se firmó, la firma se rechaza (`APERTURA_CAMBIO`).
- **La diferencia contra el Excel es una referencia.** Se agrega una carga, *Histórico: foto de
  Existencias* (`HIST_EXISTENCIAS` → `historico.saldo_excel`), que guarda solo lo capturado
  (Inventario inicial, Material defectuoso, Apartados). La existencia del Excel se recalcula como la
  hoja (`J = O + M − N`): inventario inicial + entradas − salidas entregadas del histórico. Es de
  todo el almacén del Excel (no tiene frente), así que solo orienta. El conteo es el que vale y la
  diferencia no se busca a quién cargarla. A quien cuenta no se le muestra mientras cuenta.
- **Excel congelado y carga final.** `ensayo.py congelar` guarda el sha256 de los tres Excel;
  `ensayo.py corte` hace la carga final con las decisiones del ensayo solo si los archivos son los
  congelados, y no se borra. Las 16 cargas, en el orden de siempre.
- **Plan de reversa sin tocar la base.** `GET /almacenes/{id}/salidas.xlsx` escribe los cargos a
  obra del sistema (vales, devoluciones en negativo, reasignaciones como dos renglones) con las
  columnas A a O de la hoja Salidas, con Entregado como VERDADERO de Excel, para pegarlos en el
  Excel si hubiera que regresar.

**Consecuencias.**

- El inventario depende ahora de `historico` (solo `ConsultaHistorico`: préstamos abiertos, costo
  ponderado al corte y existencia del Excel). No hay ciclo: `historico` no depende de `inventario`.
- La carga de la foto de Existencias se puede repetir en el corte como cualquier histórico
  (revertir y volver a cargar); la apertura la lee al calcular y la congela al firmar.
- Para cada frente restante se repite la apertura de su almacén; los préstamos y la foto ya están
  cargados.
- Un almacén abierto no se vuelve a abrir: lo que se encuentre después se corrige con un conteo
  físico normal (H4).

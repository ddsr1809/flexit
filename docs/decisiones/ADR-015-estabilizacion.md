# ADR-015 · Estabilización del piloto: incidentes, criterio de H9 y primer cierre de mes

**Contexto.** H9 confirma que el sistema reemplaza al Excel en el frente piloto. Su criterio de
aceptación es: dos semanas seguidas sin capturar en Excel, cero incidentes críticos abiertos y el
primer cierre de mes hecho. Hasta H8 no había dónde registrar un incidente ni cómo medir el uso del
almacén, y el cierre de mes (H4) solo revisaba los documentos sin aplicar: un conteo o una apertura
abiertos con fecha en el mes quedaban, después del cierre, sin poder cerrarse ni aplicarse (el
kardex no acepta fechas de un mes cerrado).

Decisiones de la constructora (28 nov 2026):

- Los incidentes se registran **en el sistema**, no solo en GitHub: el almacén no tiene cuenta de
  GitHub. El responsable técnico pasa a GitHub lo que sea un error.
- Una **captura tardía** es un documento capturado **más de 1 día** después de su fecha.
- Los préstamos vencidos y las requisiciones autorizadas sin surtir **solo avisan** al cerrar el
  mes; no lo impiden.

**Decisión.**

- **Módulo nuevo `soporte`** (esquema `soporte`, V012) con `incidente` y su seguimiento
  (`incidente_nota`, solo se agrega). Cualquier usuario reporta; la pantalla y el `X-Request-Id`
  de la última petición que falló se mandan solos. Lo atiende quien tiene el permiso nuevo
  `incidente:atender` (administración): cambia la severidad, lo liga a un issue de GitHub, lo
  resuelve o lo descarta, siempre con texto. Quien lo reportó ve los suyos, agrega notas y lo
  reabre. Lo reportado no cambia; un incidente no se borra.
- **Severidad.** *Crítica*: el almacén no puede registrar entradas o salidas, o el sistema dejó mal
  una existencia, un costo o un permiso. *Mayor*: se sigue con un rodeo, o un dato importante se ve
  mal. *Menor*: lo demás. Quien reporta propone; quien atiende decide.
- **Reporte de estabilización** (`GET /reportes/estabilizacion`, coordinación, dirección y
  administración; sin costos): por almacén, día por día desde la apertura, documentos, vales,
  recepciones, capturas tardías y cancelados; lo que sigue sin aplicar con su antigüedad;
  préstamos, conteos, mediana de horas de captura a aplicación del vale e incidentes.
- **El criterio de H9 se evalúa así** (`CriterioEstabilizacion`, Java puro):
  - *Dos semanas a tiempo*: los últimos 14 días seguidos sin capturas tardías, con al menos un
    vale aplicado. Un día sin documentos (domingo) no rompe la racha; uno con una captura tardía,
    sí. El sistema no ve el Excel: que nadie lo volvió a guardar lo prueba `ensayo.py verificar`
    con el sha256 congelado en el corte.
  - *Cero críticos abiertos*: de toda la empresa, aunque no sean de un almacén.
  - *Primer cierre*: un mes cerrado igual o posterior al mes de la apertura.
- **Cierre de mes.** Lo impiden, además de lo de H4, una apertura sin aplicar o un conteo abierto
  con fecha hasta el fin del mes. Se revisa en la aplicación (`GET /periodos/{a}/{m}/revision`) y
  se garantiza en la base (trigger en `inventario.periodo`). La revisión también avisa, sin
  impedir: préstamos que debían regresar en el mes, recepciones sin folio de factura (su ajuste de
  costo ya entraría en el mes siguiente) y lo que avisen otros módulos por `AvisosDeCierre`
  (el abastecimiento: requisiciones autorizadas sin surtir y sin autorizar). La interfaz vive en
  el inventario y la implementa el abastecimiento, así el inventario no depende de él.
- **Acta del cierre** en Excel (`GET /periodos/{a}/{m}/acta.xlsx`): por almacén, valor inicial +
  movimientos del mes = valor al cierre (la diferencia debe ser 0), la foto del inventario y los
  movimientos del mes por tipo. Sin permiso de costos, solo cantidades.

**Consecuencias.**

- El incidente es el registro de lo que pasó en el piloto; el issue de GitHub, el de lo que se
  corrige. Un incidente se resuelve cuando la corrección está en producción, no al fusionar el PR.
- El reporte de estabilización sirve igual para cada frente que arranque después (H8 y H9 se
  repiten por frente).
- Un cierre que antes pasaba con un conteo abierto ahora se rechaza con `MES_CON_CONTEO_ABIERTO` o
  `MES_CON_APERTURA_ABIERTA`. No hay datos en producción que lo cambien: el primer cierre es el del
  piloto.

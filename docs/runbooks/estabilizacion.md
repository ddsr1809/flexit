# Estabilización del frente piloto (H9 · semanas 16 y 17)

Las dos semanas después del arranque. El objetivo es confirmar que el sistema reemplaza al Excel
sin regresar a él. Decisiones en ADR-015.

**Criterio para cerrar H9** (pantalla *Estabilización*, arriba):

1. Dos semanas seguidas capturando a tiempo: 14 días sin documentos capturados más de 1 día después
   de su fecha, con vales aplicados. Y `ensayo.py verificar` dice que nadie guardó el Excel.
2. Cero incidentes críticos abiertos.
3. El primer cierre de mes hecho (`cierre-de-mes.md`).

## Rutina diaria

| Hora | Quién | Qué |
| --- | --- | --- |
| 7:00 | Responsable técnico | Revisar Uptime Kuma y la alerta del respaldo de la noche (`monitoreo.md`). Abrir *Incidentes* con el filtro *Abierto*. |
| 7:30–9:00 | Responsable técnico, en el almacén | Acompañar la entrega de la mañana: es cuando más vales salen. Lo que falle se reporta con *Reportar un problema* en el momento, desde la pantalla donde pasó. |
| 12:00 | Coordinación | *Autorizaciones*: que no queden vales del día sin autorizar. |
| 17:00 | Administración | *Estabilización*: capturas tardías del día y lo que sigue sin aplicar. Cada tardío se pregunta: ¿se entregó en papel? ¿por qué? |
| 18:00 | Responsable técnico | Clasificar los incidentes nuevos, ligarlos a un issue de GitHub (milestone *H9 · Estabilización*) y anotar qué sigue. Mandar el resumen del día al grupo. |

Cada lunes, administración corre `ensayo.py verificar` en la laptop donde está la copia congelada:

```bash
python3 tools/ensayo/ensayo.py verificar --congelado corte/congelado.json \
    --almacen "Control de almacén.xlsx"
```

Sale con 0 si el archivo es el congelado; con 3 si alguien lo guardó después del corte. En ese
caso se abre el archivo y se pregunta qué se capturó ahí; no se borra.

## Incidentes

| Severidad | Cuándo | Tiempo de respuesta |
| --- | --- | --- |
| Crítica | El almacén no puede registrar entradas o salidas, o quedó mal una existencia, un costo o un permiso | Atención en la primera hora; si pasan 2 horas sin registrar salidas, se aplica el plan de reversa de `corte.md` |
| Mayor | Se sigue con un rodeo, o un dato importante se ve mal | Corrección en el siguiente despliegue (a más tardar 2 días) |
| Menor | Un texto, un acomodo, algo que estorba | Se junta para después de H9 |

- Quien reporta propone la severidad; el responsable técnico la confirma o la cambia, con nota.
- Un error de código se pasa a GitHub con la plantilla *Error*, la etiqueta `severidad:critica`,
  `severidad:mayor` o `severidad:menor` y el número del incidente. El número del issue se anota en
  el incidente (*Issue en GitHub*).
- Un incidente se **resuelve** cuando la corrección ya está en producción y quien lo reportó la
  vio; la resolución dice la versión. Se **descarta** si no era un error (se explica cómo se hace)
  o si está repetido (se dice cuál).
- Si sigue pasando, quien lo reportó lo **reabre** con una nota. Un crítico reabierto vuelve a
  detener el criterio.
- El `X-Request-Id` del incidente se busca en los logs: `docker compose logs app | grep <id>`
  (`incidente.md`).

## Correcciones durante el piloto

- Van por PR a `development` → `testing` → `main` como siempre, con el issue del incidente.
- Se despliega en la ventana acordada (`desplegar.md`), nunca en la entrega de la mañana.
- Una corrección que toca costeo, existencias, permisos o migraciones se prueba primero en
  preproducción con una copia de lo que pasó (sin datos personales).
- Si hace falta corregir un dato (un vale mal capturado), se corrige con un documento: cancelar y
  volver a capturar, devolución o reasignación. Nunca con SQL en la base.

## Informe de cierre de H9

Lo arma el responsable técnico al cumplirse el criterio y lo firma dirección. Fuente: la pantalla
*Estabilización* (con *Descargar Excel*) del almacén piloto, de la apertura a hoy.

1. Periodo: fecha de apertura y fecha del informe.
2. Criterio: racha de días a tiempo, críticos abiertos (0) y mes cerrado, con la salida de
   `ensayo.py verificar`.
3. Uso: vales y recepciones aplicados, capturas tardías (número y porcentaje), cancelados
   (porcentaje), mediana de horas de captura a aplicación del vale, préstamos vencidos.
4. Incidentes: reportados, resueltos, descartados, abiertos por severidad y mediana de horas a
   resolución; la lista de los críticos con su resolución.
5. Primer cierre: el acta del mes (`acta.xlsx`), con la diferencia en 0 por almacén.
6. Demo del hito: el reporte de costo del frente piloto con histórico y sistema (*Reportes* →
   *Costo por lote* del contrato del piloto), en pantalla, sin una celda de Excel.
7. Lo que queda: incidentes mayores y menores abiertos, y qué se corrige antes del siguiente frente.

## Siguiente frente

Después de H9 se repiten H8 y H9 por frente. La migración ya cubre los tres frentes: en cada
arranque solo hace falta congelar, la carga final con `ensayo.py corte`, el conteo físico, la
apertura con sus dos firmas y estas dos semanas de estabilización. El reporte de estabilización se
elige por almacén.

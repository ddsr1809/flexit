# Ensayo general de la migración (H7 · issue 53)

Se corre la migración completa (las 16 cargas de H3, H5 y la foto de Existencias de H8) en
**producción**, se revisa la conciliación y se borra. Así se mide cuánto tardará la carga del corte
(H8) y se prueba el motor en el servidor real, sin que el resultado se quede.

**Criterio:** de punta a punta en menos de 1 hora, con la conciliación limpia (cada carga sin
renglones en cuarentena, diferencia 0 por hoja y, en el histórico, todos los conceptos en 0).

## Antes

- Producción en la versión que se va a usar en el corte, con respaldos funcionando
  (`produccion.md`, `respaldos.md`).
- En preproducción, la migración de H3 y H5 confirmada y validada por administración: ahí se
  tomaron las decisiones de la cuarentena (unas 120).
- Los tres Excel, los mismos que se cargaron en preproducción, en la laptop que corre el ensayo.
  **Nunca en el repositorio.**
- El usuario de administración que corre el ensayo, con su segundo factor a la mano.
- Python 3.10 o más reciente en esa laptop (no necesita instalar nada más).

## Pasos

```bash
# 1. Decisiones de preproducción, por nombre (los ids cambian de una base a otra)
python3 tools/ensayo/ensayo.py exportar --url https://preprod.constructora.mx \
  --usuario admin --salida "$HOME/ensayo/decisiones.json"

# 2. Respaldo de producción antes del ensayo (en el servidor; anotar el id que imprime)
ssh deploy@constructora-prod /opt/constructora/respaldo.sh ensayo

# 3. Ensayo (en la laptop); toma el tiempo de punta a punta
python3 tools/ensayo/ensayo.py correr --url https://sistema.constructora.mx --usuario admin \
  --decisiones "$HOME/ensayo/decisiones.json" \
  --almacen "$HOME/ensayo/Control de almacén.xlsx" \
  --sua "$HOME/ensayo/SUA RM ALEMAN OCTUBRE 2026.xlsx" \
  --destajo "$HOME/ensayo/Destajo Nomina RM Aleman.xlsx" \
  --mes 2026-10 --anio 2026 --salida "$HOME/ensayo/resultado"
```

`correr` sube cada carga en orden, repite las decisiones de preproducción (las de cada renglón y
los alias), revisa la conciliación y confirma. Si una carga no queda limpia, **se detiene** y dice
qué falta: se resuelve en *Migración* (es lo que también pasará en el corte si el Excel cambió) y
se sigue con `--continuar`. Un renglón que cambió en el Excel no se decide solo: su contenido ya no
es el que vio administración.

El informe queda en `resultado/ensayo.md` (y `ensayo.json`): tiempo total, y por carga renglones,
cuarentena al subir, decisiones repetidas, pendientes y segundos de subir, decidir y confirmar.

4. Revisión con administración (15 minutos): abrir *Contratos › el lote M16-L34 de Palermo* en
   producción y compararlo con preproducción (vales, pagos de destajo, altas), y la conciliación de
   un contrato contra la hoja de su frente en *Reportes*.

## Borrado

Producción vuelve exactamente al estado de antes del ensayo restaurando el respaldo del paso 2.
Esto, además, demuestra el otro criterio de H7 (restaurar en menos de 2 horas):

```bash
ssh deploy@constructora-prod
cd /opt/constructora
time ./restaurar.sh en-sitio <id-del-respaldo-ensayo> --confirmo
```

Comprobar después que *Migración* no tiene cargas y que el respaldo `antes-de-restaurar` que se
acaba de crear existe (`./restaurar.sh listar`): guarda el ensayo completo por si hiciera falta
revisarlo.

Si por alguna razón no se puede restaurar, `ensayo.py revertir` revierte todas las cargas por la
API (de la más reciente a la más antigua); deja en *Migración* la bitácora de cargas revertidas.

## Qué se anota (para H8)

| Dato | De dónde |
| --- | --- |
| Tiempo total y por carga | `ensayo.md` |
| Decisiones nuevas que hubo que tomar | Las cargas que se detuvieron |
| Tiempo de la restauración | `restauraciones.log` (columna de segundos) |
| Problemas del Excel que conviene corregir antes del corte | La cuarentena que no se repitió sola |

El ensayo se puede repetir las veces que haga falta antes del corte; cada vez se borra igual. El
corte usa la misma herramienta con `congelar` y `corte`, y no se borra (`corte.md`).

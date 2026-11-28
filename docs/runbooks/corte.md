# Corte y arranque del frente piloto (H8 · issues 41 y 54)

El fin de semana del corte el frente piloto deja de capturar en Excel: se congela el Excel, se
hace la carga final, se cuenta la bodega y su saldo de apertura queda firmado. El lunes el almacén
captura todo en el sistema.

**Criterio de aceptación:** saldo de apertura firmado por coordinación y administración; los
préstamos sin devolver del frente aparecen como abiertos con su responsable; el primer día se
capturan todas las salidas en el sistema. **Demo:** el primer vale real del frente piloto, impreso
y firmado. **Acepta:** dirección.

## Antes (la semana del corte)

- [ ] Ensayo general hecho y borrado (`ensayo-general.md`), con el tiempo anotado.
- [ ] `decisiones.json` exportado de preproducción **después** de resolver ahí la cuarentena de la
      foto de Existencias (carga 16) con el Excel más reciente.
- [ ] Producción en la versión del corte, respaldos y monitoreo en verde (`produccion.md`).
- [ ] Usuarios dados de alta con su rol: el almacenista del piloto, el residente de cada contrato,
      coordinación (firma), administración (firma; quien firma no captura nada en el conteo) y
      dirección. Administración y dirección con su segundo factor.
- [ ] Capacitación dada (`capacitacion.md`) y el ejercicio completo hecho en preproducción.
- [ ] Vales de papel con folio preimpreso en el almacén, por si falla el sistema el lunes.
- [ ] Decidido por escrito: **el conteo físico es el que vale**; la diferencia contra el Excel se
      registra como ajuste de apertura, sin buscar responsables. Criterio para seguir o regresar
      (abajo).

## Secuencia

| Cuándo | Paso | Quién | Cómo |
| --- | --- | --- | --- |
| Viernes 14:00 | Aviso: desde el lunes se captura en el sistema | Dirección | — |
| Viernes 18:00 | Excel congelado | Administración | Copia de los tres Excel a la laptop del corte; `ensayo.py congelar` (abajo) y anotar los sha256 en el acta. El original pasa a solo lectura |
| Viernes 18:30 | Respaldo antes de la carga | Responsable técnico | `ssh deploy@constructora-prod /opt/constructora/respaldo.sh antes-del-corte` |
| Viernes 19:00 | Carga final | Responsable técnico | `ensayo.py corte` (abajo): las 16 cargas con las decisiones del ensayo |
| Viernes 21:00 | Conciliación y cuarentena | Administración | En *Migración*, lo que el corte dejó detenido; seguir con `--continuar` |
| Sábado 7:00 | Abrir la apertura y contar | Almacén, con un testigo de administración | *Apertura* → Abrir el almacén del piloto. Contar en *Conteo físico* |
| Sábado 12:00 | Préstamos y costos | Administración | En la apertura: resolver cada préstamo, capturar los costos que falten |
| Sábado 14:00 | Primera firma | Coordinación | *Apertura* → Firmar (congela) |
| Sábado 17:00 | Segunda firma: se aplica | Administración (quien no contó) | *Apertura* → Firmar y aplicar. Descargar el acta e imprimirla para firma |
| Domingo | Respaldo y prueba de restauración; usuarios y permisos | Responsable técnico | `respaldo.sh despues-del-corte`; `restaurar.sh prueba` |
| Lunes 7:00 | Arranque: primer vale en el sistema | Todos | `operacion-almacen.md`; acompañamiento en el almacén |
| Lunes 12:00 | Seguir o regresar | Dirección | Criterio abajo |

### Congelar y cargar (laptop del corte)

```bash
mkdir -p "$HOME/corte"
python3 tools/ensayo/ensayo.py congelar \
  --almacen "$HOME/corte/Control de almacén.xlsx" \
  --sua "$HOME/corte/SUA RM ALEMAN OCTUBRE 2026.xlsx" \
  --destajo "$HOME/corte/Destajo Nomina RM Aleman.xlsx" \
  --salida "$HOME/corte/congelado.json"

python3 tools/ensayo/ensayo.py corte --url https://sistema.constructora.mx --usuario admin \
  --congelado "$HOME/corte/congelado.json" --decisiones "$HOME/corte/decisiones.json" \
  --almacen "$HOME/corte/Control de almacén.xlsx" \
  --sua "$HOME/corte/SUA RM ALEMAN OCTUBRE 2026.xlsx" \
  --destajo "$HOME/corte/Destajo Nomina RM Aleman.xlsx" \
  --mes 2026-10 --anio 2026 --salida "$HOME/corte/resultado"
```

`corte` no empieza si algún archivo no es el congelado (alguien capturó después de las 18:00) ni
sobre un entorno con cargas. El informe queda en `resultado/corte.md` con los sha256: se imprime y
va con el acta. **Ni los Excel ni `decisiones.json` van al repositorio.**

### La apertura (sábado)

1. **Abrir** (almacén): *Apertura* → almacén del piloto, fecha del corte. Se crea el conteo con
   todos los materiales activos y se traen los préstamos del Excel del frente que siguen abiertos.
   Mientras no se aplique, el almacén no mueve nada.
2. **Contar** (almacén): en el conteo, capturar lo que hay en la bodega. Lo prestado **no** se
   cuenta: está fuera del anaquel y entra por su préstamo. Al terminar, *Marcar en 0 lo no
   encontrado*. El almacén no ve costos ni lo que decía el Excel.
3. **Préstamos** (administración): en cada uno, *Resolver*:
   - *Listo*: el material (o *Dar de alta como herramienta*, con la categoría Herramienta) y quién
     la tiene. Al aplicar se vuelve un préstamo vivo.
   - *Descartar* con motivo: ya regresó, es de otro almacén, se capturó dos veces.
   - Un préstamo que no estaba en el Excel: *Agregar uno que no estaba*.
4. **Costos** (administración): filtro *Sin costo*. El sistema propone el ponderado sin IVA de las
   entradas del Excel hasta el corte o el precio de referencia; la herramienta dada de alta no
   tiene ninguno y se captura (puede ser 0). Cualquier costo propuesto se puede corregir.
5. **Revisar contra el Excel**: filtro *Con diferencia contra el Excel*. Es una referencia (el
   Excel no tiene frente); el conteo es el que vale.
6. **Firmar**: coordinación y después administración. Ninguna de las dos personas puede haber
   abierto el conteo ni capturado lo contado. La primera firma congela; si hay que corregir,
   *Retirar firmas*. La segunda aplica: el saldo entra al kardex como *Saldo de apertura* (SAP),
   el conteo se cierra y los préstamos quedan vivos.
7. **Acta**: *Acta en Excel* (firmas, materiales valuados, préstamos y la huella de lo firmado). Se
   imprime y la firman coordinación y administración.

## Seguir o regresar (lunes 12:00)

**Se regresa** si el almacén no pudo registrar salidas durante más de 2 horas seguidas por una
falla del sistema. Si no, se sigue.

| Situación | Acción |
| --- | --- |
| Falla antes del lunes | No se arranca: el Excel se descongela y se reprograma el corte una semana. Si la apertura ya se aplicó, restaurar el respaldo `antes-del-corte` (`restaurar.md`) |
| Falla el lunes | Vales de papel con folio preimpreso; se corrige o se revierte la imagen (`revertir.md`); los vales se capturan al volver |
| Falla grave en la primera semana | El Excel se descongela para el piloto. *Apertura* → *Salidas en Excel* (o `GET /api/v1/almacenes/{id}/salidas.xlsx?desde=…&hasta=…`) da lo capturado en el sistema con las columnas de la hoja Salidas (A a O): se pega al final de la hoja. Se analiza y se vuelve a intentar |
| Después de 30 días | El Excel se archiva en solo lectura; ya no hay reversa al Excel |

La reversa no deshace la base: el histórico migrado sigue valiendo y el siguiente intento solo
repite la carga final y la apertura (cancelar la apertura si no se aplicó; si se aplicó, restaurar
el respaldo `antes-del-corte`).

## Qué queda para el informe del corte

| Dato | De dónde |
| --- | --- |
| sha256 de los Excel congelados | `resultado/corte.md` |
| Tiempo de la carga final y lo que se detuvo | `resultado/corte.md` |
| Materiales contados, con existencia y valor del saldo de apertura | Acta, hoja *Apertura* |
| Diferencias contra el Excel | Acta, columnas *Excel* y *Diferencia* |
| Préstamos vivos y descartados (con motivo) | Acta, hoja *Préstamos* |
| Salidas del primer día capturadas en el sistema | *Documentos*, filtro por fecha |
| Primer vale impreso y firmado | Archivo del almacén |

## Frentes siguientes

Se repite por frente: congelar el Excel, revertir y volver a cargar solo las cargas del histórico
que cambiaron (salidas, entradas, préstamos, foto de Existencias; los catálogos ya los usa el
sistema y no se revierten) y la apertura de su almacén, que trae solo los préstamos de su frente.

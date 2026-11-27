# Restaurar

Objetivo de servicio: **menos de 2 horas** desde que se decide restaurar hasta que la app responde
(criterio de H7). Pérdida máxima de datos: 24 horas (el respaldo diario), menos con los respaldos
`antes-de-desplegar`.

## Prueba mensual (automática)

El primer domingo de cada mes a las 04:00, `restaurar.sh prueba latest`:

1. Baja el último respaldo de restic.
2. Levanta una base PostgreSQL temporal en una red de Docker aislada, sin salida a internet.
3. Crea los roles con contraseñas de un solo uso, restaura el volcado y compara el conteo de
   renglones de cada tabla con el del respaldo. Si no es idéntico, la prueba falla.
4. Aplica las migraciones de la versión que corre hoy y arranca la app contra esa base hasta que
   está lista.
5. Borra todo. Los datos nunca salen del servidor de producción (ADR-013).

El resultado queda en `/opt/constructora/restauraciones.log` (fecha, resultado, segundos, tablas,
renglones, versión). Si falla, o si pasan 36 días sin una prueba correcta, Uptime Kuma avisa.

Para correrla a mano (tarda unos minutos):

```bash
/opt/constructora/restaurar.sh prueba            # el más reciente
/opt/constructora/restaurar.sh prueba 4f2a9c1e   # uno en particular
```

## En sitio: reemplazar la base de producción

Solo con la decisión de dirección y del responsable técnico (`incidente.md`). Lo capturado después
del respaldo elegido se pierde y se vuelve a capturar desde los vales de papel.

```bash
ssh deploy@constructora-prod
cd /opt/constructora
./restaurar.sh listar                                 # elegir el respaldo
./restaurar.sh en-sitio 4f2a9c1e --confirmo
```

Qué hace: baja ese respaldo, saca otro `antes-de-restaurar` de lo que hay ahora (por si hay que
regresar), detiene la app, borra y recrea la base desde el volcado, compara conteos, aplica las
migraciones de la versión actual y arranca. Si la base actual ya no abre, agregar
`--sin-respaldo-previo`.

Después: abrir la app, revisar el último vale y la última recepción del respaldo, y avisar al
almacén desde qué fecha y hora hay que volver a capturar.

## En un servidor nuevo

`servidor-perdido.md`.

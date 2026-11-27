# Muestras anonimizadas

Solo aquí pueden vivir archivos .xlsx o .csv en el repositorio, y solo con nombres inventados
(sin trabajadores, NSS, CURP ni montos reales). Las usan las pruebas de los importadores (H3 y H5).

Se generan con `python3 tools/validacion-datos/generar_muestras.py`: reproducen la forma de los
Excel reales (hojas, filas de encabezado, fórmulas) y sus problemas conocidos (unidades escritas de
varias formas, códigos con "/", lotes ilegibles, apodos, conceptos repetidos), con datos inventados.

| Muestra | Imita | La usan |
| --- | --- | --- |
| `muestra-almacen.xlsx` | Control de almacén | frentes, materiales, proveedores, solicitantes |
| `muestra-sua.xlsx` | SUA RM Alemán | contratos, personas del SUA |
| `muestra-destajo.xlsx` | Destajo y nómina | lotes, paquetes, trabajadores |
| `muestra-lotes.xlsx` | Plantilla "Lotes" | lotes con contrato |
| `muestra-historico-almacen.xlsx` | Entradas, Salidas, Semanal, Faltantes y Préstamos | histórico de almacén (H5) |
| `muestra-historico-sua.xlsx` | IMSS, ASISTENCIA, SUA ANTERIOR y BD de un mes | histórico del SUA (H5) |
| `muestra-historico-destajo.xlsx` | Una hoja de pagos del destajo | histórico de destajo (H5) |
| `muestra-conciliacion.xlsx` | Existencias, Entradas, Salidas y la hoja de un frente con sus `SUMIFS` | conciliación del contrato contra la hoja del frente (H6) |

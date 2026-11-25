# ADR-006 · El alcance por contrato vive en estructura

**Contexto.** El plan ponía `usuario_contrato` en `organizacion`. Pero la tabla apunta a
`estructura.contrato`, y `organizacion` no puede depender de `estructura` (las dependencias van de
`estructura` hacia `organizacion`, nunca al revés).

**Decisión.** La tabla es `estructura.contrato_usuario` y la administra `ServicioContratos`. Quién
ve todo lo decide el permiso `estructura:ver-todo`, que tienen todos los roles salvo residente.
Sin él, las consultas filtran por los contratos asignados; un contrato ajeno responde 404, igual
que uno de otra empresa. Para mostrar nombres, `estructura` usa `organizacion.DirectorioUsuarios`.

**Consecuencias.** El filtro del residente vive en el código (las consultas de contratos y lotes
reciben el usuario al que se restringen), no en RLS: RLS separa empresas, el alcance separa
personas dentro de una empresa. Cuando lleguen los reportes de costo (H6), su consulta usa el
mismo filtro.

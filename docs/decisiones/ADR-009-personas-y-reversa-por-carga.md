# ADR-009 · Personas en organización y reversa por carga

**Contexto.** El plan técnico ponía `persona` en `compartido`. La migración necesita dar de alta
personas (SUA, destajo, almacén) y poder revertir una carga completa, pero la app no tiene
`DELETE` sobre estructura, catálogo ni personas ("nada se borra").

**Decisión.**

- `organizacion.persona` vive en el módulo `organizacion`: son datos personales con su permiso
  (`personas:administrar`, solo administración) y `compartido` se queda sin datos de negocio. Los
  demás módulos la usan por `organizacion.Personas`. NSS y CURP con formato validado; se cifrarán
  por columna con el módulo de personal.
- Cada registro maestro que crea una carga lleva `origen_carga`. Revertir llama a
  `estructura.revertir_carga`, `catalogo.revertir_carga` y `organizacion.revertir_carga_personas`:
  funciones `SECURITY DEFINER` que solo borran renglones de la empresa en curso con esa marca. Si
  algo ya los usa, la FK aborta todo y la carga queda como estaba (`CARGA_EN_USO`).
- Los alias decididos en una carga llevan su `carga_id`; revertirla los borra, junto con los alias
  que apuntaban a registros que la carga creó.
- Un registro que ya existía nunca se modifica desde una carga: se liga (alias) y queda "ya
  existe". Así revertir nunca tiene que restaurar valores anteriores.

**Consecuencias.** La app sigue sin poder borrar a mano; solo la reversa de una carga borra, y
queda en bitácora con el usuario. Para revertir una carga de la que dependen otras (contratos con
lotes) hay que revertir primero las posteriores.

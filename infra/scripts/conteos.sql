-- Renglones exactos de cada tabla de la aplicación, uno por línea: «esquema.tabla<TAB>renglones».
-- Lo usan respaldo.sh (dentro de la instantánea del volcado) y restaurar.sh (en la base
-- restaurada): si los dos listados no son idénticos, la restauración no se da por buena.
SELECT format('SELECT %L || chr(9) || count(*) FROM %I.%I',
              n.nspname || '.' || c.relname, n.nspname, c.relname)
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind IN ('r', 'p')
   AND n.nspname NOT IN ('pg_catalog', 'information_schema')
   AND n.nspname NOT LIKE 'pg\_toast%'
   AND n.nspname NOT LIKE 'pg\_temp%'
 ORDER BY n.nspname, c.relname
\gexec

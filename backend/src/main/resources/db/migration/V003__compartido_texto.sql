-- V003 · Normalización de texto para búsquedas y unicidad.
--
-- Un solo criterio en toda la base: sin acentos, mayúsculas y espacios simples. Es el mismo que
-- usará la migración desde Excel (H3) para empatar nombres: "Cemento  gris" y "CEMENTO GRIS" son
-- el mismo material. Se traducen las vocales acentuadas antes de upper() para no depender del
-- locale de la base (en "C", upper() no cambia letras fuera de ASCII).

CREATE FUNCTION compartido.normalizar(p_texto text) RETURNS text
  LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
  AS $$
  SELECT nullif(
    btrim(regexp_replace(
      upper(translate(p_texto,
        'áàäâãéèëêíìïîóòöôõúùüûñçÁÀÄÂÃÉÈËÊÍÌÏÎÓÒÖÔÕÚÙÜÛÑÇ',
        'aaaaaeeeeiiiiooooouuuuncAAAAAEEEEIIIIOOOOOUUUUNC')),
      '\s+', ' ', 'g')),
    '')
$$;

COMMENT ON FUNCTION compartido.normalizar(text) IS
  'Sin acentos, mayúsculas, espacios simples y sin espacios en los extremos; NULL si queda vacío.';

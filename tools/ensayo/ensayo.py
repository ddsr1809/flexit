#!/usr/bin/env python3
"""Ensayo general de la migración (H7 · issue 53). Solo biblioteca estándar de Python 3.10+.

Corre el orden de carga completo (las 15 cargas de H3 y H5) contra la API de un entorno, repite las
decisiones de la cuarentena que administración ya tomó en preproducción, revisa la conciliación de
cada carga y la confirma, midiendo cuánto tarda cada paso. Ver docs/runbooks/ensayo-general.md.

    # 1. En preproducción, con la migración ya validada por administración:
    python3 tools/ensayo/ensayo.py exportar --url https://preprod.constructora.mx \\
        --usuario admin --salida decisiones.json

    # 2. En producción, después de `respaldo.sh ensayo` en el servidor:
    python3 tools/ensayo/ensayo.py correr --url https://sistema.constructora.mx --usuario admin \\
        --decisiones decisiones.json --almacen "Control de almacén.xlsx" \\
        --sua "SUA RM ALEMAN OCTUBRE 2026.xlsx" --destajo "Destajo Nomina RM Aleman.xlsx" \\
        --mes 2026-10 --anio 2026 --salida ensayo/

    # 3. Borrado: restaurar en producción el respaldo "ensayo" (restaurar.sh en-sitio). O, si no
    #    se puede, revertir las cargas por la API:
    python3 tools/ensayo/ensayo.py revertir --url https://sistema.constructora.mx --usuario admin

La contraseña se pide en la terminal (o ENSAYO_CONTRASENA); el código del segundo factor también
(o --codigo). Los Excel y decisiones.json traen datos reales: nunca se suben al repositorio.
"""

from __future__ import annotations

import argparse
import datetime as dt
import getpass
import hashlib
import http.cookiejar
import json
import os
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

VERSION_FORMATO = 1

# El orden del centro de migración (runbooks migracion-catalogos.md y migracion-historico.md) y de
# qué archivo sale cada carga.
ORDEN: list[tuple[str, str]] = [
    ("FRENTES", "almacen"),
    ("CONTRATOS", "sua"),
    ("LOTES", "destajo"),
    ("PAQUETES", "destajo"),
    ("MATERIALES", "almacen"),
    ("PROVEEDORES", "almacen"),
    ("PERSONAS_SUA", "sua"),
    ("PERSONAS_DESTAJO", "destajo"),
    ("PERSONAS_ALMACEN", "almacen"),
    ("HIST_ENTRADAS", "almacen"),
    ("HIST_SALIDAS", "almacen"),
    ("HIST_REQUISICIONES", "almacen"),
    ("HIST_PRESTAMOS", "almacen"),
    ("HIST_SUA", "sua"),
    ("HIST_DESTAJO", "destajo"),
]

# Entidades cuyo texto en el selector es "clave · nombre": se busca por la clave.
CON_CLAVE = {"frente", "material", "unidad_medida", "paquete", "unidad_obra"}

PAGINA = 200  # máximo que acepta /importaciones/{id}/filas
LOTE_DECISIONES = 500  # la API acepta hasta 1000 por llamada


# --------------------------------------------------------------------------------------- API


class ErrorApi(Exception):
    def __init__(self, estado: int, codigo: str, detalle: str):
        super().__init__(f"{estado} {codigo}: {detalle}")
        self.estado = estado
        self.codigo = codigo
        self.detalle = detalle


class Api:
    """Cliente mínimo: cookies de sesión, token CSRF y JSON, como el frontend."""

    def __init__(self, base: str, tiempo_max: float = 300):
        self.base = base.rstrip("/")
        self.tiempo_max = tiempo_max
        self.cookies = http.cookiejar.CookieJar()
        self.abridor = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))

    def _csrf(self) -> str | None:
        for cookie in self.cookies:
            if cookie.name == "XSRF-TOKEN":
                return cookie.value
        return None

    def pedir(
        self,
        metodo: str,
        ruta: str,
        cuerpo: Any = None,
        consulta: dict[str, Any] | None = None,
        datos: bytes | None = None,
        tipo: str | None = None,
    ) -> tuple[int, Any]:
        url = self.base + ruta
        if consulta:
            limpia = {k: v for k, v in consulta.items() if v is not None}
            if limpia:
                url += "?" + urllib.parse.urlencode(limpia)
        encabezados = {"Accept": "application/json"}
        if cuerpo is not None:
            datos = json.dumps(cuerpo).encode("utf-8")
            tipo = "application/json"
        if tipo:
            encabezados["Content-Type"] = tipo
        if metodo != "GET":
            token = self._csrf()
            if token:
                encabezados["X-XSRF-TOKEN"] = token
        peticion = urllib.request.Request(url, data=datos, method=metodo, headers=encabezados)
        try:
            with self.abridor.open(peticion, timeout=self.tiempo_max) as respuesta:
                texto = respuesta.read().decode("utf-8")
                return respuesta.status, json.loads(texto) if texto else None
        except urllib.error.HTTPError as error:
            texto = error.read().decode("utf-8", "replace")
            try:
                problema = json.loads(texto)
            except ValueError:
                problema = {}
            raise ErrorApi(
                error.code,
                problema.get("codigo", "HTTP_" + str(error.code)),
                problema.get("detail") or texto[:300],
            ) from None

    def get(self, ruta: str, **consulta: Any) -> Any:
        return self.pedir("GET", ruta, consulta=consulta)[1]

    def post(self, ruta: str, cuerpo: Any = None) -> Any:
        return self.pedir("POST", ruta, cuerpo=cuerpo if cuerpo is not None else {})[1]

    def subir(self, ruta: str, campos: dict[str, Any], archivo: Path) -> tuple[int, Any]:
        limite = "----ensayo" + uuid.uuid4().hex
        partes: list[bytes] = []
        for nombre, valor in campos.items():
            if valor is None:
                continue
            partes.append(
                f'--{limite}\r\nContent-Disposition: form-data; name="{nombre}"\r\n\r\n{valor}\r\n'.encode()
            )
        nombre_archivo = archivo.name.replace('"', "")
        partes.append(
            (
                f'--{limite}\r\nContent-Disposition: form-data; name="archivo"; '
                f'filename="{nombre_archivo}"\r\nContent-Type: application/vnd.openxmlformats-'
                "officedocument.spreadsheetml.sheet\r\n\r\n"
            ).encode()
        )
        partes.append(archivo.read_bytes())
        partes.append(f"\r\n--{limite}--\r\n".encode())
        return self.pedir(
            "POST",
            ruta,
            consulta=None,
            datos=b"".join(partes),
            tipo="multipart/form-data; boundary=" + limite,
        )

    def iniciar_sesion(
        self, usuario: str, contrasena: str, pedir_codigo: Callable[[], str]
    ) -> dict[str, Any]:
        self.get("/api/v1/auth/csrf")
        estado, cuerpo = self.pedir(
            "POST", "/api/v1/auth/login", cuerpo={"usuario": usuario, "contrasena": contrasena}
        )
        if estado == 202:
            if cuerpo.get("paso") == "alta":
                raise SystemExit(
                    f"{usuario} no ha dado de alta su segundo factor: entra una vez al sistema"
                    " desde el navegador y vuelve a correr el ensayo."
                )
            return self.post("/api/v1/auth/segundo-factor", {"codigo": pedir_codigo()})
        return cuerpo


# ----------------------------------------------------------------------------- utilidades


def huella(crudo: dict[str, Any] | None) -> str:
    """Identifica un renglón por lo que trae, no por su número: si el Excel cambió, no se repite."""
    texto = json.dumps(crudo or {}, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(texto.encode("utf-8")).hexdigest()[:20]


def normal(texto: str) -> str:
    sin_acentos = unicodedata.normalize("NFKD", texto)
    sin_acentos = "".join(c for c in sin_acentos if not unicodedata.combining(c))
    return " ".join(sin_acentos.upper().split())


def todas_las_filas(api: Api, carga_id: int) -> list[dict[str, Any]]:
    filas: list[dict[str, Any]] = []
    pagina = 0
    while True:
        respuesta = api.get(f"/api/v1/importaciones/{carga_id}/filas", pagina=pagina, tamano=PAGINA)
        filas.extend(respuesta["elementos"])
        if len(respuesta["elementos"]) < PAGINA or len(filas) >= respuesta["total"]:
            return filas
        pagina += 1


def entidad_de(incidencia: dict[str, Any]) -> str | None:
    """La misma regla que el frontend (consultas.ts, entidadDe)."""
    sugerencia = incidencia.get("sugerencia") or {}
    if sugerencia.get("entidad"):
        return sugerencia["entidad"]
    if incidencia.get("regla") == "LOTE_SIN_CONTRATO":
        return "contrato"
    return None


def ahora() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def segundos(inicio: float) -> float:
    return round(time.monotonic() - inicio, 1)


# ------------------------------------------------------------------------------- exportar


def exportar(api: Api, origen: str) -> dict[str, Any]:
    """Decisiones de la cuarentena de las cargas confirmadas, por nombre y no por id."""
    alias = [
        {
            "entidad": a["entidad"],
            "texto": a["texto"],
            "destino": a["destino"],
            "origen": a["origen"],
        }
        for a in api.get("/api/v1/alias")
        if a["origen"] != "regla" and a.get("destino")
    ]
    nombres: dict[tuple[str, int], str | None] = {}

    def nombre(entidad: str, destino_id: int) -> str | None:
        clave = (entidad, destino_id)
        if clave not in nombres:
            try:
                nombres[clave] = api.get(f"/api/v1/importaciones/destinos/{entidad}/{destino_id}")[
                    "texto"
                ]
            except ErrorApi:
                nombres[clave] = None
        return nombres[clave]

    cargas = []
    decisiones = []
    sin_portar = []
    confirmadas = [c for c in api.get("/api/v1/importaciones") if c["estado"] == "confirmada"]
    for resumen in sorted(confirmadas, key=lambda c: c["id"]):
        detalle = api.get(f"/api/v1/importaciones/{resumen['id']}")
        cargas.append(
            {
                "tipo": resumen["tipo"],
                "archivo": resumen["archivo"],
                "hojas": resumen["hojas"],
                "parametros": detalle.get("parametros") or {},
                "renglones": resumen["renglones"],
            }
        )
        for fila in todas_las_filas(api, resumen["id"]):
            for incidencia in fila["incidencias"]:
                resolucion = incidencia.get("resolucion")
                if not resolucion:
                    continue
                decision = {
                    "tipo": resumen["tipo"],
                    "hoja": fila["hoja"],
                    "filaExcel": fila.get("filaExcel"),
                    "huella": huella(fila.get("crudo")),
                    "regla": incidencia["regla"],
                    "opcion": resolucion.get("opcion"),
                    "valor": resolucion.get("valor"),
                    "motivo": resolucion.get("motivo"),
                    "destino": None,
                }
                destino_id = resolucion.get("destinoId")
                if destino_id is not None:
                    entidad = entidad_de(incidencia)
                    texto = nombre(entidad, int(destino_id)) if entidad else None
                    if texto is None:
                        sin_portar.append({**decision, "motivo_sin_portar": "destino sin nombre"})
                        continue
                    decision["destino"] = {"entidad": entidad, "texto": texto}
                decisiones.append(decision)
    return {
        "formato": VERSION_FORMATO,
        "origen": origen,
        "fecha": ahora(),
        "cargas": cargas,
        "alias": alias,
        "decisiones": decisiones,
        "sin_portar": sin_portar,
    }


# --------------------------------------------------------------------------------- correr


@dataclass
class ResultadoCarga:
    tipo: str
    carga_id: int | None = None
    archivo: str = ""
    renglones: int = 0
    cuarentena_al_subir: int = 0
    decisiones_repetidas: int = 0
    decisiones_fallidas: int = 0
    pendientes: int = 0
    diferencia_hojas: int = 0
    conceptos_con_diferencia: list[str] = field(default_factory=list)
    creados: dict[str, int] = field(default_factory=dict)
    tiempos: dict[str, float] = field(default_factory=dict)
    estado: str = "pendiente"  # confirmada, ya-confirmada, detenida
    motivo: str = ""


class Resolutor:
    """Traduce el nombre de un registro de preproducción al id que tiene en este entorno."""

    def __init__(self, api: Api):
        self.api = api
        self.cache: dict[tuple[str, str], int | None] = {}

    def id_de(self, entidad: str, texto: str) -> int | None:
        clave = (entidad, texto)
        if clave in self.cache:
            return self.cache[clave]
        busqueda = texto.split(" · ")[0] if entidad in CON_CLAVE else texto
        try:
            candidatos = self.api.get("/api/v1/importaciones/destinos", entidad=entidad, q=busqueda)
        except ErrorApi:
            candidatos = []
        exactos = [c for c in candidatos if normal(c["texto"]) == normal(texto)]
        if not exactos:
            exactos = [
                c
                for c in candidatos
                if normal(c["texto"]).endswith(" · " + normal(texto))
                or normal(c["texto"]).startswith(normal(texto) + " · ")
            ]
        self.cache[clave] = exactos[0]["id"] if len(exactos) == 1 else None
        return self.cache[clave]


def proponer(
    tipo: str,
    filas: list[dict[str, Any]],
    por_renglon: dict[tuple, dict[str, Any]],
    por_alias: dict[tuple[str, str], dict[str, Any]],
    resolutor: Resolutor,
) -> list[dict[str, Any]]:
    """Decisiones para las incidencias sin resolver que preproducción ya decidió."""
    propuestas = []
    for fila in filas:
        firma = huella(fila.get("crudo"))
        for incidencia in fila["incidencias"]:
            if incidencia.get("resolucion"):
                continue
            opciones = incidencia.get("opciones") or []
            sugerencia = incidencia.get("sugerencia") or {}
            llave = (tipo, fila["hoja"], fila.get("filaExcel"), incidencia["regla"], firma)
            previa = por_renglon.get(llave)
            decision: dict[str, Any] | None = None
            if previa is not None:
                decision = {"numero": fila["numero"], "regla": incidencia["regla"]}
                opcion = previa["opcion"]
                if previa.get("destino"):
                    destino_id = resolutor.id_de(
                        previa["destino"]["entidad"], previa["destino"]["texto"]
                    )
                    if destino_id is None:
                        continue
                    if "ELEGIR" in opciones:
                        opcion = "ELEGIR"
                        decision["destinoId"] = destino_id
                    elif "ACEPTAR" in opciones and sugerencia.get("destinoId") == destino_id:
                        opcion = "ACEPTAR"
                    else:
                        continue
                for campo in ("valor", "motivo"):
                    if previa.get(campo):
                        decision[campo] = previa[campo]
                decision["opcion"] = opcion
            elif sugerencia.get("entidad") and sugerencia.get("texto"):
                alias = por_alias.get((sugerencia["entidad"], sugerencia["texto"]))
                if alias is None:
                    continue
                destino_id = resolutor.id_de(alias["entidad"], alias["destino"])
                if destino_id is None:
                    continue
                if "ELEGIR" in opciones:
                    decision = {
                        "numero": fila["numero"],
                        "regla": incidencia["regla"],
                        "opcion": "ELEGIR",
                        "destinoId": destino_id,
                    }
                elif "ACEPTAR" in opciones and sugerencia.get("destinoId") == destino_id:
                    decision = {
                        "numero": fila["numero"],
                        "regla": incidencia["regla"],
                        "opcion": "ACEPTAR",
                    }
            if decision is not None and decision["opcion"] in opciones:
                propuestas.append(decision)
    return propuestas


def aplicar(api: Api, carga_id: int, propuestas: list[dict[str, Any]]) -> tuple[int, int]:
    """Manda las decisiones en lotes; si un lote falla, las manda una por una."""
    aplicadas = fallidas = 0
    for inicio in range(0, len(propuestas), LOTE_DECISIONES):
        lote = propuestas[inicio : inicio + LOTE_DECISIONES]
        try:
            api.post(f"/api/v1/importaciones/{carga_id}/decisiones", {"decisiones": lote})
            aplicadas += len(lote)
        except ErrorApi:
            for decision in lote:
                try:
                    api.post(
                        f"/api/v1/importaciones/{carga_id}/decisiones", {"decisiones": [decision]}
                    )
                    aplicadas += 1
                except ErrorApi:
                    fallidas += 1
    return aplicadas, fallidas


def cliente_id(api: Api, nombre: str, crear: bool) -> int:
    for cliente in api.get("/api/v1/clientes"):
        if normal(cliente["nombre"]) == normal(nombre):
            return cliente["id"]
    if not crear:
        raise SystemExit(f'No existe el cliente "{nombre}". Dalo de alta o usa --crear-cliente.')
    return api.post("/api/v1/clientes", {"nombre": nombre})["id"]


def correr(
    api: Api,
    archivos: dict[str, Path],
    decisiones: dict[str, Any],
    cliente: str,
    mes: str,
    anio: int,
    continuar: bool,
    crear_cliente: bool,
    avisar: Callable[[str], None] = print,
) -> tuple[list[ResultadoCarga], float]:
    inicio_total = time.monotonic()
    orden = {t["tipo"]: t for t in api.get("/api/v1/importaciones/tipos")}
    ya = [t for t, info in orden.items() if info["confirmada"]]
    if ya and not continuar:
        raise SystemExit(
            "Este entorno ya tiene cargas confirmadas (" + ", ".join(ya) + "). El ensayo empieza"
            " de una base vacía; para seguir uno interrumpido usa --continuar."
        )
    por_renglon = {
        (d["tipo"], d["hoja"], d.get("filaExcel"), d["regla"], d["huella"]): d
        for d in decisiones.get("decisiones", [])
    }
    por_alias = {(a["entidad"], a["texto"]): a for a in decisiones.get("alias", [])}
    resolutor = Resolutor(api)
    id_cliente = cliente_id(api, cliente, crear_cliente)
    parametros = {"clienteId": id_cliente, "mes": mes, "anio": anio}

    resultados: list[ResultadoCarga] = []
    for tipo, origen in ORDEN:
        r = ResultadoCarga(tipo=tipo, archivo=archivos[origen].name)
        resultados.append(r)
        if orden.get(tipo, {}).get("confirmada"):
            r.estado = "ya-confirmada"
            avisar(f"· {tipo}: ya estaba confirmada")
            continue
        avisar(f"· {tipo}: subiendo {archivos[origen].name}")
        inicio = time.monotonic()
        necesarios = orden.get(tipo, {}).get("parametros", [])
        campos = {"tipo": tipo, **{p: parametros[p] for p in necesarios}}
        _, resumen = api.subir("/api/v1/importaciones", campos, archivos[origen])
        r.tiempos["subir"] = segundos(inicio)
        r.carga_id = resumen["id"]
        r.renglones = resumen["renglones"]
        r.cuarentena_al_subir = resumen["enCuarentena"]
        if resumen["estado"] == "confirmada":
            r.estado = "ya-confirmada"
            continue

        inicio = time.monotonic()
        for _ in range(6):
            propuestas = proponer(
                tipo, todas_las_filas(api, r.carga_id), por_renglon, por_alias, resolutor
            )
            if not propuestas:
                break
            aplicadas, fallidas = aplicar(api, r.carga_id, propuestas)
            r.decisiones_repetidas += aplicadas
            r.decisiones_fallidas += fallidas
            if aplicadas == 0:
                break
        r.tiempos["decidir"] = segundos(inicio)

        detalle = api.get(f"/api/v1/importaciones/{r.carga_id}")
        r.pendientes = detalle["resumen"]["enCuarentena"]
        r.diferencia_hojas = sum(h["diferencia"] for h in detalle["conciliacion"])
        if tipo.startswith("HIST_"):
            conciliacion = api.get(f"/api/v1/importaciones/{r.carga_id}/conciliacion")
            r.conceptos_con_diferencia = [
                c["concepto"] for c in conciliacion["conceptos"] if float(c["diferencia"]) != 0
            ]
        if r.pendientes or r.diferencia_hojas or r.conceptos_con_diferencia:
            r.estado = "detenida"
            pendientes = [
                f"{g['regla']} ({g['pendientes']})" for g in detalle["reglas"] if g["pendientes"]
            ]
            r.motivo = "; ".join(
                filter(
                    None,
                    [
                        f"{r.pendientes} en cuarentena: " + ", ".join(pendientes)
                        if r.pendientes
                        else "",
                        f"diferencia por hoja {r.diferencia_hojas}" if r.diferencia_hojas else "",
                        "conciliación con diferencia: " + ", ".join(r.conceptos_con_diferencia)
                        if r.conceptos_con_diferencia
                        else "",
                    ],
                )
            )
            avisar(f"  ✗ {tipo} detenida: {r.motivo}")
            break

        inicio = time.monotonic()
        confirmado = api.post(f"/api/v1/importaciones/{r.carga_id}/confirmar")
        r.tiempos["confirmar"] = segundos(inicio)
        r.creados = confirmado.get("creados") or {}
        r.estado = "confirmada"
        avisar(
            f"  ✓ {tipo}: {r.renglones} renglones, {r.decisiones_repetidas} decisiones repetidas,"
            f" {sum(r.tiempos.values()):.0f} s"
        )
    return resultados, segundos(inicio_total)


def informe(resultados: list[ResultadoCarga], total: float, url: str, limite_min: int) -> str:
    completas = all(r.estado in ("confirmada", "ya-confirmada") for r in resultados)
    dentro = total <= limite_min * 60
    lineas = [
        "# Ensayo general de la migración",
        "",
        f"{ahora()} · {url}",
        "",
        (
            f"**Resultado: {'completo' if completas else 'detenido'}"
            f" en {total / 60:.1f} min** (criterio: menos de {limite_min} min,"
            f" {'cumple' if completas and dentro else 'no cumple'})."
        ),
        "",
        (
            "| Carga | Archivo | Renglones | Cuarentena al subir | Decisiones repetidas | Fallidas"
            " | Pendientes | Estado | Subir (s) | Decidir (s) | Confirmar (s) |"
        ),
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: |",
    ]
    for r in resultados:
        t = r.tiempos
        lineas.append(
            f"| {r.tipo} | {r.archivo} | {r.renglones} | {r.cuarentena_al_subir}"
            f" | {r.decisiones_repetidas} | {r.decisiones_fallidas} | {r.pendientes}"
            f" | {r.estado} | {t.get('subir', '')} | {t.get('decidir', '')}"
            f" | {t.get('confirmar', '')} |"
        )
    detenidas = [r for r in resultados if r.estado == "detenida"]
    if detenidas:
        lineas += ["", "## Qué falta", ""]
        lineas += [f"- **{r.tipo}** (carga {r.carga_id}): {r.motivo}" for r in detenidas]
        lineas += [
            "",
            "Resolver en *Migración* y volver a correr con `--continuar`.",
        ]
    creados: dict[str, int] = {}
    for r in resultados:
        for entidad, n in r.creados.items():
            creados[entidad] = creados.get(entidad, 0) + n
    if creados:
        lineas += ["", "## Registros creados", ""]
        lineas += [f"- {entidad}: {n}" for entidad, n in sorted(creados.items())]
    return "\n".join(lineas) + "\n"


# -------------------------------------------------------------------------------- revertir


def revertir(api: Api, avisar: Callable[[str], None] = print) -> int:
    """Revierte todas las cargas vigentes, de la más reciente a la más antigua."""
    vigentes = [c for c in api.get("/api/v1/importaciones") if c["estado"] != "revertida"]
    for carga in sorted(vigentes, key=lambda c: (c.get("confirmada") or "", c["id"]), reverse=True):
        api.post(f"/api/v1/importaciones/{carga['id']}/revertir")
        avisar(f"· revertida {carga['id']} {carga['tipo']}")
    return len(vigentes)


# ------------------------------------------------------------------------------------ main


def sesion(argumentos: argparse.Namespace) -> Api:
    api = Api(argumentos.url)
    contrasena = os.environ.get("ENSAYO_CONTRASENA") or getpass.getpass(
        f"Contraseña de {argumentos.usuario}: "
    )
    api.iniciar_sesion(
        argumentos.usuario,
        contrasena,
        lambda: argumentos.codigo or input("Código de 6 dígitos de la app: ").strip(),
    )
    return api


def principal(argv: list[str] | None = None) -> int:
    analizador = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = analizador.add_subparsers(dest="comando", required=True)
    for nombre in ("exportar", "correr", "revertir"):
        p = sub.add_parser(nombre)
        p.add_argument("--url", required=True)
        p.add_argument("--usuario", required=True)
        p.add_argument("--codigo", help="código del segundo factor (si no, se pide)")
        if nombre == "exportar":
            p.add_argument("--salida", type=Path, default=Path("decisiones.json"))
        if nombre == "correr":
            p.add_argument("--decisiones", type=Path, required=True)
            p.add_argument("--almacen", type=Path, required=True)
            p.add_argument("--sua", type=Path, required=True)
            p.add_argument("--destajo", type=Path, required=True)
            p.add_argument("--mes", required=True, help="mes del SUA, aaaa-mm")
            p.add_argument("--anio", type=int, required=True, help="año de las semanas del destajo")
            p.add_argument("--cliente", default="Ruba")
            p.add_argument("--crear-cliente", action="store_true")
            p.add_argument("--continuar", action="store_true")
            p.add_argument("--limite-minutos", type=int, default=60)
            p.add_argument("--salida", type=Path, default=Path("ensayo"))
    argumentos = analizador.parse_args(argv)

    if argumentos.comando == "exportar":
        datos = exportar(sesion(argumentos), argumentos.url)
        argumentos.salida.write_text(json.dumps(datos, ensure_ascii=False, indent=1), "utf-8")
        print(
            f"{len(datos['cargas'])} cargas, {len(datos['decisiones'])} decisiones y"
            f" {len(datos['alias'])} alias en {argumentos.salida}"
            + (f"; {len(datos['sin_portar'])} sin portar" if datos["sin_portar"] else "")
        )
        return 0

    if argumentos.comando == "revertir":
        print(f"{revertir(sesion(argumentos))} cargas revertidas")
        return 0

    archivos = {"almacen": argumentos.almacen, "sua": argumentos.sua, "destajo": argumentos.destajo}
    for origen, ruta in archivos.items():
        if not ruta.is_file():
            raise SystemExit(f"No existe el archivo de {origen}: {ruta}")
    decisiones = json.loads(argumentos.decisiones.read_text("utf-8"))
    if decisiones.get("formato") != VERSION_FORMATO:
        raise SystemExit("decisiones.json es de otro formato: vuelve a exportarlo")
    resultados, total = correr(
        sesion(argumentos),
        archivos,
        decisiones,
        argumentos.cliente,
        argumentos.mes,
        argumentos.anio,
        argumentos.continuar,
        argumentos.crear_cliente,
    )
    argumentos.salida.mkdir(parents=True, exist_ok=True)
    texto = informe(resultados, total, argumentos.url, argumentos.limite_minutos)
    (argumentos.salida / "ensayo.md").write_text(texto, "utf-8")
    (argumentos.salida / "ensayo.json").write_text(
        json.dumps(
            {"url": argumentos.url, "total_s": total, "cargas": [r.__dict__ for r in resultados]},
            ensure_ascii=False,
            indent=1,
        ),
        "utf-8",
    )
    print("\n" + texto)
    completo = all(r.estado in ("confirmada", "ya-confirmada") for r in resultados)
    return 0 if completo else 2


if __name__ == "__main__":
    sys.exit(principal())

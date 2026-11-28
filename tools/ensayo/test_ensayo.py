"""Pruebas del ensayo general contra una API simulada (sin red ni datos reales).

python3 -m unittest discover -s tools/ensayo -v
"""

from __future__ import annotations

import json
import re
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import ensayo

TIPOS = [t for t, _ in ensayo.ORDEN]


class Entorno:
    """Un entorno con sus propios ids: preproducción y producción no comparten ninguno."""

    def __init__(self, desplazamiento: int, resueltas: bool, segundo_factor: bool = False):
        self.d = desplazamiento
        self.segundo_factor = segundo_factor
        self.sesion = False
        self.pendiente = False
        self.cargas: dict[int, dict] = {}
        self.clientes = [{"id": 1 + desplazamiento, "nombre": "Ruba"}]
        self.contratos = {101 + desplazamiento: "60-KZ7-MF6-C002-2026"}
        self.lotes = {
            501 + desplazamiento: "M16-L34 · PALERMO",
            502 + desplazamiento: "M16-L35 · PALERMO",
        }
        self.personas = {701 + desplazamiento: "GARCIA CRUZ BLADIMIR"}
        self.resueltas = resueltas
        self.alias: list[dict] = []
        self.decisiones_recibidas: list[dict] = []
        self.subidas: list[dict] = []
        if resueltas:
            for tipo in TIPOS:
                carga = self._nueva(tipo, {})
                for fila in carga["filas"]:
                    # Lo que se ligó por alias ya no aparece como incidencia al revalidar.
                    fila["incidencias"] = [
                        i
                        for i in fila["incidencias"]
                        if not (i.get("sugerencia") or {}).get("texto")
                    ]
                    for inc in fila["incidencias"]:
                        inc["resolucion"] = self._resolucion(inc)
                carga["estado"] = "confirmada"
            self.alias.append(
                {
                    "entidad": "persona",
                    "texto": "BLADIMIR GCIA",
                    "destino": "GARCIA CRUZ BLADIMIR",
                    "origen": "manual",
                }
            )

    def _resolucion(self, inc: dict) -> dict:
        if inc["regla"] == "LOTE_SIN_CONTRATO":
            return {"opcion": "ELEGIR", "destinoId": 101 + self.d}
        if inc["regla"] == "LOTE_INEXISTENTE":
            return {"opcion": "ELEGIR", "destinoId": 502 + self.d}
        return {"opcion": "DESCARTAR", "motivo": "renglón de prueba"}

    def _nueva(self, tipo: str, parametros: dict, crudo_extra: str = "") -> dict:
        carga_id = 1000 + self.d + len(self.cargas)
        filas = [
            {
                "numero": 1,
                "hoja": "Hoja",
                "filaExcel": 2,
                "crudo": {"A": "valido " + tipo},
                "normal": {},
                "estado": "valida",
                "accion": "crear",
                "incidencias": [],
            }
        ]
        incidencias = {
            "LOTES": [
                {
                    "regla": "LOTE_SIN_CONTRATO",
                    "severidad": "cuarentena",
                    "sugerencia": None,
                    "opciones": ["ELEGIR", "DESCARTAR"],
                }
            ],
            "HIST_SALIDAS": [
                {
                    "regla": "LOTE_INEXISTENTE",
                    "severidad": "cuarentena",
                    "sugerencia": {"entidad": "unidad_obra", "frenteId": 3},
                    "opciones": ["ELEGIR", "VALOR", "DESCARTAR"],
                }
            ],
            "PERSONAS_DESTAJO": [
                {
                    "regla": "PERSONA_SIN_LIGAR",
                    "severidad": "cuarentena",
                    "sugerencia": {"entidad": "persona", "texto": "BLADIMIR GCIA"},
                    "opciones": ["ELEGIR", "CREAR", "DESCARTAR"],
                }
            ],
            "HIST_DESTAJO": [
                {
                    "regla": "MONTO_ILEGIBLE",
                    "severidad": "cuarentena",
                    "sugerencia": None,
                    "opciones": ["VALOR", "DESCARTAR"],
                }
            ],
        }.get(tipo, [])
        if incidencias:
            filas.append(
                {
                    "numero": 2,
                    "hoja": "Hoja",
                    "filaExcel": 3,
                    "crudo": {"A": "dudoso " + tipo + crudo_extra},
                    "normal": {},
                    "estado": "cuarentena",
                    "accion": "omitir",
                    "incidencias": [
                        dict(i, detalle="", pendiente=True, resolucion=None) for i in incidencias
                    ],
                }
            )
        carga = {
            "id": carga_id,
            "tipo": tipo,
            "archivo": "x.xlsx",
            "hojas": "Hoja",
            "estado": "validada",
            "parametros": parametros,
            "filas": filas,
            "confirmada": None,
        }
        self.cargas[carga_id] = carga
        return carga

    def resumen(self, carga: dict) -> dict:
        pendientes = sum(
            1 for f in carga["filas"] for i in f["incidencias"] if not i.get("resolucion")
        )
        return {
            "id": carga["id"],
            "tipo": carga["tipo"],
            "archivo": carga["archivo"],
            "hojas": carga["hojas"],
            "estado": carga["estado"],
            "renglones": len(carga["filas"]),
            "enCuarentena": pendientes,
            "confirmada": carga["confirmada"],
            "repetida": False,
        }

    def detalle(self, carga: dict) -> dict:
        reglas = {}
        for f in carga["filas"]:
            for i in f["incidencias"]:
                r = reglas.setdefault(i["regla"], {"regla": i["regla"], "pendientes": 0})
                r["pendientes"] += 0 if i.get("resolucion") else 1
        return {
            "resumen": self.resumen(carga),
            "parametros": carga["parametros"],
            "conciliacion": [{"hoja": "Hoja", "diferencia": 0}],
            "reglas": list(reglas.values()),
            "creados": {"renglones": len(carga["filas"])}
            if carga["estado"] == "confirmada"
            else {},
        }

    def destinos(self, entidad: str) -> dict[int, str]:
        return {
            "contrato": self.contratos,
            "unidad_obra": self.lotes,
            "persona": self.personas,
        }.get(entidad, {})


class Manejador(BaseHTTPRequestHandler):
    entorno: Entorno

    def log_message(self, *args):  # silencio
        pass

    def _responder(self, estado: int, cuerpo=None):
        datos = json.dumps(cuerpo).encode() if cuerpo is not None else b""
        self.send_response(estado)
        self.send_header("Content-Type", "application/json")
        if self.path.startswith("/api/v1/auth/csrf"):
            self.send_header("Set-Cookie", "XSRF-TOKEN=tok; Path=/")
        self.send_header("Content-Length", str(len(datos)))
        self.end_headers()
        self.wfile.write(datos)

    def _error(self, estado: int, codigo: str):
        self._responder(estado, {"codigo": codigo, "detail": codigo})

    def _cuerpo(self) -> bytes:
        return self.rfile.read(int(self.headers.get("Content-Length") or 0))

    def do_GET(self):
        e = self.entorno
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        ruta = url.path
        if ruta == "/api/v1/auth/csrf":
            return self._responder(200, {"token": "tok"})
        if not e.sesion:
            return self._error(401, "NO_AUTENTICADO")
        if ruta == "/api/v1/importaciones/tipos":
            confirmados = {c["tipo"] for c in e.cargas.values() if c["estado"] == "confirmada"}
            return self._responder(
                200,
                [
                    {
                        "tipo": t,
                        "confirmada": t in confirmados,
                        "parametros": {
                            "FRENTES": ["clienteId"],
                            "HIST_SUA": ["mes"],
                            "HIST_DESTAJO": ["anio"],
                        }.get(t, []),
                    }
                    for t in TIPOS
                ],
            )
        if ruta == "/api/v1/clientes":
            return self._responder(200, e.clientes)
        if ruta == "/api/v1/alias":
            return self._responder(200, e.alias)
        if ruta == "/api/v1/importaciones":
            return self._responder(200, [e.resumen(c) for c in e.cargas.values()])
        if ruta == "/api/v1/importaciones/destinos":
            texto = ensayo.normal(q.get("q", ""))
            todos = e.destinos(q["entidad"])
            return self._responder(
                200,
                [
                    {"id": i, "texto": t}
                    for i, t in todos.items()
                    if all(p in ensayo.normal(t) for p in texto.split())
                ],
            )
        m = re.fullmatch(r"/api/v1/importaciones/destinos/(\w+)/(\d+)", ruta)
        if m:
            texto = e.destinos(m[1]).get(int(m[2]))
            return (
                self._responder(200, {"id": int(m[2]), "texto": texto})
                if texto
                else self._error(404, "DESTINO_NO_ENCONTRADO")
            )
        m = re.fullmatch(r"/api/v1/importaciones/(\d+)(/filas|/conciliacion)?", ruta)
        if m:
            carga = e.cargas[int(m[1])]
            if m[2] == "/filas":
                pagina, tamano = int(q.get("pagina", 0)), int(q.get("tamano", 50))
                filas = carga["filas"][pagina * tamano : (pagina + 1) * tamano]
                return self._responder(200, {"elementos": filas, "total": len(carga["filas"])})
            if m[2] == "/conciliacion":
                return self._responder(
                    200, {"conceptos": [{"concepto": "Cantidad", "diferencia": 0}]}
                )
            return self._responder(200, e.detalle(carga))
        return self._error(404, "RUTA")

    def do_POST(self):
        e = self.entorno
        ruta = urlparse(self.path).path
        cuerpo = self._cuerpo()
        if self.headers.get("X-XSRF-TOKEN") != "tok":
            return self._error(403, "CSRF")
        if ruta == "/api/v1/auth/login":
            datos = json.loads(cuerpo)
            if datos["contrasena"] != "secreta":
                return self._error(401, "CREDENCIALES_INVALIDAS")
            if e.segundo_factor:
                e.pendiente = True
                return self._responder(202, {"paso": "codigo"})
            e.sesion = True
            return self._responder(200, {"usuario": datos["usuario"]})
        if ruta == "/api/v1/auth/segundo-factor":
            if not e.pendiente or json.loads(cuerpo)["codigo"] != "123456":
                return self._error(401, "CODIGO_INVALIDO")
            e.sesion = True
            return self._responder(200, {"usuario": "admin"})
        if not e.sesion:
            return self._error(401, "NO_AUTENTICADO")
        if ruta == "/api/v1/importaciones":
            campos = dict(re.findall(rb'name="(\w+)"\r\n\r\n([^\r]*)\r\n', cuerpo))
            campos = {k.decode(): v.decode() for k, v in campos.items()}
            e.subidas.append(campos)
            tipo = campos["tipo"]
            carga = (
                e._nuevo_para_prod(tipo, campos)
                if hasattr(e, "_nuevo_para_prod")
                else e._nueva(tipo, campos)
            )
            return self._responder(201, e.resumen(carga))
        m = re.fullmatch(r"/api/v1/importaciones/(\d+)/(decisiones|confirmar|revertir)", ruta)
        if m:
            carga = e.cargas[int(m[1])]
            if m[2] == "decisiones":
                for d in json.loads(cuerpo)["decisiones"]:
                    e.decisiones_recibidas.append(d)
                    fila = next(f for f in carga["filas"] if f["numero"] == d["numero"])
                    inc = next(i for i in fila["incidencias"] if i["regla"] == d["regla"])
                    if d["opcion"] not in inc["opciones"]:
                        return self._error(400, "OPCION_NO_PERMITIDA")
                    if d["opcion"] == "ELEGIR":
                        entidad = (
                            inc["sugerencia"]["entidad"] if inc.get("sugerencia") else "contrato"
                        )
                        if d["destinoId"] not in e.destinos(entidad):
                            return self._error(400, "DESTINO_NO_ENCONTRADO")
                    inc["resolucion"] = {k: v for k, v in d.items() if k not in ("numero", "regla")}
                return self._responder(200, e.detalle(carga))
            if m[2] == "confirmar":
                if e.resumen(carga)["enCuarentena"]:
                    return self._error(409, "CON_CUARENTENA")
                carga["estado"] = "confirmada"
                carga["confirmada"] = f"2026-11-27T10:00:{carga['id'] % 60:02d}Z"
                return self._responder(200, e.detalle(carga))
            carga["estado"] = "revertida"
            return self._responder(200, e.detalle(carga))
        return self._error(404, "RUTA")


def servidor(entorno: Entorno) -> tuple[ThreadingHTTPServer, str]:
    manejador = type("M", (Manejador,), {"entorno": entorno})
    http = ThreadingHTTPServer(("127.0.0.1", 0), manejador)
    threading.Thread(target=http.serve_forever, daemon=True).start()
    return http, f"http://127.0.0.1:{http.server_address[1]}"


class PruebasEnsayo(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.archivos = {}
        for origen in ("almacen", "sua", "destajo"):
            ruta = Path(self.tmp.name) / f"{origen}.xlsx"
            ruta.write_bytes(b"PK\x03\x04 libro simulado")
            self.archivos[origen] = ruta
        self.servidores = []

    def tearDown(self):
        for s in self.servidores:
            s.shutdown()
            s.server_close()
        self.tmp.cleanup()

    def _api(self, entorno: Entorno) -> ensayo.Api:
        http, url = servidor(entorno)
        self.servidores.append(http)
        api = ensayo.Api(url)
        api.iniciar_sesion("admin", "secreta", lambda: "123456")
        return api

    def _exportado(self) -> dict:
        preprod = Entorno(0, resueltas=True)
        return ensayo.exportar(self._api(preprod), "preprod")

    def test_exporta_decisiones_por_nombre_y_no_por_id(self):
        datos = self._exportado()
        self.assertEqual(len(datos["cargas"]), len(ensayo.ORDEN))
        destinos = {d["regla"]: d["destino"] for d in datos["decisiones"] if d["destino"]}
        self.assertEqual(
            destinos["LOTE_SIN_CONTRATO"], {"entidad": "contrato", "texto": "60-KZ7-MF6-C002-2026"}
        )
        self.assertEqual(
            destinos["LOTE_INEXISTENTE"], {"entidad": "unidad_obra", "texto": "M16-L35 · PALERMO"}
        )
        self.assertEqual(datos["alias"][0]["destino"], "GARCIA CRUZ BLADIMIR")
        self.assertEqual(datos["sin_portar"], [])

    def test_repite_las_decisiones_en_otro_entorno_y_confirma_todo(self):
        datos = self._exportado()
        prod = Entorno(9000, resueltas=False, segundo_factor=True)
        api = self._api(prod)
        avisos: list[str] = []
        resultados, total = ensayo.correr(
            api, self.archivos, datos, "Ruba", "2026-10", 2026, False, False, avisos.append
        )
        self.assertTrue(all(r.estado == "confirmada" for r in resultados), avisos)
        elegidos = {d["regla"]: d.get("destinoId") for d in prod.decisiones_recibidas}
        self.assertEqual(
            elegidos["LOTE_SIN_CONTRATO"], 9101
        )  # el id de producción, no el de preprod
        self.assertEqual(elegidos["LOTE_INEXISTENTE"], 9502)
        self.assertEqual(elegidos["PERSONA_SIN_LIGAR"], 9701)  # por alias
        self.assertEqual(elegidos["MONTO_ILEGIBLE"], None)
        parametros = {s["tipo"]: s for s in prod.subidas}
        self.assertEqual(parametros["FRENTES"]["clienteId"], "9001")
        self.assertEqual(parametros["HIST_SUA"]["mes"], "2026-10")
        self.assertEqual(parametros["HIST_DESTAJO"]["anio"], "2026")
        texto = ensayo.informe(resultados, total, "prod", 60)
        self.assertIn("Resultado: completo", texto)
        self.assertIn("cumple", texto)

    def test_un_renglon_que_cambio_en_el_excel_no_se_decide_solo(self):
        datos = self._exportado()
        prod = Entorno(9000, resueltas=False)
        original = prod._nueva
        prod._nuevo_para_prod = lambda tipo, campos: original(
            tipo, campos, crudo_extra=" (cambió)" if tipo == "HIST_SALIDAS" else ""
        )
        resultados, total = ensayo.correr(
            self._api(prod),
            self.archivos,
            datos,
            "Ruba",
            "2026-10",
            2026,
            False,
            False,
            lambda _: None,
        )
        estados = {r.tipo: r.estado for r in resultados}
        self.assertEqual(estados["HIST_ENTRADAS"], "confirmada")
        self.assertEqual(estados["HIST_SALIDAS"], "detenida")
        self.assertNotIn("HIST_SUA", estados)  # se detiene en la primera que no cierra
        detenida = next(r for r in resultados if r.estado == "detenida")
        self.assertIn("LOTE_INEXISTENTE (1)", detenida.motivo)
        self.assertIn("Qué falta", ensayo.informe(resultados, total, "prod", 60))

    def test_no_empieza_sobre_un_entorno_con_cargas(self):
        datos = self._exportado()
        ya_cargado = Entorno(9000, resueltas=True)
        with self.assertRaises(SystemExit):
            ensayo.correr(
                self._api(ya_cargado),
                self.archivos,
                datos,
                "Ruba",
                "2026-10",
                2026,
                False,
                False,
                lambda _: None,
            )

    def test_revertir_deja_todas_las_cargas_revertidas(self):
        entorno = Entorno(0, resueltas=True)
        api = self._api(entorno)
        self.assertEqual(ensayo.revertir(api, lambda _: None), len(ensayo.ORDEN))
        self.assertTrue(all(c["estado"] == "revertida" for c in entorno.cargas.values()))

    def test_la_foto_de_existencias_va_despues_de_los_prestamos(self):
        tipos = [t for t, _ in ensayo.ORDEN]
        self.assertEqual(len(tipos), 16)
        self.assertEqual(tipos.index("HIST_EXISTENCIAS"), tipos.index("HIST_PRESTAMOS") + 1)

    def test_congelar_y_verificar_los_excel_del_corte(self):
        congelado = ensayo.congelar(self.archivos)
        self.assertEqual(set(congelado["archivos"]), {"almacen", "sua", "destajo"})
        self.assertRegex(congelado["archivos"]["almacen"]["sha256"], r"^[0-9a-f]{64}$")
        self.assertEqual(ensayo.verificar_congelado(self.archivos, congelado), [])
        # Alguien captura en el Excel después de congelarlo.
        self.archivos["almacen"].write_bytes(b"PK\x03\x04 otra salida capturada")
        problemas = ensayo.verificar_congelado(self.archivos, congelado)
        self.assertEqual(len(problemas), 1)
        self.assertIn("almacen", problemas[0])
        self.assertIn("no es el archivo congelado", problemas[0])

    def test_el_corte_no_carga_un_excel_que_cambio(self):
        ruta = Path(self.tmp.name) / "congelado.json"
        argumentos = [
            "--almacen",
            str(self.archivos["almacen"]),
            "--sua",
            str(self.archivos["sua"]),
            "--destajo",
            str(self.archivos["destajo"]),
        ]
        self.assertEqual(ensayo.principal(["congelar", *argumentos, "--salida", str(ruta)]), 0)
        self.archivos["sua"].write_bytes(b"PK\x03\x04 cambiado")
        decisiones = Path(self.tmp.name) / "decisiones.json"
        decisiones.write_text(json.dumps({"formato": ensayo.VERSION_FORMATO}), "utf-8")
        with self.assertRaises(SystemExit) as salida:
            ensayo.principal(
                [
                    "corte",
                    "--url",
                    "http://127.0.0.1:9",
                    "--usuario",
                    "admin",
                    "--congelado",
                    str(ruta),
                    "--decisiones",
                    str(decisiones),
                    *argumentos,
                    "--mes",
                    "2026-10",
                    "--anio",
                    "2026",
                ]
            )
        self.assertIn("sua", str(salida.exception))

    def test_verificar_en_la_estabilizacion_que_nadie_toco_el_excel(self):
        ruta = Path(self.tmp.name) / "congelado.json"
        ruta.write_text(json.dumps(ensayo.congelar(self.archivos)), "utf-8")
        almacen = ["--congelado", str(ruta), "--almacen", str(self.archivos["almacen"])]
        self.assertEqual(ensayo.principal(["verificar", *almacen]), 0)
        # Alguien vuelve a capturar una salida en el Excel del piloto.
        self.archivos["almacen"].write_bytes(b"PK\x03\x04 otra salida capturada")
        self.assertEqual(ensayo.principal(["verificar", *almacen]), 3)
        filas = ensayo.verificacion(
            {"almacen": self.archivos["almacen"]}, json.loads(ruta.read_text("utf-8"))
        )
        self.assertFalse(filas[0]["coincide"])
        with self.assertRaises(SystemExit):
            ensayo.principal(["verificar", "--congelado", str(ruta)])

    def test_el_informe_del_corte_trae_los_sha256(self):
        datos = self._exportado()
        prod = Entorno(9000, resueltas=False)
        resultados, total = ensayo.correr(
            self._api(prod), self.archivos, datos, "Ruba", "2026-10", 2026, False, False,
            lambda _: None,
        )
        congelado = ensayo.congelar(self.archivos)
        texto = ensayo.informe(resultados, total, "prod", 60, congelado)
        self.assertIn("# Carga final del corte", texto)
        self.assertIn(congelado["archivos"]["destajo"]["sha256"], texto)
        self.assertIn("Resultado: completo", texto)

    def test_el_codigo_equivocado_no_inicia_sesion(self):
        http, url = servidor(Entorno(0, resueltas=False, segundo_factor=True))
        self.servidores.append(http)
        with self.assertRaises(ensayo.ErrorApi) as error:
            ensayo.Api(url).iniciar_sesion("admin", "secreta", lambda: "000000")
        self.assertEqual(error.exception.codigo, "CODIGO_INVALIDO")


if __name__ == "__main__":
    unittest.main()

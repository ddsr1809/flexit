#!/usr/bin/env python3
"""Genera las muestras anonimizadas que usan las pruebas de los importadores (H3).

Reproducen la forma de los tres Excel reales (hojas, filas de encabezado, columnas, fórmulas y
los problemas conocidos del plan), pero con datos inventados: ningún nombre, monto ni contrato es
real. Para regenerarlas:

    python3 tools/validacion-datos/generar_muestras.py

Requiere openpyxl. Si cambias una muestra, revisa las pruebas de ImportadoresTest.
"""
import datetime as dt
import pathlib

from openpyxl import Workbook

DESTINO = pathlib.Path(__file__).parent / "muestras"


def guardar(libro, nombre):
    DESTINO.mkdir(exist_ok=True)
    ruta = DESTINO / nombre
    libro.save(ruta)
    print("escrita", ruta)


def almacen():
    libro = Workbook()
    ex = libro.active
    ex.title = "Existencias"
    ex["F1"] = "Seguimiento de pedido: "
    encabezados = {"A": "Precio Unitario Promedio", "B": "Zona", "E": "Clave",
                   "F": "Artículo/Material", "I": "Unidad de medida", "J": "Existencia",
                   "O": "Inventario inicial"}
    for col, texto in encabezados.items():
        ex[f"{col}3"] = texto
    materiales = [
        ("001", "Abrazadera omega de 3/4\"", "Piezas"),
        ("002", "Cemento gris 50 kg", "Saco"),
        ("003", "Aerosol negro", "pieza"),
        ("004", "Bolsa para escombro", "Bolsas "),
        ("005", "Clavo para concreto 1 1/2\"", "kilo"),
        ("006", "Juego de brocas", "Juego"),
        ("007", "Lija 220", "Pieza"),
        ("008", "Pintura vinílica blanca 19 L", "Cubetas"),
    ]
    for i, (clave, nombre, unidad) in enumerate(materiales, start=5):
        ex[f"A{i}"] = f'=IFERROR(AVERAGEIFS(Entradas!K:K,Entradas!F:F,F{i}),"")'
        ex[f"B{i}"] = 1
        ex[f"E{i}"] = clave
        ex[f"F{i}"] = nombre
        ex[f"I{i}"] = unidad
        ex[f"J{i}"] = f"=O{i}"
        ex[f"O{i}"] = 10

    en = libro.create_sheet("Entradas")
    for col, texto in {"A": "Semana", "B": "Fecha Recepción", "D": "Cant.", "E": "Unidad",
                       "F": "Artículo", "G": "Precio Unitario ", "H": "Subtotal",
                       "I": "Descuento (SIN IVA)", "J": "IVA", "K": "Precio Unitario +IVA",
                       "M": "Proveedor ", "N": "Folio/N. factura", "O": "Quién Recibe:"}.items():
        en[f"{col}1"] = texto
    entradas = [
        # material, cantidad, precio sin IVA, descuento, IVA, proveedor, recibe
        ("Abrazadera omega de 3/4\"", 40, 1.50, None, 0.08, "Ferretera Norte", "Lalo Prueba"),
        ("Abrazadera omega de 3/4\"", 60, 2.00, 10, 0.08, "FERRETERA  NORTE", "Lalo Prueba"),
        ("Cemento gris 50 kg", 10, 220, None, 0.16, "Materiales del Valle", None),
        ("Cemento gris 50 kg", 10, 600, None, 0.16, "Materiales del Valle", None),
        ("Aerosol negro", 5, None, None, None, "Pinturas Sol", None),
        ("Pintura vinílica blanca 19 L", 2, 1500, None, 0.08, "Pinturas Sol", "Mari Ejemplo"),
        ("Lija 220", 20, 8.5, None, 0.08, "Ferreteria Nortte", None),
    ]
    for i, (mat, cant, precio, desc, iva, prov, recibe) in enumerate(entradas, start=2):
        en[f"B{i}"] = dt.date(2026, 6, 10 + i)
        en[f"D{i}"] = cant
        en[f"E{i}"] = f"=_xlfn.XLOOKUP(F{i},Existencias!F:F,Existencias!I:I)"
        en[f"F{i}"] = mat
        if precio is not None:
            en[f"G{i}"] = precio
        en[f"H{i}"] = f"=D{i}*G{i}"
        if desc is not None:
            en[f"I{i}"] = desc
        if iva is not None:
            en[f"J{i}"] = iva
        en[f"K{i}"] = f"=(G{i}+G{i}*J{i})"
        en[f"M{i}"] = prov
        if recibe:
            en[f"O{i}"] = recibe
    # Un proveedor que es fórmula: no se lee
    fila = len(entradas) + 2
    en[f"F{fila}"] = "Cemento gris 50 kg"
    en[f"D{fila}"] = 1
    en[f"M{fila}"] = '=IF(1=1,"Proveedor calculado","")'

    sa = libro.create_sheet("Salidas")
    sa["F1"] = "Requisiciones y Vales de Material / 2026"
    for col, texto in {"A": "Fecha", "B": "Documento", "C": "Razón", "D": "Folio",
                       "E": "Paqueteria", "F": "Mza", "G": "Lote", "H": "Cant.", "I": "Unidad",
                       "J": "Artículo", "K": "Solicitante", "L": "Frente", "M": "Entregar a:",
                       "N": "Entregado"}.items():
        sa[f"{col}2"] = texto
    salidas = [
        ("Cemento gris 50 kg", "Lalo", "Lalo"),
        ("Lija 221", "Lalo", "Ana y Pepe"),
        ("Lija 221", "Mari", "Mari"),
        ("Tubo ABS 1\" (6 metros)", "Pepe", "6"),
        ("Aerosol negro", "Lalo", "Lalo"),
    ]
    for i, (mat, sol, entregar) in enumerate(salidas, start=3):
        sa[f"A{i}"] = dt.date(2026, 6, 22)
        sa[f"B{i}"] = "Vale"
        sa[f"C{i}"] = "Programa"
        sa[f"F{i}"] = 20
        sa[f"G{i}"] = 30 + i
        sa[f"H{i}"] = 1
        sa[f"I{i}"] = f"=_xlfn.XLOOKUP(J{i},Existencias!F:F,Existencias!I:I)"
        sa[f"J{i}"] = mat
        sa[f"K{i}"] = sol
        sa[f"L{i}"] = "Corceles"
        sa[f"M{i}"] = entregar
        sa[f"N{i}"] = True

    se = libro.create_sheet("Semanal")
    for col, texto in {"A": "Semana", "F": "Solicitante", "G": "Frente", "K": "Cantidad",
                       "L": "Unidad", "N": "Material"}.items():
        se[f"{col}2"] = texto
    se["A3"], se["F3"], se["G3"], se["K3"], se["L3"], se["N3"] = (
        "Semana 31", "Pepe", "Corceles", 2, "Piezas", "Tubo ABS 1\" (6 metros)")

    fa = libro.create_sheet("Faltante solicitud diaria")
    for col, texto in {"A": "Fecha de Solicitud", "C": "Cantidad", "D": "Unidad", "E": "Material",
                       "F": "Solicitante", "G": "Frente"}.items():
        fa[f"{col}2"] = texto
    fa["A3"], fa["C3"], fa["D3"], fa["E3"], fa["F3"] = (
        dt.date(2026, 6, 12), 2, "Piezas", "Tanque sanitario", "Mari")

    pr = libro.create_sheet("Prestamo Herramientas-Material")
    for col, texto in {"A": "Mza", "C": "Cant.", "D": "Unidad", "E": "Artículo",
                       "H": "Solicitante", "J": "Entregar a:"}.items():
        pr[f"{col}1"] = texto
    pr["C2"], pr["D2"], pr["E2"], pr["H2"], pr["J2"] = 1, "Pieza", "Rotomartillo", "Pepe", "Pepe"

    li = libro.create_sheet("Info. listas desplegables")
    for col, texto in {"A": "Documento", "C": "Frente", "E": "Manzana", "H": "Estado pedido"}.items():
        li[f"{col}1"] = texto
    for i, frente in enumerate(["Corceles", "Granada", "Palermo", "Almacén", "corceles "], start=2):
        li[f"C{i}"] = frente
    li["A2"], li["A3"] = "Vale", "Requisición"
    guardar(libro, "muestra-almacen.xlsx")


def sua():
    libro = Workbook()
    imss = libro.active
    imss.title = "IMSS OCTUBRE"
    for col, texto in {"A": "STATUS", "B": "NOMBRE", "C": "DIAS IMSS", "E": "CONTRATO"}.items():
        imss[f"{col}1"] = texto
    nombres = ["PEREZ LUNA MARIO ALBERTO ", "SALAS RUIZ ANA MARIA", "GARZA LEAL EDUARDO",
               "TORRES VEGA JOSE PEDRO", "MORA DIAZ MARIA ELENA"]
    for i, nombre in enumerate(nombres, start=2):
        imss[f"A{i}"] = "ALTA "
        imss[f"B{i}"] = nombre
        imss[f"C{i}"] = 31
        imss[f"E{i}"] = "60-AAA-MF6-C002-2026"
    anterior = libro.create_sheet("SUA ANTERIOR")
    anterior["A1"], anterior["B1"] = "NOMBRE", "FRENTE"
    anterior["A2"] = "PERES LUNA MARIO ALBERTO"      # una letra distinta: posible duplicado
    anterior["A3"] = "salas  ruíz ana maría"         # mismo nombre con acentos y espacios
    bd = libro.create_sheet("BD")
    bd["A1"], bd["B1"] = "NOMBRE", "FRENTE"
    bd["A2"] = "GARZA LEAL EDUARDO"
    asistencia = libro.create_sheet("ASISTENCIA OCTUBRE")
    for col, texto in {"A": "#", "B": "FRENTE", "D": "NOMBRE DEL PERSONAL", "E": "TIPO DE PAGO"}.items():
        asistencia[f"{col}1"] = texto
    asistencia["D2"] = "EDUARDO GARZA LEAL"          # otro orden de palabras
    asistencia["D3"] = "RIOS CANO LUIS"

    contratos = libro.create_sheet("CONTRATOS ACTIVOS")
    contratos["A1"], contratos["B1"] = "20", "notas"
    contratos["A2"], contratos["B2"] = "corceles", "contratos"
    filas = [
        ("60-AAA-MF6-C002-2026", "CORCELES FRACCIONAMIENTO", dt.date(2026, 7, 12), dt.date(2027, 3, 28), "C1269209", 4882033.1099999985),
        ("60-bbb-mek/C001/2026", "QUINTA GRANADA", dt.date(2026, 4, 9), dt.date(2026, 8, 18), "C1226676", 373768.13999999966),
        ("60-CCC-MEO-C001-2026", "PALERMO RESIDENCIAL", dt.date(2026, 4, 12), dt.date(2026, 8, 3), "C1240318", 1072615.53),
        ("60-DDD-MEG-C001-2026", "PALERMO RESIDENCIAL", dt.date(2026, 9, 1), dt.date(2026, 2, 1), "C1203287", 0),
    ]
    for i, (codigo, frente, inicio, fin, obra, monto) in enumerate(filas, start=4):
        contratos[f"B{i}"] = codigo
        contratos[f"C{i}"] = frente
        contratos[f"D{i}"] = inicio
        contratos[f"E{i}"] = fin
        contratos[f"F{i}"] = obra
        contratos[f"G{i}"] = monto
    contratos["I3"] = "=+H3/10"
    guardar(libro, "muestra-sua.xlsx")


def hoja_pagos(libro, nombre, prototipo, lotes, secciones, ruba=False):
    hoja = libro.create_sheet(nombre)
    hoja["A2"] = "Conceptos y Pagos"
    hoja["C5"], hoja["D5"] = "PROTOTIPO:", prototipo
    for col, texto in {"A": "No.", "B": "Concepto RUBA", "C": "P.U.", "D": "Destajo RM",
                       "E": "Cantidad", "F": "Unidad", "G": "P.U.", "H": "Importe"}.items():
        hoja[f"{col}6"] = texto
    # Lotes cada tres columnas desde I: Pagos, Sem, Trabajador
    for j, lote in enumerate(lotes):
        base = 9 + j * 3
        hoja.cell(row=5, column=base, value="Pagos")
        hoja.cell(row=5, column=base + 1, value="Sem")
        hoja.cell(row=5, column=base + 2, value="Trabajador")
        hoja.cell(row=6, column=base, value=lote)
    fila = 7
    for numero, (seccion, conceptos) in enumerate(secciones, start=1):
        hoja[f"A{fila}"] = str(numero)
        hoja[f"B{fila}"] = seccion
        hoja[f"D{fila}"] = seccion
        fila += 1
        inicio = fila
        for k, concepto in enumerate(conceptos, start=1):
            nombre_rm, precio, cliente = concepto
            hoja[f"A{fila}"] = f"{numero}.{k}"
            if nombre_rm is not None:
                hoja[f"D{fila}"] = nombre_rm
            hoja[f"E{fila}"] = "Lote"
            hoja[f"F{fila}"] = 1
            if precio is not None:
                hoja[f"G{fila}"] = precio
            hoja[f"H{fila}"] = f"=F{fila}*G{fila}"
            if ruba and cliente:
                hoja[f"B{fila}"] = cliente[0]
                hoja[f"C{fila}"] = cliente[1]
            fila += 1
        hoja[f"C{fila}"] = f"=SUM(C{inicio}:C{fila - 1})"
        hoja[f"H{fila}"] = f"=SUM(H{inicio}:H{fila - 1})"
        hoja[f"D{fila}"] = "acuerdo con la cuadrilla, precio anterior"
        fila += 1
    # Pagos de ejemplo y trabajadores por apodo
    trabajadores = ["Lalo", "Lalo G(1,2)", "Pepe y Mari", "LALO LALO", "1000"]
    for j in range(len(lotes)):
        for fila_pago, desfase in ((8, 0), (9, 3)):
            hoja.cell(row=fila_pago, column=9 + j * 3, value=2500)
            hoja.cell(row=fila_pago, column=10 + j * 3, value="Sem 31")
            hoja.cell(row=fila_pago, column=11 + j * 3,
                      value=trabajadores[(j + desfase) % len(trabajadores)])
    # Resumen de abajo: no es catálogo
    fila += 2
    hoja[f"B{fila}"], hoja[f"C{fila}"], hoja[f"D{fila}"] = "PRECIOS", "RUBA", "DIF"
    hoja[f"A{fila + 1}"], hoja[f"B{fila + 1}"] = "1", "CIMENTACIÓN"
    hoja[f"B{fila + 2}"] = "TOTAL GASTADO POR LOTE"
    return hoja


def destajo():
    libro = Workbook()
    libro.remove(libro.active)
    secciones = [
        ("CIMENTACIÓN", [("TRAZO, EXCAVACION Y CIMBRA", 2400, None),
                         ("COLADO DE CIMENTACION", 1600, None)]),
        ("MUROS 1ER NIVEL", [("COLOCACION DE CAJAS ELECTRICAS", 500, None),
                             ("MURO PLANTA BAJA", 3800, None),
                             ("COLOCACION DE CAJAS ELECTRICAS", 500, None),
                             (None, 2100, None),
                             ("CIMBRADO DE CASTILLOS", None, None)]),
    ]
    hoja_pagos(libro, "Pagos Corceles P-65", "65", ["M20L10", "M20L15", "M21L3"], secciones)
    secciones_ruba = [
        ("CIMENTACIÓN", [("CIMENTACION", 10332, ("Paquete 01 Trazo y excavación", 4250.03)),
                         ("ACOMETIDA HIDRAULICA", 350, ("Paquete 03 Colado de cimentación", 5093.44))]),
    ]
    hoja_pagos(libro, "Pagos Granada P-51", "51", ["M56L6", "M56L7"], secciones_ruba, ruba=True)
    hoja_pagos(libro, "MDZ Palermo P-45", "45", ["M16L3", "M716", "M20L10"], secciones[:1])
    otra = libro.create_sheet("DASHBORD")
    otra["A1"] = "CONTRATOS ACTIVOS"
    guardar(libro, "muestra-destajo.xlsx")


def plantilla_lotes():
    libro = Workbook()
    hoja = libro.active
    hoja.title = "Lotes"
    for col, texto in {"A": "Frente", "B": "Contrato", "C": "Manzana", "D": "Lote", "E": "Prototipo"}.items():
        hoja[f"{col}1"] = texto
    filas = [
        ("Corceles", "60-AAA-MF6/C002/2026", "20", "29-31, 35", "T-69"),
        ("Corceles", "60-AAA-MF6-C002-2026", "20", "36", "79"),
        ("Corceles", "60-ZZZ-NOEXISTE-2026", "21", "1", "P-65"),
        ("Corceles", "60-AAA-MF6-C002-2026", "x", "y", "P-65"),
    ]
    for i, fila in enumerate(filas, start=2):
        for j, valor in enumerate(fila):
            hoja.cell(row=i, column=j + 1, value=valor)
    guardar(libro, "muestra-lotes.xlsx")


if __name__ == "__main__":
    almacen()
    sua()
    destajo()
    plantilla_lotes()

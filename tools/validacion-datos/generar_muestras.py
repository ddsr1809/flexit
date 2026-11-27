#!/usr/bin/env python3
"""Genera las muestras anonimizadas que usan las pruebas de los importadores (H3 y H5).

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


def historico_almacen():
    """Hojas de movimientos del almacén con los problemas que motivan las reglas de H5."""
    libro = Workbook()
    en = libro.active
    en.title = "Entradas"
    for col, texto in {"A": "Semana", "B": "Fecha Recepción", "D": "Cant.", "E": "Unidad",
                       "F": "Artículo", "G": "Precio Unitario ", "H": "Subtotal",
                       "I": "Descuento (SIN IVA)", "J": "IVA", "M": "Proveedor ",
                       "N": "Folio/N. factura", "O": "Quién Recibe:"}.items():
        en[f"{col}1"] = texto
    entradas = [
        # semana, fecha, cantidad, artículo, precio, descuento, iva, proveedor
        (None, dt.date(2026, 6, 10), 10, "Cemento gris 50 kg", 200, None, 0.16, "Materiales del Valle"),
        (None, dt.date(2026, 6, 20), 10, "Cemento gris 50 kg", 220, 100, None, "Materiales del Valle"),
        (None, dt.date(2026, 6, 25), 30, "Pintura vinílica blanca 19 L", "=206.95/30", None, 0.08, "Pinturas Sol"),
        (None, dt.date(2026, 6, 26), 5, "Cemento gris 50 kg", None, None, None, "Materiales del Valle"),
        ("Semana 32", dt.date(2027, 8, 3), "=3*4", "Aerosol negro", 40, None, None, "Pinturas Sol"),
        (None, None, 2, "Aerosol negro", 41, None, 0.08, "Pinturas Sol"),
        (None, dt.date(2026, 7, 1), 4, "Lija 220", 8, None, 0.08, None),
    ]
    for i, (sem, fecha, cant, art, precio, desc, iva, prov) in enumerate(entradas, start=2):
        if sem:
            en[f"A{i}"] = sem
        if fecha:
            en[f"B{i}"] = fecha
        en[f"D{i}"] = cant
        en[f"E{i}"] = f"=_xlfn.XLOOKUP(F{i},Existencias!F:F,Existencias!I:I)"
        en[f"F{i}"] = art
        if precio is not None:
            en[f"G{i}"] = precio
        en[f"H{i}"] = f"=D{i}*G{i}"
        if desc is not None:
            en[f"I{i}"] = desc
        if iva is not None:
            en[f"J{i}"] = iva
        if prov:
            en[f"M{i}"] = prov
        en[f"O{i}"] = "Lalo"

    sa = libro.create_sheet("Salidas")
    sa["F1"] = "Requisiciones y Vales de Material / 2026"
    for col, texto in {"A": "Fecha", "B": "Documento", "C": "Razón", "D": "Folio",
                       "E": "Paqueteria", "F": "Mza", "G": "Lote", "H": "Cant.", "I": "Unidad",
                       "J": "Artículo", "K": "Solicitante", "L": "Frente", "M": "Entregar a:",
                       "N": "Entregado", "O": "Observaciones"}.items():
        sa[f"{col}2"] = texto
    salidas = [
        # fecha, razón, paquetería, mza, lote, cantidad, artículo, solicitante, frente, entregado, obs
        (dt.date(2026, 6, 22), "Programa", "Acabados ", 20, 30, 2, "Cemento gris 50 kg", "Lalo", "Corceles", True, None),
        (dt.date(2026, 6, 22), "Programa", None, 20, 31, "=1/3", "Pintura vinílica blanca 19 L", "Lalo", "Corceles", True, None),
        (dt.date(2026, 6, 22), "Programa", None, 20, 32, "=1/3", "Pintura vinílica blanca 19 L", "Lalo", "Corceles", True, None),
        (dt.date(2026, 6, 22), "Programa", None, 20, 33, "=1/3", "Pintura vinílica blanca 19 L", "Lalo", "Corceles", True, None),
        (dt.date(2026, 6, 23), "Retrabajo", None, 20, "30, 31, 32", 10, "Cemento gris 50 kg", "Mari", "Corceles", True, None),
        (dt.date(2026, 6, 23), "Programa", None, 20, "-", 1, "Lija 220", "Mari", "Corceles", True, None),
        (dt.date(2026, 6, 24), "Programa", None, "16, 6, 5", "-", 3, "Lija 220", "Mari", "Corceles", True, None),
        (dt.date(2026, 6, 24), "Programa", None, 20, 99, 1, "Aerosol negro", "Pepe", "Corceles", True, None),
        (dt.date(2026, 6, 25), "Programa", None, 20, 99, 2, "Aerosol negro", "Pepe", "Corceles", True, None),
        (dt.date(2026, 6, 25), "Garantía Ruba", None, 16, 34, 1, "Aerosol negro", "Pepe", "Corceles", True, None),
        (dt.date(2026, 6, 26), "Programa", None, 20, 30, -1, "Cemento gris 50 kg", "Lalo", "Corceles", True, None),
        (dt.date(2026, 6, 26), "Programa", None, 20, 31, -2, "Cemento gris 50 kg", "Lalo", "Corceles", True,
         "AJUSTE DE MATERIALES EN LOTES, SE REFLEJA MATERIAL REAL POR CASA"),
        (dt.date(2026, 6, 27), None, None, None, None, 1, "Lija 220", "Lalo", "Almacén", True, None),
        (dt.date(2026, 6, 27), "Programa", None, 20, 30, 5, "Cemento gris 50 kg", "Lalo", "Corceles", False, None),
        (dt.date(2026, 6, 28), "Programa", None, 20, 30, "=12-SUM(H3:H5)", "Cemento gris 50 kg", "Lalo", "Corceles", True, None),
    ]
    for i, (fecha, razon, paq, mza, lote, cant, art, sol, frente, entregado, obs) in enumerate(salidas, start=3):
        sa[f"A{i}"] = fecha
        sa[f"B{i}"] = "Vale"
        if razon:
            sa[f"C{i}"] = razon
        if paq:
            sa[f"E{i}"] = paq
        if mza is not None:
            sa[f"F{i}"] = mza
        if lote is not None:
            sa[f"G{i}"] = lote
        sa[f"H{i}"] = cant
        sa[f"I{i}"] = f"=_xlfn.XLOOKUP(J{i},Existencias!F:F,Existencias!I:I)"
        sa[f"J{i}"] = art
        sa[f"K{i}"] = sol
        sa[f"L{i}"] = frente
        sa[f"M{i}"] = sol
        sa[f"N{i}"] = entregado
        if obs:
            sa[f"O{i}"] = obs

    se = libro.create_sheet("Semanal")
    for col, texto in {"A": "Semana", "E": "Autorización Coordinación", "F": "Solicitante",
                       "G": "Frente", "H": "Prototipo", "I": "Manzana", "J": "Lote", "K": "Cantidad",
                       "L": "Unidad", "M": "Paqueteria", "N": "Material", "O": "Lunes",
                       "P": "Martes", "U": "Subtotal (pedido)"}.items():
        se[f"{col}2"] = texto
    se["A3"], se["E3"], se["F3"], se["G3"], se["H3"], se["I3"], se["J3"] = (
        "Semana 31", "Autorizado", "Pepe", "Corceles", 65, 20, 30)
    se["K3"], se["L3"], se["M3"], se["N3"], se["O3"], se["P3"] = (
        1, "Paquete", "Acabados", "Cemento gris 50 kg", 2, "=1/2")
    se["U3"] = "=SUM(O3:T3)"
    se["A4"], se["E4"], se["F4"], se["G4"], se["I4"], se["J4"] = (
        "Sem. 20-25 julio", "No Autorizado", "Mari", "Corceles", "20-21", "-")
    se["K4"], se["L4"], se["N4"] = 3, "Piezas", "Lija 220"

    fa = libro.create_sheet("Faltante solicitud diaria")
    for col, texto in {"A": "Fecha de Solicitud", "B": "URGENCIA", "C": "Cantidad", "D": "Unidad",
                       "E": "Material", "F": "Solicitante", "G": "Frente", "H": "Observación",
                       "I": "Estado del pedido", "J": "Suministrado"}.items():
        fa[f"{col}2"] = texto
    fa["A3"], fa["B3"], fa["C3"], fa["D3"], fa["E3"], fa["G3"], fa["I3"], fa["J3"] = (
        dt.date(2026, 6, 12), "NO URGENTE", 2, "Piezas", "Aerosol negro", "Almacén ", "PENDIENTE", 1)

    pr = libro.create_sheet("Prestamo Herramientas-Material")
    for col, texto in {"A": "Mza", "B": "Lote", "C": "Cant.", "D": "Unidad", "E": "Artículo",
                       "F": "Fecha Entrega", "H": "Solicitante", "I": "Frente", "J": "Entregar a:",
                       "K": "Recibido", "L": "Fecha Recepción"}.items():
        pr[f"{col}1"] = texto
    pr["C2"], pr["D2"], pr["E2"], pr["F2"], pr["H2"], pr["I2"], pr["J2"], pr["K2"], pr["L2"] = (
        1, "Pieza", "Rotomartillo", dt.date(2026, 6, 10), "Pepe", "Corceles", "Pepe", True,
        dt.date(2026, 7, 2))
    pr["C3"], pr["D3"], pr["E3"], pr["F3"], pr["H3"], pr["I3"], pr["J3"], pr["K3"] = (
        1, "Pieza", "Lija 220", dt.date(2026, 6, 18), "Mari", "Corceles", "Trabajadores", False)
    guardar(libro, "muestra-historico-almacen.xlsx")


def historico_sua():
    """SUA de octubre con asistencia diaria, contrato con "/" y el del mes anterior por texto."""
    libro = Workbook()
    imss = libro.active
    imss.title = "IMSS OCTUBRE"
    for col, texto in {"A": "STATUS", "B": "NOMBRE", "C": "DIAS IMSS", "D": "SUA IMSS OCTUBRE",
                       "E": "CONTRATO", "F": "NO. OBRA"}.items():
        imss[f"{col}1"] = texto
    for i, (estatus, nombre, dias, contrato) in enumerate([
            ("ALTA ", "PEREZ LUNA MARIO ALBERTO ", 31, "60-AAA-MF6/C002/2026"),
            ("BAJA", "SALAS RUIZ ANA MARIA", 6, "60-AAA-MF6-C002-2026"),
            ("ALTA", "RIOS CANO LUIS", 31, "60-ZZZ-NOEXISTE-2026")], start=2):
        imss[f"A{i}"], imss[f"B{i}"], imss[f"C{i}"] = estatus, nombre, dias
        imss[f"D{i}"], imss[f"E{i}"], imss[f"F{i}"] = "CORCELES", contrato, "C1269209"
    asistencia = libro.create_sheet("ASISTENCIA OCTUBRE")
    for col, texto in {"A": "#", "B": "FRENTE", "D": "NOMBRE DEL PERSONAL", "E": "TIPO DE PAGO",
                       "F": "OCUPACION"}.items():
        asistencia[f"{col}1"] = texto
    for dia in range(1, 32):
        asistencia.cell(row=1, column=6 + dia, value=dia)
    filas = [
        ("CORCELES", "PEREZ LUNA MARIO ALBERTO", "NOMINA", "OFICIAL", {1: 1, 2: 0.5, 3: 0, 4: 1}),
        ("PALERMO", "MARIO ALBERTO PEREZ LUNA", "NOMINA", "OFICIAL", {4: 1, 5: 1}),
        ("CORCELES", "SALAS RUIZ ANA MARIA", "DESTAJO", "AYUDANTE", {1: 1, 2: "F"}),
    ]
    for i, (frente, nombre, tipo, ocupacion, dias) in enumerate(filas, start=2):
        asistencia[f"A{i}"], asistencia[f"B{i}"], asistencia[f"D{i}"] = i - 1, frente, nombre
        asistencia[f"E{i}"], asistencia[f"F{i}"] = tipo, ocupacion
        for dia, valor in dias.items():
            asistencia.cell(row=i, column=6 + dia, value=valor)
    anterior = libro.create_sheet("SUA ANTERIOR")
    anterior["A1"], anterior["B1"], anterior["C1"] = "NOMBRE", "FRENTE", "CONTRATO ACTIVO"
    anterior["A2"], anterior["B2"], anterior["C2"] = "PEREZ LUNA MARIO ALBERTO   ", "CORCELES", "1 MF6 10 VIVIENDAS"
    bd = libro.create_sheet("BD")
    bd["A1"], bd["B1"], bd["C1"] = "NOMBRE", "FRENTE", "ASIGNACION PARA IMSS POR CONTRATO"
    bd["A2"], bd["B2"], bd["C2"] = "SALAS RUIZ ANA MARIA", "2 G - C ", "CORCELES"
    guardar(libro, "muestra-historico-sua.xlsx")


def historico_destajo():
    """Pagos de destajo con fórmulas, semanas y trabajadores juntos, sobrepago y subtotales."""
    libro = Workbook()
    hoja = libro.active
    hoja.title = "Pagos Corceles P-65"
    hoja["A2"] = "Conceptos y Pagos"
    hoja["C5"], hoja["D5"] = "PROTOTIPO:", 65
    for col, texto in {"A": "No.", "B": "Concepto RUBA", "C": "P.U.", "D": "Destajo RM",
                       "E": "Cantidad", "F": "Unidad", "G": "P.U.", "H": "Importe"}.items():
        hoja[f"{col}6"] = texto
    for j, lote in enumerate(["M20L30", "M20L31", "M2OL3"]):
        base = 9 + j * 3
        hoja.cell(row=5, column=base, value="Pagos")
        hoja.cell(row=5, column=base + 1, value="Sem")
        hoja.cell(row=5, column=base + 2, value="Trabajador")
        hoja.cell(row=6, column=base, value=lote)
    hoja["A7"], hoja["B7"], hoja["D7"] = 1, "CIMENTACIÓN", "CIMENTACIÓN"
    hoja["A8"], hoja["D8"], hoja["E8"], hoja["F8"], hoja["G8"] = 1.1, "TRAZO Y EXCAVACION", "Lote", 1, 2400
    hoja["H8"] = "=F8*G8"
    hoja["I8"], hoja["J8"], hoja["K8"] = "=1000+1400", "s39\ns38(2)", "Lalo G(1,2)"
    hoja["L8"], hoja["M8"], hoja["N8"] = 3000, "s40", "Pepe y Mari"
    hoja["O8"], hoja["P8"], hoja["Q8"] = 2400, "S40", "Lalo"
    hoja["A9"], hoja["D9"], hoja["E9"], hoja["F9"], hoja["G9"] = 1.2, "COLADO", "Lote", 1, 1600
    hoja["I9"], hoja["J9"], hoja["K9"] = 1600, "s41", "1000"
    hoja["L9"] = 0
    hoja["O9"] = 0
    hoja["I10"] = "=SUM(I8:I9)"
    hoja["L10"] = "=SUM(L8:L9)"
    hoja["B12"], hoja["C12"] = "PRECIOS", "RUBA"
    guardar(libro, "muestra-historico-destajo.xlsx")


def conciliacion():
    """Hoja de un frente con sus fórmulas, para conciliar un contrato contra ella (H6, issue 38).

    Un renglón de Salidas por cada motivo de diferencia: razón con espacio de más, varios lotes en
    una celda, lote que no es columna, nombre de material distinto, lote corregido en la
    cuarentena, material sin precio, no entregado, cantidad como fórmula.
    """
    libro = Workbook()
    ex = libro.active
    ex.title = "Existencias"
    for col, texto in {"A": "Precio Unitario Promedio", "E": "Clave", "F": "Artículo/Material",
                       "I": "Unidad de medida"}.items():
        ex[f"{col}3"] = texto
    materiales = ["Cemento gris 50 kg", "Pintura vinílica blanca 19 L", "Lija 220",
                  "Aerosol negro", "Varilla 3/8\""]
    for i, nombre in enumerate(materiales, start=5):
        ex[f"A{i}"] = f'=IFERROR(AVERAGEIFS(Entradas!K:K,Entradas!F:F,Existencias!F{i},Entradas!K:K,">0"),"")'
        ex[f"E{i}"] = f"{i - 4:03d}"
        ex[f"F{i}"] = nombre
        ex[f"I{i}"] = "Pieza"

    en = libro.create_sheet("Entradas")
    for col, texto in {"B": "Fecha Recepción", "D": "Cant.", "F": "Artículo",
                       "G": "Precio Unitario ", "J": "IVA", "K": "Precio Unitario +IVA"}.items():
        en[f"{col}1"] = texto
    entradas = [
        # material, cantidad, precio sin IVA, IVA (el promedio simple no pondera por cantidad)
        ("Cemento gris 50 kg", 30, 200, 0.16),
        ("Cemento gris 50 kg", 10, 300, 0.16),
        ("Pintura vinílica blanca 19 L", 2, 1500, 0.08),
        ("Lija 220", 20, 8.5, None),
        ("Varilla 3/8\"", 100, 50, 0.16),
    ]
    for i, (mat, cant, precio, iva) in enumerate(entradas, start=2):
        en[f"B{i}"] = dt.date(2026, 6, 10 + i)
        en[f"D{i}"] = cant
        en[f"F{i}"] = mat
        en[f"G{i}"] = precio
        if iva is not None:
            en[f"J{i}"] = iva
        en[f"K{i}"] = f"=(G{i}+G{i}*J{i})"

    sa = libro.create_sheet("Salidas")
    for col, texto in {"A": "Fecha", "B": "Documento", "C": "Razón", "F": "Mza", "G": "Lote",
                       "H": "Cant.", "J": "Artículo", "L": "Frente", "N": "Entregado"}.items():
        sa[f"{col}2"] = texto
    salidas = [
        # fila 3 en adelante: razón, mza, lote, cantidad, material, entregado
        ("Programa", 20, 1, 4, "Cemento gris 50 kg", True),             # 3: cuenta en M20-L1
        ("Programa ", 20, 1, 2, "Cemento gris 50 kg", True),            # 4: razón con espacio
        ("Programa", 20, "2-3", 2, "Pintura vinílica blanca 19 L", True),  # 5: varios lotes
        ("Programa", 20, 9, 1, "Varilla 3/8\"", True),                  # 6: M20-L9 no es columna
        ("Programa", 20, 1, 3, "Lija  220", True),                      # 7: nombre distinto
        ("Programa", 20, 2, 5, "Cemento gris 50 kg", True),             # 8: el sistema lo pasó a L3
        ("Programa", 20, 1, 1, "Aerosol negro", True),                  # 9: sin precio en la hoja
        ("Programa", 20, 1, 6, "Cemento gris 50 kg", False),            # 10: no entregado
        ("Programa", 20, 3, 2, "Lija 220", True),                       # 11: el sistema no lo tiene
        ("Programa", 20, 1, "=1/4", "Cemento gris 50 kg", True),        # 12: cantidad fórmula
    ]
    for i, (razon, mza, lote, cant, mat, entregado) in enumerate(salidas, start=3):
        sa[f"A{i}"] = dt.date(2026, 6, 22)
        sa[f"B{i}"] = "Vale"
        sa[f"C{i}"] = razon
        sa[f"F{i}"] = mza
        sa[f"G{i}"] = lote
        sa[f"H{i}"] = cant
        sa[f"J{i}"] = mat
        sa[f"L{i}"] = "Corceles"
        sa[f"N{i}"] = entregado

    co = libro.create_sheet("CORCELES")
    co["D1"] = "CORCELES"
    for col, texto in {"A": "Clave", "B": "Paquete", "C": "Zona", "D": "Artículo/Material",
                       "H": "Unidad", "I": "Precio Prom.", "J": "Rescate"}.items():
        co[f"{col}3"] = texto
    columnas = [("Q", "R", 20, 1), ("S", "T", 20, 2), ("U", "V", 20, 3), ("W", "X", "X", "X")]
    for mza, lote, m, l in columnas:
        co[f"{mza}2"], co[f"{lote}2"] = "Mza", "Lote"
        co[f"{mza}3"], co[f"{lote}3"] = m, l
        co[f"{mza}4"], co[f"{lote}4"] = "Cant", "Costo Total"
    ultima = 4 + len(materiales)
    for i in range(5, ultima + 1):
        co[f"A{i}"] = f"{i - 4:03d}"
        co[f"D{i}"] = f"=Existencias!F{i}"
        co[f"I{i}"] = f"=Existencias!A{i}"
        for mza, lote, _, _ in columnas:
            co[f"{mza}{i}"] = (
                f"=SUMIFS(Salidas!$H$1:$H$14994,Salidas!$F$1:$F$14994,CORCELES!{mza}$3,"
                f"Salidas!$G$1:$G$14994,CORCELES!{lote}$3,Salidas!$J$1:$J$14994,CORCELES!$D{i},"
                f"Salidas!$N$1:$N$14994,TRUE,Salidas!$C$1:$C$14994,\"Programa\","
                f"Salidas!$L$1:$L$14994,\"Corceles\")")
            co[f"{lote}{i}"] = (
                f'=IFERROR(IF(Existencias!$A{i}*CORCELES!{mza}{i} = 0, "", '
                f'Existencias!$A{i}*CORCELES!{mza}{i}), "")')
    co[f"D{ultima + 1}"] = "TOTAL"
    for mza, lote, _, _ in columnas:
        co[f"{lote}{ultima + 1}"] = f"=SUM({lote}5:{lote}{ultima})"
    guardar(libro, "muestra-conciliacion.xlsx")


if __name__ == "__main__":
    conciliacion()
    almacen()
    sua()
    destajo()
    plantilla_lotes()
    historico_almacen()
    historico_sua()
    historico_destajo()

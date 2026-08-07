#!/usr/bin/env python3
"""
Genera datos de prueba de posición GPS realistas para los 10 vendedores
REALES con tipo='0' (001,002,003,005,010,011,012,013,033,120), simulando un
celular que emite un punto cada 30 segundos (igual que el servicio de
background real de dipalza_mobile) durante una jornada de 10:00 a 19:00,
siguiendo calles reales (ruteo con OSRM, router.project-osrm.org — servicio
público, sin API key) en vez de líneas rectas entre paradas.

Las ciudades base de cada vendedor se tomaron de dbo.vendedor_ruta + dbo.ruta
(consulta en vivo contra la BD real, 2026-07-31): un vendedor con 2 rutas
asignadas alterna paradas entre ambas ciudades durante el día. Los 4
vendedores sin fila en vendedor_ruta (001, 003, 033, 120 — códigos
administrativos: "Cobrador Unico", "OFICINA", "DEVOLUCION OFICINA",
"DEVOLUCION VENCIDOS") usan una ciudad de dbo.ruta no cubierta por los demás,
solo para completar el set de 10 y mantener consistencia con el resto de la
simulación.

Reemplaza POR COMPLETO los tres días de prueba generados en scripts previos
(generar_datos_prueba_posicion.py y _multi_dia.py): 2026-07-29, -30 y -31,
calculados como offsets -2/-1/0 respecto al día de ejecución. Es idempotente:
borra el historial/posición previos de estos códigos en esas 3 fechas antes
de reinsertar.

Cada jornada tiene 8 "paradas" reales (>10 min, con margen sobre el umbral),
cuya duración se ajusta automáticamente para que jornada completa (tramos de
viaje reales según OSRM + tiempo en cada parada) sume exactamente las 9 horas
de 10:00 a 19:00. Todo es determinístico por (vendedor, día) vía una semilla
random.Random — mismos resultados si se vuelve a ejecutar.

Requiere de red hacia router.project-osrm.org (probado y funcionando desde
este entorno).

Uso:
    python3 generar_datos_prueba_posicion_realista.py > datos_prueba_realista_10_vendedores.sql
"""

import math
import random
import sys
import time

import requests

OSRM_BASE = "https://router.project-osrm.org/route/v1/driving/"

# (lat, lon) de cada ciudad, de dbo.ruta / conocimiento geográfico general de
# la Región de Valparaíso.
CIUDADES = {
    "LA LIGUA": (-32.4525, -71.2306),
    "PAPUDO": (-32.5017, -71.4453),
    "CONCON": (-32.9273, -71.5308),
    "QUILLOTA": (-32.8820, -71.2489),
    "QUINTERO": (-32.7761, -71.5314),
    "LA CRUZ": (-32.8347, -71.1875),
    "CASABLANCA": (-33.3200, -71.4083),
    "LONCURA": (-32.7667, -71.5333),
    "QUILPUE": (-33.0472, -71.4425),
    "PUCHUNCAVI": (-32.7333, -71.4167),
    "VENTANA": (-32.7419, -71.4761),
    "LA CALERA": (-32.7889, -71.1994),
}

# Ciudades por vendedor, según dbo.vendedor_ruta (join con dbo.ruta) para los
# que tenían fila; el resto (001, 003, 033, 120, sin fila en vendedor_ruta)
# se completó con una ciudad de dbo.ruta no usada por ningún otro vendedor de
# este set, solo para tener 10 recorridos distintos.
VENDEDORES = [
    {"codigo": "001", "nombre": "Cobrador Unico", "ciudades": ["PAPUDO"]},
    {"codigo": "002", "nombre": "CRISTIAN PAVEZ GALVEZ", "ciudades": ["PUCHUNCAVI", "VENTANA"]},
    {"codigo": "003", "nombre": "OFICINA", "ciudades": ["CASABLANCA"]},
    {"codigo": "005", "nombre": "CARLOS OLÓRTEGUI", "ciudades": ["QUINTERO", "QUILLOTA"]},
    {"codigo": "010", "nombre": "JORGE ZAMORA", "ciudades": ["CONCON", "QUILPUE"]},
    {"codigo": "011", "nombre": "JOSE OLIVARES", "ciudades": ["QUILLOTA", "QUINTERO"]},
    {"codigo": "012", "nombre": "MAURO HUINON", "ciudades": ["LA LIGUA", "LA CRUZ"]},
    {"codigo": "013", "nombre": "GUSTAVO ITURRIETA", "ciudades": ["CONCON", "QUILPUE"]},
    {"codigo": "033", "nombre": "DEVOLUCION OFICINA", "ciudades": ["LONCURA"]},
    {"codigo": "120", "nombre": "DEVOLUCION VENCIDOS", "ciudades": ["LA CALERA"]},
]
TIPO = "0"

DIAS = [
    {"offset_dias": -2, "etiqueta": "2026-07-29"},
    {"offset_dias": -1, "etiqueta": "2026-07-30"},
    {"offset_dias": 0, "etiqueta": "2026-07-31 (hoy)"},
]

N_PARADAS = 8
MIN_PARADA_SEG = 720  # 12 min: por sobre el umbral de 10 min de detectarParadas, con margen
INTERVALO_SEG = 30  # igual al Timer.periodic real del background service
HORA_INICIO_SEG = 10 * 3600  # 10:00
TOTAL_JORNADA_SEG = 9 * 3600  # 10:00 a 19:00
INSERT_BATCH = 800  # filas por sentencia INSERT (límite práctico de SQL Server: 1000)

METROS_POR_GRADO_LAT = 111_320
METROS_POR_GRADO_LON = 111_320 * math.cos(math.radians(33))


def desplazar(lat, lon, metros_norte, metros_este):
    dlat = metros_norte / METROS_POR_GRADO_LAT
    dlon = metros_este / METROS_POR_GRADO_LON
    return (round(lat + dlat, 6), round(lon + dlon, 6))


def punto_cerca(centro, rng, radio_min, radio_max):
    lat, lon = centro
    if radio_max <= 0:
        return (lat, lon)
    angulo = rng.uniform(0, 2 * math.pi)
    radio = rng.uniform(radio_min, radio_max)
    return desplazar(lat, lon, radio * math.cos(angulo), radio * math.sin(angulo))


def haversine_m(lat1, lon1, lat2, lon2):
    R = 6_371_000
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    return 2 * R * math.asin(math.sqrt(a))


def consultar_osrm(waypoints, rng, ciudades_por_parada, intentos=4):
    """waypoints: lista de (lat, lon). Devuelve lista de legs
    [{"duration": s, "distance": m, "coords": [(lat,lon), ...]}] o lanza
    RuntimeError si no logra rutear tras varios intentos con radio decreciente."""
    wp = list(waypoints)
    for intento in range(intentos):
        coords_url = ";".join(f"{lon:.6f},{lat:.6f}" for lat, lon in wp)
        try:
            resp = requests.get(
                OSRM_BASE + coords_url,
                params={"overview": "full", "geometries": "geojson", "steps": "true"},
                timeout=25,
            )
            data = resp.json()
        except Exception as exc:  # noqa: BLE001 - queremos reintentar ante cualquier falla de red
            data = {"code": f"EXC:{exc}"}

        if data.get("code") == "Ok":
            legs = []
            for leg in data["routes"][0]["legs"]:
                coords = []
                for step in leg["steps"]:
                    coords.extend((c[1], c[0]) for c in step["geometry"]["coordinates"])
                legs.append({"duration": leg["duration"], "distance": leg["distance"], "coords": coords})
            return legs

        # Reintento: volver a samplear los puntos "sueltos" (paradas/inicio/fin)
        # con radio más chico; en el último intento, radio 0 (centro exacto de
        # la ciudad, que siempre debería rutear).
        radio_max = max(0, 1500 - intento * 500)
        nuevo_wp = [wp[0]]
        for i, ciudad in enumerate(ciudades_por_parada):
            nuevo_wp.append(punto_cerca(CIUDADES[ciudad], rng, 0, radio_max))
        nuevo_wp.append(wp[-1])
        wp = nuevo_wp
        time.sleep(0.3)

    raise RuntimeError(f"OSRM no pudo rutear tras {intentos} intentos: {data}")


def punto_en_distancia(coords, cum_dist, objetivo_m):
    if objetivo_m <= 0:
        return coords[0]
    if objetivo_m >= cum_dist[-1]:
        return coords[-1]
    for i in range(1, len(cum_dist)):
        if cum_dist[i] >= objetivo_m:
            d0, d1 = cum_dist[i - 1], cum_dist[i]
            frac = (objetivo_m - d0) / (d1 - d0) if d1 > d0 else 0.0
            lat0, lon0 = coords[i - 1]
            lat1, lon1 = coords[i]
            return (lat0 + (lat1 - lat0) * frac, lon0 + (lon1 - lon0) * frac)
    return coords[-1]


def resamplear_leg(leg, intervalo_s):
    """Devuelve lista de (segundos_desde_inicio_del_leg, lat, lon), sin
    incluir t=0 (el punto de partida ya fue emitido por el paso anterior) ni
    el punto de llegada exacto (lo agrega el llamador con la coordenada
    exacta de la parada)."""
    coords = leg["coords"]
    duracion = leg["duration"]
    if duracion <= 0 or len(coords) < 2:
        return []

    cum = [0.0]
    for i in range(1, len(coords)):
        cum.append(cum[-1] + haversine_m(*coords[i - 1], *coords[i]))
    distancia_total = cum[-1]

    puntos = []
    t = intervalo_s
    while t < duracion:
        objetivo_m = (t / duracion) * distancia_total
        lat, lon = punto_en_distancia(coords, cum, objetivo_m)
        puntos.append((t, lat, lon))
        t += intervalo_s
    return puntos


def generar_pesos(rng, n):
    valores = [rng.uniform(0.6, 1.4) for _ in range(n)]
    total = sum(valores)
    return [v / total for v in valores]


def generar_puntos_vendedor_dia(vendedor, dia):
    seed = f"{vendedor['codigo']}-{dia['offset_dias']}"
    rng = random.Random(seed)

    ciudades = vendedor["ciudades"]
    ciudad_inicio = ciudades[0]
    ciudades_paradas = [ciudades[i % len(ciudades)] for i in range(N_PARADAS)]
    ciudad_fin = ciudades_paradas[-1]

    inicio = punto_cerca(CIUDADES[ciudad_inicio], rng, 100, 500)
    paradas = [punto_cerca(CIUDADES[c], rng, 300, 1800) for c in ciudades_paradas]
    fin = punto_cerca(CIUDADES[ciudad_fin], rng, 100, 500)

    waypoints = [inicio] + paradas + [fin]
    legs = consultar_osrm(waypoints, rng, ciudades_paradas + [ciudad_fin])

    travel_total = sum(leg["duration"] for leg in legs)
    presupuesto_paradas = TOTAL_JORNADA_SEG - travel_total
    if presupuesto_paradas < N_PARADAS * MIN_PARADA_SEG:
        presupuesto_paradas = N_PARADAS * MIN_PARADA_SEG  # caso borde: jornada se extiende un poco más de 9h

    pesos = generar_pesos(rng, N_PARADAS)
    duraciones_paradas = [max(MIN_PARADA_SEG, round(presupuesto_paradas * w)) for w in pesos]

    puntos = []
    t = 0.0
    puntos.append((t, inicio[0], inicio[1]))

    for i in range(N_PARADAS):
        leg = legs[i]
        for dt, lat, lon in resamplear_leg(leg, INTERVALO_SEG):
            puntos.append((t + dt, lat, lon))
        t += leg["duration"]
        lat_p, lon_p = paradas[i]
        puntos.append((round(t, 2), lat_p, lon_p))

        dur = duraciones_paradas[i]
        k = 1
        while k * INTERVALO_SEG <= dur:
            tk = t + k * INTERVALO_SEG
            jlat, jlon = desplazar(lat_p, lon_p, rng.uniform(-6, 6), rng.uniform(-6, 6))
            puntos.append((round(tk, 2), jlat, jlon))
            k += 1
        t += dur

    leg_fin = legs[N_PARADAS]
    for dt, lat, lon in resamplear_leg(leg_fin, INTERVALO_SEG):
        puntos.append((t + dt, lat, lon))
    t += leg_fin["duration"]
    puntos.append((round(t, 2), fin[0], fin[1]))

    return _asegurar_orden_estricto(puntos)


def _asegurar_orden_estricto(puntos):
    corregidos = []
    previo = None
    for seg, lat, lon in puntos:
        if previo is not None and seg <= previo:
            seg = round(previo + 0.5, 2)
        corregidos.append((seg, lat, lon))
        previo = seg
    return corregidos


def emitir_sql(datos_por_vendedor_dia):
    codigos_sql = ", ".join(f"'{v['codigo']}'" for v in VENDEDORES)

    print("-- =====================================================================")
    print("-- Datos de prueba REALISTAS de posición GPS: 10 vendedores reales")
    print("-- (tipo='0'), jornada 10:00-19:00, un punto cada 30s (igual que el")
    print("-- Timer.periodic real de dipalza_mobile), siguiendo calles reales vía")
    print("-- OSRM (router.project-osrm.org) en vez de líneas rectas. Ciudades base")
    print("-- tomadas de dbo.vendedor_ruta + dbo.ruta (consulta en vivo 2026-07-31).")
    print("--")
    print("-- Generado por data/generar_datos_prueba_posicion_realista.py.")
    print("-- REEMPLAZA por completo los 3 días de prueba anteriores (29, 30 y 31 de")
    print("-- julio de 2026 hora Chile) — borra el historial/posición sintéticos")
    print("-- previos de estos 10 códigos en esas 3 fechas antes de reinsertar. NO")
    print("-- toca dbo.vendedor (códigos reales, ya existen).")
    print("--")
    print("-- dbo.posicion queda con el último punto del día offset 0 (hoy).")
    print("-- =====================================================================")
    print()
    print("DECLARE @fecha_hoy DATE = CAST(DATEADD(HOUR, -4, GETDATE()) AS DATE);  -- fecha local Chile, no UTC")
    print()

    print("-- 1) Limpieza idempotente de los 3 días de prueba (29, 30, 31)")
    for dia in DIAS:
        print(f"""DELETE FROM dbo.historial_posicion
WHERE vendedorId IN ({codigos_sql})
  AND CAST(fechaHora AS DATE) = DATEADD(DAY, {dia['offset_dias']}, @fecha_hoy);
""")
    print(f"DELETE FROM dbo.posicion WHERE vendedorId IN ({codigos_sql});")
    print()

    print("-- 2) Historial de posiciones (jornada 10:00-19:00, ruteada por calles reales)")
    todas_filas = []
    for dia in DIAS:
        for v in VENDEDORES:
            puntos = datos_por_vendedor_dia[(v["codigo"], dia["offset_dias"])]
            for seg, lat, lon in puntos:
                offset_total_seg = HORA_INICIO_SEG + seg
                fecha_expr = (
                    f"DATEADD(SECOND, {int(round(offset_total_seg))}, "
                    f"CAST(DATEADD(DAY, {dia['offset_dias']}, @fecha_hoy) AS DATETIME2(0)))"
                )
                todas_filas.append(f"    ('{v['codigo']}', '{TIPO}', {fecha_expr}, {lat}, {lon})")

    for inicio in range(0, len(todas_filas), INSERT_BATCH):
        lote = todas_filas[inicio:inicio + INSERT_BATCH]
        print("INSERT INTO dbo.historial_posicion (vendedorId, vendedorCodigo, fechaHora, latitud, longitud)")
        print("VALUES")
        print(",\n".join(lote) + ";")
        print()

    print("-- 3) Posición actual = último punto del día 'hoy' (offset 0)")
    dia_hoy = next(d for d in DIAS if d["offset_dias"] == 0)
    for v in VENDEDORES:
        seg, lat, lon = datos_por_vendedor_dia[(v["codigo"], 0)][-1]
        offset_total_seg = HORA_INICIO_SEG + seg
        fecha_expr = f"DATEADD(SECOND, {int(round(offset_total_seg))}, CAST(@fecha_hoy AS DATETIME2(0)))"
        print(f"""INSERT INTO dbo.posicion (vendedorId, vendedorCodigo, latitud, longitud, ultimaActualizacion)
VALUES ('{v['codigo']}', '{TIPO}', {lat}, {lon}, {fecha_expr});""")
    print()

    print("-- =====================================================================")
    print("-- Limpieza manual (los 3 días, estos 10 vendedores; no toca dbo.vendedor):")
    print(f"-- DELETE FROM dbo.historial_posicion WHERE vendedorId IN ({codigos_sql}) AND CAST(fechaHora AS DATE) IN (CAST(DATEADD(DAY,-2,DATEADD(HOUR,-4,GETDATE())) AS DATE), CAST(DATEADD(DAY,-1,DATEADD(HOUR,-4,GETDATE())) AS DATE), CAST(DATEADD(HOUR,-4,GETDATE()) AS DATE));")
    print(f"-- DELETE FROM dbo.posicion WHERE vendedorId IN ({codigos_sql});")
    print("-- =====================================================================")


def main():
    datos_por_vendedor_dia = {}
    total_combos = len(VENDEDORES) * len(DIAS)
    hecho = 0
    for dia in DIAS:
        for v in VENDEDORES:
            puntos = generar_puntos_vendedor_dia(v, dia)
            datos_por_vendedor_dia[(v["codigo"], dia["offset_dias"])] = puntos
            hecho += 1
            print(
                f"# [{hecho}/{total_combos}] {v['codigo']} {dia['etiqueta']}: "
                f"{len(puntos)} puntos, dura {puntos[-1][0] / 60:.1f} min",
                file=sys.stderr,
            )

    emitir_sql(datos_por_vendedor_dia)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Extiende generar_datos_prueba_posicion.py: replica el recorrido de prueba del
2026-07-29 (ver ese script) para dos días más — "ayer" y "hoy" relativos al
momento de ejecución — con variaciones (hora de inicio, duración de cada
parada, tránsito entre paradas, y un pequeño desplazamiento de las paradas)
para que los tres días sean visualmente distinguibles al comparar recorridos
en el mapa (dipalza_web_client).

NO toca el 2026-07-29 (no lo borra, no lo reinserta) — solo agrega historial
para los otros dos días. Es idempotente por día: borra primero cualquier
historial de prueba ya existente para esos códigos en esas dos fechas antes
de volver a insertar (para poder re-ejecutar sin duplicar). El último punto
del día "hoy" (offset 0, el que se procesa al final) queda como dbo.posicion,
igual que el script original.

Uso:
    python3 generar_datos_prueba_posicion_multi_dia.py > datos_prueba_10_vendedores_30_31.sql
"""

import math

METROS_POR_GRADO_LAT = 111_320
METROS_POR_GRADO_LON = 111_320 * math.cos(math.radians(33))


def desplazar(lat, lon, metros_norte, metros_este):
    dlat = metros_norte / METROS_POR_GRADO_LAT
    dlon = metros_este / METROS_POR_GRADO_LON
    return round(lat + dlat, 6), round(lon + dlon, 6)


VENDEDORES = [
    {"codigo": "001", "nombre": "Cobrador Unico", "lat": -33.0393, "lon": -71.6273},
    {"codigo": "002", "nombre": "CRISTIAN PAVEZ GALVEZ", "lat": -33.0100, "lon": -71.5450},
    {"codigo": "003", "nombre": "OFICINA", "lat": -32.9273, "lon": -71.5308},
    {"codigo": "005", "nombre": "CARLOS OLÓRTEGUI", "lat": -33.0245, "lon": -71.5518},
    {"codigo": "010", "nombre": "JORGE ZAMORA", "lat": -32.9950, "lon": -71.5600},
    {"codigo": "011", "nombre": "JOSE OLIVARES", "lat": -33.0472, "lon": -71.4425},
    {"codigo": "012", "nombre": "MAURO HUINON", "lat": -33.0426, "lon": -71.3735},
    {"codigo": "013", "nombre": "GUSTAVO ITURRIETA", "lat": -33.5928, "lon": -71.6108},
    {"codigo": "033", "nombre": "DEVOLUCION OFICINA", "lat": -32.8820, "lon": -71.2489},
    {"codigo": "120", "nombre": "DEVOLUCION VENCIDOS", "lat": -32.4525, "lon": -71.2306},
]

TIPO = "0"

OFFSETS_PARADAS_BASE = [
    (300, -400),
    (-250, 600),
    (700, 200),
    (-150, -700),
]

PUNTOS_TRANSITO_POR_TRAMO = 5
JITTER_PARADA_METROS = 8

# Una variación por día: desplaza el offset de las 4 paradas (para que el
# recorrido se vea en otro sector, no exactamente encima del de otros días),
# cambia la hora de inicio de jornada, la duración de cada parada (siempre
# >10 min para seguir calificando como nodo numerado) y el tiempo de tránsito
# entre paradas.
DIAS = [
    {
        "offset_dias": -1,
        "etiqueta": "ayer",
        "shift_paradas_metros": (120, -90),
        "minuto_inicio_jornada": 8 * 60 + 10,  # 08:10
        "duracion_paradas_min": [22, 20, 28, 17],
        "minutos_transito_entre_paradas": 16,
    },
    {
        "offset_dias": 0,
        "etiqueta": "hoy",
        "shift_paradas_metros": (-110, 140),
        "minuto_inicio_jornada": 9 * 60 + 5,  # 09:05
        "duracion_paradas_min": [15, 33, 12, 24],
        "minutos_transito_entre_paradas": 24,
    },
]


def generar_puntos_vendedor(vendedor, dia):
    puntos = []
    minuto_actual = dia["minuto_inicio_jornada"]
    shift_n, shift_e = dia["shift_paradas_metros"]

    paradas_coords = [
        desplazar(vendedor["lat"], vendedor["lon"], dn + shift_n, de + shift_e)
        for dn, de in OFFSETS_PARADAS_BASE
    ]

    lat_salida, lon_salida = desplazar(
        vendedor["lat"], vendedor["lon"], -600 + shift_n, -900 + shift_e
    )
    puntos.append((minuto_actual, lat_salida, lon_salida))
    minuto_actual += 6

    anterior_lat, anterior_lon = lat_salida, lon_salida
    transito_min = dia["minutos_transito_entre_paradas"]
    duraciones = dia["duracion_paradas_min"]

    for i, (lat_parada, lon_parada) in enumerate(paradas_coords):
        for p in range(1, PUNTOS_TRANSITO_POR_TRAMO + 1):
            frac = p / (PUNTOS_TRANSITO_POR_TRAMO + 1)
            lat_i = round(anterior_lat + (lat_parada - anterior_lat) * frac, 6)
            lon_i = round(anterior_lon + (lon_parada - anterior_lon) * frac, 6)
            minuto_actual += transito_min / (PUNTOS_TRANSITO_POR_TRAMO + 1)
            puntos.append((round(minuto_actual, 2), lat_i, lon_i))

        duracion = duraciones[i]
        n_puntos_parada = max(3, duracion // 6)
        for k in range(n_puntos_parada):
            signo_lat = 1 if k % 2 == 0 else -1
            signo_lon = 1 if k % 3 == 0 else -1
            lat_j, lon_j = desplazar(
                lat_parada, lon_parada, signo_lat * JITTER_PARADA_METROS, signo_lon * JITTER_PARADA_METROS
            )
            minuto_punto = (
                minuto_actual + (duracion * k / max(1, n_puntos_parada - 1))
                if n_puntos_parada > 1
                else minuto_actual
            )
            puntos.append((round(minuto_punto, 2), lat_j, lon_j))

        minuto_actual += duracion
        anterior_lat, anterior_lon = lat_parada, lon_parada

    lat_fin, lon_fin = desplazar(anterior_lat, anterior_lon, 150, 100)
    minuto_actual += 8
    puntos.append((round(minuto_actual, 2), lat_fin, lon_fin))

    return _asegurar_orden_estricto(puntos)


def _asegurar_orden_estricto(puntos):
    corregidos = []
    minuto_previo = None
    for minuto, lat, lon in puntos:
        if minuto_previo is not None and minuto <= minuto_previo:
            minuto = round(minuto_previo + 0.5, 2)
        corregidos.append((minuto, lat, lon))
        minuto_previo = minuto
    return corregidos


def emitir_sql():
    print("-- =====================================================================")
    print("-- Replica el recorrido de prueba del 2026-07-29 (ver")
    print("-- generar_datos_prueba_posicion.py) para 'ayer' y 'hoy' (offsets -1 y 0")
    print("-- relativos al día de ejecución), con variaciones en hora de inicio,")
    print("-- duración de paradas, tránsito y desplazamiento de paradas, para que")
    print("-- los tres días sean distinguibles al comparar recorridos en el mapa.")
    print("-- NO toca el 2026-07-29 (no se borra ni se reinserta).")
    print("--")
    print("-- Verificado antes de generar: no existían filas reales para estos 10")
    print("-- códigos en 'ayer'/'hoy' (SELECT COUNT previo, 2026-07-31 hora Chile).")
    print("--")
    print("-- dbo.posicion queda con el último punto del día 'hoy' (procesado al")
    print("-- final), igual que exige el flujo de posición actual del mapa.")
    print("-- =====================================================================")
    print()
    print("DECLARE @fecha_hoy DATE = CAST(DATEADD(HOUR, -4, GETDATE()) AS DATE);  -- fecha local Chile, no UTC")
    print()

    codigos_sql = ", ".join(f"'{v['codigo']}'" for v in VENDEDORES)

    print("-- 1) Limpieza idempotente: solo de 'ayer' y 'hoy' para estos vendedores")
    print("--    (no toca el 2026-07-29)")
    for dia in DIAS:
        print(f"""DELETE FROM dbo.historial_posicion
WHERE vendedorId IN ({codigos_sql})
  AND CAST(fechaHora AS DATE) = DATEADD(DAY, {dia['offset_dias']}, @fecha_hoy);
""")

    print("-- 2) Historial de posiciones por día (uno de los bloques por vendedor)")
    print("INSERT INTO dbo.historial_posicion (vendedorId, vendedorCodigo, fechaHora, latitud, longitud)")
    print("VALUES")

    filas = []
    ultimo_punto_dia_hoy = {}
    for dia in DIAS:
        for v in VENDEDORES:
            puntos = generar_puntos_vendedor(v, dia)
            if dia["offset_dias"] == 0:
                ultimo_punto_dia_hoy[v["codigo"]] = puntos[-1]
            for minuto, lat, lon in puntos:
                fecha_expr = (
                    f"DATEADD(SECOND, {int(round(minuto * 60))}, "
                    f"CAST(DATEADD(DAY, {dia['offset_dias']}, @fecha_hoy) AS DATETIME2(0)))"
                )
                filas.append(f"    ('{v['codigo']}', '{TIPO}', {fecha_expr}, {lat}, {lon})")

    print(",\n".join(filas) + ";")
    print()

    print("-- 3) Posición actual = último punto del día 'hoy' (offset 0)")
    for v in VENDEDORES:
        minuto, lat, lon = ultimo_punto_dia_hoy[v["codigo"]]
        fecha_expr = (
            f"DATEADD(SECOND, {int(round(minuto * 60))}, CAST(@fecha_hoy AS DATETIME2(0)))"
        )
        print(f"""DELETE FROM dbo.posicion WHERE vendedorId = '{v['codigo']}' AND vendedorCodigo = '{TIPO}';
INSERT INTO dbo.posicion (vendedorId, vendedorCodigo, latitud, longitud, ultimaActualizacion)
VALUES ('{v['codigo']}', '{TIPO}', {lat}, {lon}, {fecha_expr});""")
    print()

    print("-- =====================================================================")
    print("-- Limpieza manual (solo 'ayer' y 'hoy' para estos vendedores; el 29 no")
    print("-- se toca acá):")
    print(f"-- DELETE FROM dbo.historial_posicion WHERE vendedorId IN ({codigos_sql}) AND CAST(fechaHora AS DATE) IN (CAST(DATEADD(DAY,-1,DATEADD(HOUR,-4,GETDATE())) AS DATE), CAST(DATEADD(HOUR,-4,GETDATE()) AS DATE));")
    print("-- =====================================================================")


if __name__ == "__main__":
    emitir_sql()

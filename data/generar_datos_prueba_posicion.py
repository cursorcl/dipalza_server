#!/usr/bin/env python3
"""
Genera un script T-SQL con datos de prueba de posición GPS para los 10
vendedores REALES (tipo='0') que existen hoy en dbo.vendedor, simulando un
recorrido del día distribuido en distintas comunas de la Región de Valparaíso,
para validar visualmente la detección de "nodos de parada" en el mapa
(dipalza_web_client).

Los códigos y nombres (001, 002, 003, 005, 010, 011, 012, 013, 033, 120) se
obtuvieron con:
    SELECT codigo, tipo, nombre, comuna, ciudad FROM dbo.vendedor WHERE tipo = '0';
contra la BD real (cursorcl.dynalias.com:1777/ventas) el 2026-07-29. Este
script NO inserta en dbo.vendedor (ya existen) — solo siembra historial de
posiciones y la posición actual para esos códigos.

IMPORTANTE: como son códigos de vendedores reales, estos datos de posición son
sintéticos pero quedarán mezclados con datos reales bajo esas mismas
identidades. Limpiar con la sección de DELETE al final del script cuando ya
no se necesiten para la validación.

Uso:
    python3 generar_datos_prueba_posicion.py > datos_prueba_10_vendedores_v_region.sql

Cada vendedor tiene una jornada simulada (08:30 en adelante, hora relativa al
día en que se ejecute el script) con 4 "paradas" reales de 15-40 minutos cada
una (para que detectarParadas las reconozca como nodos numerados), conectadas
por tramos de tránsito con varios puntos intermedios (para que la polyline se
vea como un recorrido real, sin que esos puntos de tránsito se agrupen como
parada).

Resultado esperado por vendedor al seleccionarlo en el mapa: 6 nodos numerados
(1 = Inicio, 2-5 = las 4 paradas reales, 6 = Última posición).
"""

import math

# Metros por grado en Chile central (~lat -33°), para desplazar coordenadas
METROS_POR_GRADO_LAT = 111_320
METROS_POR_GRADO_LON = 111_320 * math.cos(math.radians(33))


def desplazar(lat, lon, metros_norte, metros_este):
    """Desplaza una coordenada (lat, lon) por una distancia en metros."""
    dlat = metros_norte / METROS_POR_GRADO_LAT
    dlon = metros_este / METROS_POR_GRADO_LON
    return round(lat + dlat, 6), round(lon + dlon, 6)


# Los 10 vendedores reales con tipo='0' en dbo.vendedor (consultados en vivo).
# comuna/lat/lon asignados por este script para la simulación: se respeta la
# comuna real cuando dbo.vendedor la tenía informada (002, 005, 010 -> Viña
# del Mar; 011 -> Quilpué, cada uno en un sector distinto de la ciudad para
# que sus recorridos no se superpongan); para los que no tenían comuna
# informada se distribuyeron en el resto de la Región de Valparaíso.
VENDEDORES = [
    {"codigo": "001", "nombre": "Cobrador Unico", "comuna_sim": "Valparaíso", "lat": -33.0393, "lon": -71.6273},
    {"codigo": "002", "nombre": "CRISTIAN PAVEZ GALVEZ", "comuna_sim": "Viña del Mar (Chorrillos)", "lat": -33.0100, "lon": -71.5450},
    {"codigo": "003", "nombre": "OFICINA", "comuna_sim": "Concón", "lat": -32.9273, "lon": -71.5308},
    {"codigo": "005", "nombre": "CARLOS OLÓRTEGUI", "comuna_sim": "Viña del Mar (centro)", "lat": -33.0245, "lon": -71.5518},
    {"codigo": "010", "nombre": "JORGE ZAMORA", "comuna_sim": "Viña del Mar (Cerro Esperanza)", "lat": -32.9950, "lon": -71.5600},
    {"codigo": "011", "nombre": "JOSE OLIVARES", "comuna_sim": "Quilpué", "lat": -33.0472, "lon": -71.4425},
    {"codigo": "012", "nombre": "MAURO HUINON", "comuna_sim": "Villa Alemana", "lat": -33.0426, "lon": -71.3735},
    {"codigo": "013", "nombre": "GUSTAVO ITURRIETA", "comuna_sim": "San Antonio", "lat": -33.5928, "lon": -71.6108},
    {"codigo": "033", "nombre": "DEVOLUCION OFICINA", "comuna_sim": "Quillota", "lat": -32.8820, "lon": -71.2489},
    {"codigo": "120", "nombre": "DEVOLUCION VENCIDOS", "comuna_sim": "La Ligua", "lat": -32.4525, "lon": -71.2306},
]

TIPO = "0"

# Offsets deterministicos (metros norte, metros este) de las 4 paradas
# respecto al centro de cada comuna - separadas por varios cientos de metros
# a ~1.5km entre sí, bien por sobre el radio de agrupación de 100m.
OFFSETS_PARADAS = [
    (300, -400),
    (-250, 600),
    (700, 200),
    (-150, -700),
]

# Duración de cada parada en minutos (todas > 10 min)
DURACION_PARADAS_MIN = [18, 25, 15, 32]

# Minuto de inicio (desde 00:00) de cada parada, y minutos de tránsito entre ellas
MINUTO_INICIO_JORNADA = 8 * 60 + 30  # 08:30
MINUTOS_TRANSITO_ENTRE_PARADAS = 20  # tiempo de viaje simulado entre paradas
PUNTOS_TRANSITO_POR_TRAMO = 5
JITTER_PARADA_METROS = 8  # pequeño ruido GPS dentro de la misma parada


def generar_puntos_vendedor(vendedor):
    """
    Devuelve una lista de (minuto_del_dia, lat, lon) ordenada cronológicamente
    para un vendedor: inicio de jornada + 4 paradas + tramos de tránsito + fin.
    """
    puntos = []
    minuto_actual = MINUTO_INICIO_JORNADA

    paradas_coords = [
        desplazar(vendedor["lat"], vendedor["lon"], dn, de)
        for dn, de in OFFSETS_PARADAS
    ]

    # Punto de salida (antes de llegar a la primera parada), ligeramente
    # desplazado de la primera parada para que el primer tramo de tránsito
    # tenga sentido.
    lat_salida, lon_salida = desplazar(vendedor["lat"], vendedor["lon"], -600, -900)
    puntos.append((minuto_actual, lat_salida, lon_salida))
    minuto_actual += 6

    anterior_lat, anterior_lon = lat_salida, lon_salida

    for i, (lat_parada, lon_parada) in enumerate(paradas_coords):
        # Tramo de tránsito interpolado entre el punto anterior y esta parada
        for p in range(1, PUNTOS_TRANSITO_POR_TRAMO + 1):
            frac = p / (PUNTOS_TRANSITO_POR_TRAMO + 1)
            lat_i = round(anterior_lat + (lat_parada - anterior_lat) * frac, 6)
            lon_i = round(anterior_lon + (lon_parada - anterior_lon) * frac, 6)
            minuto_actual += MINUTOS_TRANSITO_ENTRE_PARADAS / (PUNTOS_TRANSITO_POR_TRAMO + 1)
            puntos.append((round(minuto_actual, 2), lat_i, lon_i))

        # Puntos dentro de la parada (con jitter pequeño, dentro del radio de 100m)
        duracion = DURACION_PARADAS_MIN[i]
        n_puntos_parada = max(3, duracion // 6)
        for k in range(n_puntos_parada):
            signo_lat = 1 if k % 2 == 0 else -1
            signo_lon = 1 if k % 3 == 0 else -1
            lat_j, lon_j = desplazar(lat_parada, lon_parada, signo_lat * JITTER_PARADA_METROS, signo_lon * JITTER_PARADA_METROS)
            minuto_punto = minuto_actual + (duracion * k / max(1, n_puntos_parada - 1)) if n_puntos_parada > 1 else minuto_actual
            puntos.append((round(minuto_punto, 2), lat_j, lon_j))

        minuto_actual += duracion
        anterior_lat, anterior_lon = lat_parada, lon_parada

    # Punto final de jornada, cerca de la última parada (última posición conocida)
    lat_fin, lon_fin = desplazar(anterior_lat, anterior_lon, 150, 100)
    minuto_actual += 8
    puntos.append((round(minuto_actual, 2), lat_fin, lon_fin))

    return _asegurar_orden_estricto(puntos)


def _asegurar_orden_estricto(puntos):
    """
    Garantiza que cada punto tenga un minuto estrictamente mayor al anterior
    (evita fechaHora duplicada entre el último punto de un tramo de tránsito
    y el primer punto de la parada siguiente, que puede coincidir por cómo
    se acumula minuto_actual entre etapas).
    """
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
    print("-- Datos de prueba: recorrido del día simulado para los 10 vendedores")
    print("-- REALES con tipo='0' (001,002,003,005,010,011,012,013,033,120),")
    print("-- distribuidos en distintas comunas de la Región de Valparaíso, para")
    print("-- validar la detección de nodos numerados de parada en el mapa")
    print("-- (dipalza_web_client).")
    print("--")
    print("-- Generado por data/generar_datos_prueba_posicion.py. NO inserta en")
    print("-- dbo.vendedor (esos 10 códigos ya existen) — solo siembra")
    print("-- dbo.historial_posicion y dbo.posicion. Las coordenadas/paradas son")
    print("-- sintéticas, aunque los códigos de vendedor son reales: limpiar con")
    print("-- la sección de DELETE al final cuando ya no se necesiten.")
    print("--")
    print("-- Las fechas se calculan relativas al día en que se ejecute este script")
    print("-- (DATEADD sobre CAST(GETDATE() AS DATE)), así siempre queda como \"hoy\"")
    print("-- para que el flujo de \"recorrido de hoy\" del mapa lo pueda consultar.")
    print("--")
    print("-- OJO: el servidor SQL corre en UTC (verificado con SYSDATETIMEOFFSET),")
    print("-- pero la fecha \"de hoy\" que usa el navegador (toLocaleDateString) y la")
    print("-- que llevan los datos reales de posición (hora local del teléfono del")
    print("-- vendedor) son hora de Chile (UTC-4 / UTC-3 con horario de verano). Por")
    print("-- eso @fecha se calcula restando 4 horas a GETDATE() antes de tomar la")
    print("-- fecha, no con CAST(GETDATE() AS DATE) directo — si no, entre ~20:00 y")
    print("-- 23:59 hora de Chile este script sembraría datos bajo la fecha de MAÑANA")
    print("-- en UTC, que no coincidiría con el \"hoy\" que consulta el navegador.")
    print("--")
    print("-- Es idempotente: primero borra cualquier historial/posición de HOY para")
    print("-- estos códigos de vendedor antes de volver a insertar.")
    print("-- =====================================================================")
    print()
    print("DECLARE @fecha DATE = CAST(DATEADD(HOUR, -4, GETDATE()) AS DATE);  -- fecha local Chile, no UTC")
    print()

    print("-- 1) Limpieza de datos de prueba previos de HOY para estos vendedores")
    print("--    (permite volver a ejecutar este script sin duplicar el historial)")
    codigos_sql = ", ".join(f"'{v['codigo']}'" for v in VENDEDORES)
    print(f"""DELETE FROM dbo.historial_posicion
WHERE vendedorId IN ({codigos_sql})
  AND CAST(fechaHora AS DATE) = @fecha;

DELETE FROM dbo.posicion
WHERE vendedorId IN ({codigos_sql});
""")

    print("-- 2) Historial de posiciones del día (jornada simulada con 4 paradas reales")
    print("--    de >10 minutos cada una, separadas por tramos de tránsito)")
    print("INSERT INTO dbo.historial_posicion (vendedorId, vendedorCodigo, fechaHora, latitud, longitud)")
    print("VALUES")

    filas = []
    ultimo_punto_por_vendedor = {}
    for v in VENDEDORES:
        puntos = generar_puntos_vendedor(v)
        ultimo_punto_por_vendedor[v["codigo"]] = puntos[-1]
        for minuto, lat, lon in puntos:
            fecha_expr = f"DATEADD(SECOND, {int(round(minuto * 60))}, CAST(@fecha AS DATETIME2(0)))"
            filas.append(f"    ('{v['codigo']}', '{TIPO}', {fecha_expr}, {lat}, {lon})")

    print(",\n".join(filas) + ";")
    print()

    print("-- 3) Posición actual (última conocida) de cada vendedor, para que también")
    print("--    aparezcan con marker en la vista en vivo del mapa")
    for v in VENDEDORES:
        minuto, lat, lon = ultimo_punto_por_vendedor[v["codigo"]]
        fecha_expr = f"DATEADD(SECOND, {int(round(minuto * 60))}, CAST(@fecha AS DATETIME2(0)))"
        print(f"""INSERT INTO dbo.posicion (vendedorId, vendedorCodigo, latitud, longitud, ultimaActualizacion)
VALUES ('{v['codigo']}', '{TIPO}', {lat}, {lon}, {fecha_expr});""")
    print()

    print("-- =====================================================================")
    print("-- Limpieza manual (ejecutar por separado cuando ya no se necesiten los")
    print("-- datos de prueba). NO borra de dbo.vendedor: son códigos de vendedores")
    print("-- reales, esta limpieza solo afecta la posición/historial sintéticos.")
    print("--")
    print(f"-- DELETE FROM dbo.historial_posicion WHERE vendedorId IN ({codigos_sql});")
    print(f"-- DELETE FROM dbo.posicion WHERE vendedorId IN ({codigos_sql});")
    print("-- =====================================================================")


if __name__ == "__main__":
    emitir_sql()

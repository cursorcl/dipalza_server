from dataclasses import dataclass

KMH_A_MS = 1000.0 / 3600.0


@dataclass
class SegmentoRuta:
    lat: float
    lon: float
    distancia_acum_m: float
    tiempo_acum_s: float


@dataclass
class Ruta:
    segmentos: list[SegmentoRuta]


def construir_ruta(route_osrm: dict, velocidad_ciudad_kmh: float = 50.0,
                    velocidad_interurbana_kmh: float = 100.0,
                    umbral_clasificacion_kmh: float = 70.0) -> Ruta:
    leg = route_osrm["legs"][0]
    coords = route_osrm["geometry"]["coordinates"]
    distancias = leg["annotation"]["distance"]
    velocidades_osrm_ms = leg["annotation"]["speed"]

    if len(coords) < 2:
        raise ValueError("La ruta de OSRM no tiene suficientes puntos")
    if len(distancias) != len(coords) - 1 or len(velocidades_osrm_ms) != len(coords) - 1:
        raise ValueError("Las anotaciones de OSRM no calzan con la geometria de la ruta")

    velocidad_ciudad_ms = velocidad_ciudad_kmh * KMH_A_MS
    velocidad_interurbana_ms = velocidad_interurbana_kmh * KMH_A_MS

    lon0, lat0 = coords[0]
    segmentos = [SegmentoRuta(lat=lat0, lon=lon0, distancia_acum_m=0.0, tiempo_acum_s=0.0)]
    distancia_acum = 0.0
    tiempo_acum = 0.0

    for i in range(len(distancias)):
        distancia_borde = distancias[i]
        velocidad_osrm_kmh = velocidades_osrm_ms[i] * 3.6
        velocidad_fija_ms = (
            velocidad_interurbana_ms if velocidad_osrm_kmh >= umbral_clasificacion_kmh
            else velocidad_ciudad_ms
        )
        tiempo_borde = distancia_borde / velocidad_fija_ms if velocidad_fija_ms > 0 else 0.0

        distancia_acum += distancia_borde
        tiempo_acum += tiempo_borde
        lon, lat = coords[i + 1]
        segmentos.append(SegmentoRuta(lat=lat, lon=lon, distancia_acum_m=distancia_acum, tiempo_acum_s=tiempo_acum))

    return Ruta(segmentos=segmentos)


def interpolar_posicion(ruta: Ruta, tiempo_transcurrido_s: float) -> tuple[float, float, bool]:
    segmentos = ruta.segmentos
    if tiempo_transcurrido_s <= 0:
        return segmentos[0].lat, segmentos[0].lon, False

    ultimo = segmentos[-1]
    if tiempo_transcurrido_s >= ultimo.tiempo_acum_s:
        return ultimo.lat, ultimo.lon, True

    for i in range(1, len(segmentos)):
        if segmentos[i].tiempo_acum_s >= tiempo_transcurrido_s:
            anterior, actual = segmentos[i - 1], segmentos[i]
            duracion_tramo = actual.tiempo_acum_s - anterior.tiempo_acum_s
            frac = (tiempo_transcurrido_s - anterior.tiempo_acum_s) / duracion_tramo if duracion_tramo > 0 else 0.0
            lat = anterior.lat + (actual.lat - anterior.lat) * frac
            lon = anterior.lon + (actual.lon - anterior.lon) * frac
            return lat, lon, False

    return ultimo.lat, ultimo.lon, True

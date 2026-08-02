import math
import random

METROS_POR_GRADO_LAT = 111_320


def _metros_por_grado_lon(lat: float) -> float:
    return 111_320 * math.cos(math.radians(lat))


def desplazar(lat: float, lon: float, metros_norte: float, metros_este: float) -> tuple[float, float]:
    dlat = metros_norte / METROS_POR_GRADO_LAT
    dlon = metros_este / _metros_por_grado_lon(lat)
    return (lat + dlat, lon + dlon)


def jitter_gps(lat: float, lon: float, rng: random.Random, radio_m: float = 6.0) -> tuple[float, float]:
    angulo = rng.uniform(0, 2 * math.pi)
    radio = rng.uniform(0, radio_m)
    return desplazar(lat, lon, radio * math.cos(angulo), radio * math.sin(angulo))

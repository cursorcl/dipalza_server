import random

SEGUNDOS_POR_MINUTO = 60

UMBRAL_PARADA_MIN_MIN = 60
UMBRAL_PARADA_MAX_MIN = 120
DURACION_PARADA_MIN_MIN = 10
DURACION_PARADA_MAX_MIN = 60


def sortear_umbral_proxima_parada_s(rng: random.Random) -> float:
    minutos = rng.uniform(UMBRAL_PARADA_MIN_MIN, UMBRAL_PARADA_MAX_MIN)
    return minutos * SEGUNDOS_POR_MINUTO


def sortear_duracion_parada_s(rng: random.Random) -> float:
    minutos = rng.uniform(DURACION_PARADA_MIN_MIN, DURACION_PARADA_MAX_MIN)
    return minutos * SEGUNDOS_POR_MINUTO

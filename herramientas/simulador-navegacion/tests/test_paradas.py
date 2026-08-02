import random

from simulador.paradas import sortear_duracion_parada_s, sortear_umbral_proxima_parada_s


def test_umbral_proxima_parada_en_rango_60_120_min():
    rng = random.Random(1)
    for _ in range(200):
        segundos = sortear_umbral_proxima_parada_s(rng)
        assert 60 * 60 <= segundos <= 120 * 60


def test_duracion_parada_en_rango_10_60_min():
    rng = random.Random(2)
    for _ in range(200):
        segundos = sortear_duracion_parada_s(rng)
        assert 10 * 60 <= segundos <= 60 * 60


def test_es_determinista_con_la_misma_semilla():
    rng1 = random.Random(99)
    rng2 = random.Random(99)
    assert sortear_umbral_proxima_parada_s(rng1) == sortear_umbral_proxima_parada_s(rng2)

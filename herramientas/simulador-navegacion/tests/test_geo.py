import math
import random

from simulador.geo import desplazar, jitter_gps


def test_desplazar_norte_aumenta_latitud():
    lat, lon = desplazar(-33.0, -71.0, metros_norte=1000, metros_este=0)
    assert lat > -33.0
    assert math.isclose(lon, -71.0, abs_tol=1e-9)


def test_desplazar_este_aumenta_longitud():
    lat, lon = desplazar(-33.0, -71.0, metros_norte=0, metros_este=1000)
    assert lon > -71.0
    assert math.isclose(lat, -33.0, abs_tol=1e-9)


def test_desplazar_1000m_norte_es_aproximadamente_0_009_grados():
    lat, _ = desplazar(-33.0, -71.0, metros_norte=1000, metros_este=0)
    assert math.isclose(lat - (-33.0), 1000 / 111_320, rel_tol=1e-6)


def test_jitter_gps_se_mantiene_dentro_del_radio():
    rng = random.Random(42)
    lat0, lon0 = -33.0, -71.0
    for _ in range(50):
        lat, lon = jitter_gps(lat0, lon0, rng, radio_m=6.0)
        dlat_m = (lat - lat0) * 111_320
        dlon_m = (lon - lon0) * 111_320 * math.cos(math.radians(lat0))
        distancia = math.hypot(dlat_m, dlon_m)
        assert distancia <= 6.0 + 1e-6


def test_jitter_gps_es_determinista_con_la_misma_semilla():
    rng1 = random.Random(7)
    rng2 = random.Random(7)
    assert jitter_gps(-33.0, -71.0, rng1) == jitter_gps(-33.0, -71.0, rng2)

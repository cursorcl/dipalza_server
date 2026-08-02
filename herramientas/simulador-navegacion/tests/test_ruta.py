import pytest

from simulador.ruta import construir_ruta, interpolar_posicion


def _route_osrm_sintetica():
    # 3 puntos sobre el mismo paralelo (misma latitud) para que solo la
    # longitud varíe y las comparaciones de posición sean directas.
    # Tramo 0->1: 1000 m, OSRM reporta 30 km/h -> clasificado "ciudad".
    # Tramo 1->2: 2000 m, OSRM reporta 90 km/h -> clasificado "interurbano".
    return {
        "geometry": {"coordinates": [[-71.0, -33.0], [-70.99, -33.0], [-70.97, -33.0]]},
        "legs": [{
            "annotation": {
                "distance": [1000.0, 2000.0],
                "speed": [30 / 3.6, 90 / 3.6],
            }
        }],
    }


def test_construir_ruta_clasifica_tramos_por_velocidad_osrm():
    ruta = construir_ruta(_route_osrm_sintetica())
    assert len(ruta.segmentos) == 3
    assert ruta.segmentos[0].tiempo_acum_s == 0.0

    velocidad_ciudad_ms = 50 / 3.6
    velocidad_interurbana_ms = 100 / 3.6
    tiempo_esperado_tramo1 = 1000.0 / velocidad_ciudad_ms
    tiempo_esperado_tramo2 = tiempo_esperado_tramo1 + 2000.0 / velocidad_interurbana_ms

    assert ruta.segmentos[1].tiempo_acum_s == pytest.approx(tiempo_esperado_tramo1)
    assert ruta.segmentos[2].tiempo_acum_s == pytest.approx(tiempo_esperado_tramo2)


def test_construir_ruta_valida_anotaciones_incompletas():
    datos = _route_osrm_sintetica()
    datos["legs"][0]["annotation"]["distance"] = [1000.0]  # falta un elemento
    with pytest.raises(ValueError):
        construir_ruta(datos)


def test_interpolar_posicion_en_extremos():
    ruta = construir_ruta(_route_osrm_sintetica())

    lat0, lon0, terminado0 = interpolar_posicion(ruta, 0.0)
    assert (lat0, lon0) == (ruta.segmentos[0].lat, ruta.segmentos[0].lon)
    assert terminado0 is False

    tiempo_total = ruta.segmentos[-1].tiempo_acum_s
    lat_fin, lon_fin, terminado_fin = interpolar_posicion(ruta, tiempo_total)
    assert (lat_fin, lon_fin) == (ruta.segmentos[-1].lat, ruta.segmentos[-1].lon)
    assert terminado_fin is True

    lat_mas, lon_mas, terminado_mas = interpolar_posicion(ruta, tiempo_total + 100)
    assert (lat_mas, lon_mas) == (ruta.segmentos[-1].lat, ruta.segmentos[-1].lon)
    assert terminado_mas is True


def test_interpolar_posicion_a_mitad_de_tramo():
    ruta = construir_ruta(_route_osrm_sintetica())
    tiempo_medio_tramo1 = ruta.segmentos[1].tiempo_acum_s / 2

    lat, lon, terminado = interpolar_posicion(ruta, tiempo_medio_tramo1)

    assert terminado is False
    assert lat == pytest.approx(-33.0)
    assert ruta.segmentos[0].lon < lon < ruta.segmentos[1].lon

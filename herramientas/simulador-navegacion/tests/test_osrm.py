from unittest.mock import Mock, patch

import pytest

from simulador.osrm import consultar_ruta_osrm


def _respuesta_ok():
    resp = Mock()
    resp.json.return_value = {
        "code": "Ok",
        "routes": [{
            "legs": [{"annotation": {"distance": [10.0], "speed": [5.0]}}],
            "geometry": {"coordinates": [[-71.0, -33.0], [-71.0001, -33.0]]},
        }],
    }
    return resp


def _respuesta_error():
    resp = Mock()
    resp.json.return_value = {"code": "NoRoute"}
    return resp


@patch("simulador.osrm.requests.get")
def test_consultar_ruta_osrm_exitoso_al_primer_intento(mock_get):
    mock_get.return_value = _respuesta_ok()
    ruta = consultar_ruta_osrm((-33.0, -71.0), (-33.0, -71.001))
    assert ruta["legs"][0]["annotation"]["distance"] == [10.0]
    assert mock_get.call_count == 1


@patch("simulador.osrm.time.sleep", return_value=None)
@patch("simulador.osrm.requests.get")
def test_consultar_ruta_osrm_agota_reintentos_y_lanza_error(mock_get, mock_sleep):
    mock_get.return_value = _respuesta_error()
    with pytest.raises(RuntimeError):
        consultar_ruta_osrm((-33.0, -71.0), (-33.0, -71.001), intentos=3)
    assert mock_get.call_count == 3


@patch("simulador.osrm.time.sleep", return_value=None)
@patch("simulador.osrm.requests.get")
def test_consultar_ruta_osrm_reintenta_tras_fallo_de_red_y_luego_funciona(mock_get, mock_sleep):
    mock_get.side_effect = [ConnectionError("boom"), _respuesta_ok()]
    ruta = consultar_ruta_osrm((-33.0, -71.0), (-33.0, -71.001), intentos=3)
    assert ruta["legs"][0]["annotation"]["distance"] == [10.0]
    assert mock_get.call_count == 2

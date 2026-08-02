from unittest.mock import patch

from simulador.main import construir_vendedores


def _route_osrm_sintetica():
    return {
        "geometry": {"coordinates": [[-71.0, -33.0], [-70.99, -33.0]]},
        "legs": [{"annotation": {"distance": [500.0], "speed": [10.0]}}],
    }


def _config_un_vendedor():
    return {"vendedores": [{
        "codigo": "005", "tipo": "0",
        "latInicio": -33.0, "lonInicio": -71.0,
        "latFin": -33.0, "lonFin": -70.99,
    }]}


@patch("simulador.main.consultar_ruta_osrm")
def test_construir_vendedores_arma_rutas_ida_y_vuelta(mock_consultar):
    mock_consultar.return_value = _route_osrm_sintetica()
    vendedores = construir_vendedores(_config_un_vendedor())
    assert len(vendedores) == 1
    assert vendedores[0].codigo == "005"
    assert mock_consultar.call_count == 2


@patch("simulador.main.consultar_ruta_osrm")
def test_construir_vendedores_omite_vendedor_si_osrm_falla(mock_consultar):
    mock_consultar.side_effect = RuntimeError("sin ruta")
    vendedores = construir_vendedores(_config_un_vendedor())
    assert vendedores == []

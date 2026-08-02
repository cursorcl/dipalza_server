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


def _config_dos_vendedores():
    return {"vendedores": [
        {
            "codigo": "001", "tipo": "0",
            "latInicio": -33.0, "lonInicio": -71.0,
            "latFin": -33.0, "lonFin": -70.99,
        },
        {
            "codigo": "005", "tipo": "0",
            "latInicio": -33.1, "lonInicio": -71.1,
            "latFin": -33.1, "lonFin": -70.9,
        },
    ]}


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


@patch("simulador.main.consultar_ruta_osrm")
def test_construir_vendedores_continua_tras_fallo_de_osrm(mock_consultar):
    """
    Verifica que construir_vendedores continúa con el siguiente vendedor
    si uno falla en la consulta OSRM (no aborta ni regresa lista vacía).
    Distingue entre continue correcto y return/break prematuro.
    """
    mock_consultar.side_effect = [
        RuntimeError("sin ruta"),      # Vendor 001 ida: raises
        _route_osrm_sintetica(),       # Vendor 005 ida: succeeds
        _route_osrm_sintetica(),       # Vendor 005 vuelta: succeeds
    ]
    vendedores = construir_vendedores(_config_dos_vendedores())
    assert len(vendedores) == 1
    assert vendedores[0].codigo == "005"

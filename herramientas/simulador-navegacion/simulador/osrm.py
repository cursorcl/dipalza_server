import time

import requests

OSRM_BASE = "https://router.project-osrm.org/route/v1/driving/"


def consultar_ruta_osrm(origen: tuple[float, float], destino: tuple[float, float],
                         intentos: int = 4, timeout: int = 25) -> dict:
    """origen/destino: (lat, lon). Devuelve route_osrm (data['routes'][0]) con
    annotations completas, o lanza RuntimeError tras agotar los reintentos."""
    lat1, lon1 = origen
    lat2, lon2 = destino
    coords_url = f"{lon1:.6f},{lat1:.6f};{lon2:.6f},{lat2:.6f}"
    ultimo_error: object = None

    for intento in range(intentos):
        try:
            resp = requests.get(
                OSRM_BASE + coords_url,
                params={"overview": "full", "geometries": "geojson", "steps": "true", "annotations": "true"},
                timeout=timeout,
            )
            data = resp.json()
        except Exception as exc:  # noqa: BLE001 - se reintenta ante cualquier falla de red
            ultimo_error = exc
            time.sleep(0.5 * (intento + 1))
            continue

        if data.get("code") == "Ok":
            return data["routes"][0]

        ultimo_error = data
        time.sleep(0.5 * (intento + 1))

    raise RuntimeError(f"OSRM no pudo rutear tras {intentos} intentos: {ultimo_error}")

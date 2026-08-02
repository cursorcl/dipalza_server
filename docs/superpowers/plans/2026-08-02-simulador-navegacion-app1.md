# Simulador de navegación por calles (App 1) — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir un servicio Python independiente que simule uno o más
vendedores moviéndose por calles reales (OSRM) entre una posición inicial y
una final (y de vuelta), con paradas aleatorias, y emita cada posición en
tiempo real por WebSocket.

**Architecture:** Proceso `asyncio` standalone. Al arrancar, para cada
vendedor de `config.json` se precalculan dos rutas OSRM (ida y vuelta) con
anotaciones de velocidad, que se convierten en una tabla de tiempo-simulado
acumulado usando velocidades fijas (50/100 km/h) clasificadas por el tipo de
vía que reporta OSRM. Cada vendedor corre como una tarea `asyncio`
independiente con una máquina de estados simple (en movimiento / detenido /
ciclo completo), emitiendo su posición cada 30s por WebSocket a todos los
clientes conectados. Un cliente conectado puede enviar un comando de
"reiniciar" para que un vendedor vuelva a arrancar desde el inicio.

**Tech Stack:** Python 3.11+, `asyncio`, `websockets`, `requests` (consultas
a OSRM), `pytest` + `pytest-asyncio` para tests.

## Global Constraints

- Ubicación del proyecto: `herramientas/simulador-navegacion/` dentro del
  repo `dipalza_server`. Paquete Python `simulador/`.
- Velocidad fija en ciudad: **50 km/h**. Velocidad fija fuera de ciudad:
  **100 km/h**. Umbral de clasificación sobre la velocidad que anota OSRM
  para cada tramo: **70 km/h** (≥70 ⇒ interurbano, <70 ⇒ ciudad).
  Estos valores nunca se hardcodean dos veces — quedan como constantes con
  default en `construir_ruta()` (Task 3).
- Intervalo de emisión de posición: **30 segundos** (constante
  `INTERVALO_EMISION_S` en `servidor.py`, parametrizable en el
  constructor de `Simulador` para que los tests no dependan de tiempo real).
- Próxima parada: umbral aleatorio uniforme en **[60, 120] minutos** de
  tiempo en movimiento. Duración de la parada: aleatoria uniforme en
  **[10, 60] minutos**. El temporizador de "próxima parada" es continuo a
  través de todo el ciclo ida+vuelta — solo se reinicia al ocurrir una
  parada real o al reiniciar el vendedor.
- Jitter GPS durante una parada: desplazamiento aleatorio dentro de un
  radio de **6 metros** del punto de parada, en cada tick de emisión.
- Resolución de las transiciones de estado (llegar a destino, cruzar el
  umbral de parada, terminar una parada) es de **un tick de emisión**
  (30s) — no se sub-divide dentro de un tick. Es una simplificación
  aceptada para una herramienta de pruebas, no para tráfico real.
- App 1 **nunca** escribe en la base de datos ni conoce los entry points
  HTTP reales del sistema (`/api/posicion`, etc.) — eso es responsabilidad
  de la futura App 2. App 1 solo calcula movimiento y lo emite por
  WebSocket.
- Todas las funciones de cálculo (clasificación de tramos, interpolación
  de posición, sorteo de paradas) deben ser funciones puras, testeables
  sin reloj real ni red.
- Las consultas a OSRM se mockean en los tests (sin dependencia de red
  real en la suite de tests, salvo el test de humo end-to-end que no
  llama a OSRM — usa una ruta sintética).
- No agregar dependencias del backend Java/Spring ni de Maven — proceso
  Python standalone, ejecutable de forma independiente.

---

### Task 1: Estructura del proyecto y utilidades geográficas (`geo.py`)

**Files:**
- Create: `herramientas/simulador-navegacion/simulador/__init__.py`
- Create: `herramientas/simulador-navegacion/simulador/geo.py`
- Create: `herramientas/simulador-navegacion/pyproject.toml`
- Test: `herramientas/simulador-navegacion/tests/__init__.py`
- Test: `herramientas/simulador-navegacion/tests/test_geo.py`

**Interfaces:**
- Produces: `desplazar(lat: float, lon: float, metros_norte: float, metros_este: float) -> tuple[float, float]`
- Produces: `jitter_gps(lat: float, lon: float, rng: random.Random, radio_m: float = 6.0) -> tuple[float, float]`

- [ ] **Step 1: Crear la estructura de carpetas y archivos vacíos**

```bash
mkdir -p herramientas/simulador-navegacion/simulador
mkdir -p herramientas/simulador-navegacion/tests
touch herramientas/simulador-navegacion/simulador/__init__.py
touch herramientas/simulador-navegacion/tests/__init__.py
```

- [ ] **Step 2: Escribir `pyproject.toml`**

```toml
[tool.pytest.ini_options]
asyncio_mode = "auto"
```

- [ ] **Step 3: Escribir el test que falla**

`herramientas/simulador-navegacion/tests/test_geo.py`:

```python
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
```

- [ ] **Step 4: Ejecutar y verificar que falla**

Desde `herramientas/simulador-navegacion/`:
```bash
python3 -m venv .venv && source .venv/bin/activate
pip install pytest
pytest tests/test_geo.py -v
```
Esperado: FAIL con `ModuleNotFoundError: No module named 'simulador.geo'`.

- [ ] **Step 5: Implementar `geo.py`**

```python
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
```

- [ ] **Step 6: Ejecutar y verificar que pasa**

```bash
pytest tests/test_geo.py -v
```
Esperado: 5 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add herramientas/simulador-navegacion/
git commit -m "feat: estructura del proyecto y utilidades geograficas del simulador"
```

---

### Task 2: Consulta a OSRM con reintentos (`osrm.py`)

**Files:**
- Create: `herramientas/simulador-navegacion/simulador/osrm.py`
- Test: `herramientas/simulador-navegacion/tests/test_osrm.py`

**Interfaces:**
- Consumes: nada de tasks previas.
- Produces: `consultar_ruta_osrm(origen: tuple[float, float], destino: tuple[float, float], intentos: int = 4, timeout: int = 25) -> dict` — `origen`/`destino` son `(lat, lon)`. Devuelve el objeto `route` de OSRM (`data["routes"][0]`, con `annotations` y `geometry` completos) o lanza `RuntimeError` tras agotar los reintentos.

- [ ] **Step 1: Escribir el test que falla**

`herramientas/simulador-navegacion/tests/test_osrm.py`:

```python
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
```

- [ ] **Step 2: Ejecutar y verificar que falla**

```bash
pytest tests/test_osrm.py -v
```
Esperado: FAIL con `ModuleNotFoundError: No module named 'simulador.osrm'`.

- [ ] **Step 3: Implementar `osrm.py`**

```python
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
```

- [ ] **Step 4: Ejecutar y verificar que pasa**

```bash
pytest tests/test_osrm.py -v
```
Esperado: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add herramientas/simulador-navegacion/simulador/osrm.py herramientas/simulador-navegacion/tests/test_osrm.py
git commit -m "feat: consulta a OSRM con reintentos para el simulador"
```

---

### Task 3: Tabla de ruta con tiempo simulado e interpolación (`ruta.py`)

**Files:**
- Create: `herramientas/simulador-navegacion/simulador/ruta.py`
- Test: `herramientas/simulador-navegacion/tests/test_ruta.py`

**Interfaces:**
- Consumes: el shape de `dict` que devuelve `consultar_ruta_osrm()` (Task 2) — específicamente `route_osrm["legs"][0]["annotation"]["distance"]`, `route_osrm["legs"][0]["annotation"]["speed"]`, `route_osrm["geometry"]["coordinates"]` (lista de `[lon, lat]`).
- Produces:
  - `@dataclass SegmentoRuta(lat: float, lon: float, distancia_acum_m: float, tiempo_acum_s: float)`
  - `@dataclass Ruta(segmentos: list[SegmentoRuta])`
  - `construir_ruta(route_osrm: dict, velocidad_ciudad_kmh: float = 50.0, velocidad_interurbana_kmh: float = 100.0, umbral_clasificacion_kmh: float = 70.0) -> Ruta`
  - `interpolar_posicion(ruta: Ruta, tiempo_transcurrido_s: float) -> tuple[float, float, bool]` — devuelve `(lat, lon, terminado)`; `terminado=True` cuando `tiempo_transcurrido_s` alcanza o supera la duración total de la ruta.

- [ ] **Step 1: Escribir el test que falla**

`herramientas/simulador-navegacion/tests/test_ruta.py`:

```python
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
```

- [ ] **Step 2: Ejecutar y verificar que falla**

```bash
pytest tests/test_ruta.py -v
```
Esperado: FAIL con `ModuleNotFoundError: No module named 'simulador.ruta'`.

- [ ] **Step 3: Implementar `ruta.py`**

```python
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
```

- [ ] **Step 4: Ejecutar y verificar que pasa**

```bash
pytest tests/test_ruta.py -v
```
Esperado: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add herramientas/simulador-navegacion/simulador/ruta.py herramientas/simulador-navegacion/tests/test_ruta.py
git commit -m "feat: tabla de ruta con tiempo simulado e interpolacion de posicion"
```

---

### Task 4: Sorteo de paradas aleatorias (`paradas.py`)

**Files:**
- Create: `herramientas/simulador-navegacion/simulador/paradas.py`
- Test: `herramientas/simulador-navegacion/tests/test_paradas.py`

**Interfaces:**
- Consumes: nada de tasks previas.
- Produces:
  - `sortear_umbral_proxima_parada_s(rng: random.Random) -> float` — segundos, uniforme en [60, 120] minutos.
  - `sortear_duracion_parada_s(rng: random.Random) -> float` — segundos, uniforme en [10, 60] minutos.

- [ ] **Step 1: Escribir el test que falla**

`herramientas/simulador-navegacion/tests/test_paradas.py`:

```python
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
```

- [ ] **Step 2: Ejecutar y verificar que falla**

```bash
pytest tests/test_paradas.py -v
```
Esperado: FAIL con `ModuleNotFoundError: No module named 'simulador.paradas'`.

- [ ] **Step 3: Implementar `paradas.py`**

```python
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
```

- [ ] **Step 4: Ejecutar y verificar que pasa**

```bash
pytest tests/test_paradas.py -v
```
Esperado: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add herramientas/simulador-navegacion/simulador/paradas.py herramientas/simulador-navegacion/tests/test_paradas.py
git commit -m "feat: sorteo de umbral y duracion de paradas aleatorias"
```

---

### Task 5: Máquina de estados por vendedor (`vendedor.py`)

**Files:**
- Create: `herramientas/simulador-navegacion/simulador/vendedor.py`
- Test: `herramientas/simulador-navegacion/tests/test_vendedor.py`

**Interfaces:**
- Consumes: `Ruta`, `interpolar_posicion` (Task 3); `sortear_umbral_proxima_parada_s`, `sortear_duracion_parada_s` (Task 4); `jitter_gps` (Task 1).
- Produces:
  - `class EstadoVendedor(Enum)`: `INACTIVO`, `EN_MOVIMIENTO`, `DETENIDO`, `CICLO_COMPLETO`.
  - `@dataclass class VendedorSimulacion(codigo: str, tipo: str, ruta_ida: Ruta, ruta_vuelta: Ruta, rng: random.Random, estado: EstadoVendedor = INACTIVO, tramo: str = "ida", tiempo_en_tramo_s: float = 0.0, tiempo_hasta_proxima_parada_s: float = 0.0, tiempo_restante_parada_s: float = 0.0, posicion_detenida: tuple[float, float] | None = None)`
  - `VendedorSimulacion.iniciar(self) -> None`
  - `VendedorSimulacion.avanzar(self, dt_s: float) -> dict` — mensaje `{"tipo": "posicion", "vendedorCodigo": ..., "vendedorTipo": ..., "latitud": ..., "longitud": ..., "timestamp": ...}`. Lanza `RuntimeError` si se llama en estado `INACTIVO` o `CICLO_COMPLETO`.

- [ ] **Step 1: Escribir el test que falla**

`herramientas/simulador-navegacion/tests/test_vendedor.py`:

```python
import random

import pytest

from simulador.ruta import Ruta, SegmentoRuta
from simulador.vendedor import EstadoVendedor, VendedorSimulacion


def _ruta_recta(distancia_total_m: float, tiempo_total_s: float) -> Ruta:
    return Ruta(segmentos=[
        SegmentoRuta(lat=-33.0, lon=-71.0, distancia_acum_m=0.0, tiempo_acum_s=0.0),
        SegmentoRuta(lat=-33.0, lon=-70.9, distancia_acum_m=distancia_total_m, tiempo_acum_s=tiempo_total_s),
    ])


def _vendedor_de_prueba(tiempo_ida_s=100.0, tiempo_vuelta_s=100.0, semilla=1) -> VendedorSimulacion:
    return VendedorSimulacion(
        codigo="005",
        tipo="0",
        ruta_ida=_ruta_recta(1000.0, tiempo_ida_s),
        ruta_vuelta=_ruta_recta(1000.0, tiempo_vuelta_s),
        rng=random.Random(semilla),
    )


def test_iniciar_deja_al_vendedor_en_movimiento_tramo_ida():
    v = _vendedor_de_prueba()
    v.iniciar()
    assert v.estado == EstadoVendedor.EN_MOVIMIENTO
    assert v.tramo == "ida"
    assert v.tiempo_hasta_proxima_parada_s >= 60 * 60


def test_avanzar_en_movimiento_produce_mensaje_de_posicion():
    v = _vendedor_de_prueba()
    v.iniciar()
    v.tiempo_hasta_proxima_parada_s = 10_000  # evita que dispare una parada en este test
    mensaje = v.avanzar(30)
    assert mensaje["tipo"] == "posicion"
    assert mensaje["vendedorCodigo"] == "005"
    assert v.tiempo_en_tramo_s == 30


def test_llegar_al_final_de_ida_pasa_a_tramo_vuelta():
    v = _vendedor_de_prueba(tiempo_ida_s=30.0, tiempo_vuelta_s=1000.0)
    v.iniciar()
    v.tiempo_hasta_proxima_parada_s = 10_000
    v.avanzar(30)  # llega exactamente al final del tramo ida
    assert v.tramo == "vuelta"
    assert v.tiempo_en_tramo_s == 0.0
    assert v.estado == EstadoVendedor.EN_MOVIMIENTO


def test_llegar_al_final_de_vuelta_marca_ciclo_completo():
    v = _vendedor_de_prueba(tiempo_ida_s=30.0, tiempo_vuelta_s=30.0)
    v.iniciar()
    v.tiempo_hasta_proxima_parada_s = 10_000
    v.avanzar(30)  # fin de ida
    v.avanzar(30)  # fin de vuelta
    assert v.estado == EstadoVendedor.CICLO_COMPLETO


def test_umbral_de_parada_alcanzado_pasa_a_detenido():
    v = _vendedor_de_prueba(tiempo_ida_s=1000.0)
    v.iniciar()
    v.tiempo_hasta_proxima_parada_s = 20  # se cumplira en el primer tick de 30s
    v.avanzar(30)
    assert v.estado == EstadoVendedor.DETENIDO
    assert v.tiempo_restante_parada_s >= 10 * 60


def test_detenido_emite_posiciones_y_luego_reanuda():
    v = _vendedor_de_prueba(tiempo_ida_s=1000.0)
    v.iniciar()
    v.tiempo_hasta_proxima_parada_s = 20
    v.avanzar(30)  # entra en DETENIDO
    assert v.estado == EstadoVendedor.DETENIDO

    v.tiempo_restante_parada_s = 25  # forzamos que el proximo tick la termine
    mensaje = v.avanzar(30)
    assert v.estado == EstadoVendedor.EN_MOVIMIENTO
    assert mensaje["tipo"] == "posicion"


def test_avanzar_en_estado_inactivo_lanza_error():
    v = _vendedor_de_prueba()
    with pytest.raises(RuntimeError):
        v.avanzar(30)
```

- [ ] **Step 2: Ejecutar y verificar que falla**

```bash
pytest tests/test_vendedor.py -v
```
Esperado: FAIL con `ModuleNotFoundError: No module named 'simulador.vendedor'`.

- [ ] **Step 3: Implementar `vendedor.py`**

```python
from __future__ import annotations

import random
from dataclasses import dataclass
from datetime import datetime
from enum import Enum, auto

from .geo import jitter_gps
from .paradas import sortear_duracion_parada_s, sortear_umbral_proxima_parada_s
from .ruta import Ruta, interpolar_posicion


class EstadoVendedor(Enum):
    INACTIVO = auto()
    EN_MOVIMIENTO = auto()
    DETENIDO = auto()
    CICLO_COMPLETO = auto()


@dataclass
class VendedorSimulacion:
    codigo: str
    tipo: str
    ruta_ida: Ruta
    ruta_vuelta: Ruta
    rng: random.Random
    estado: EstadoVendedor = EstadoVendedor.INACTIVO
    tramo: str = "ida"
    tiempo_en_tramo_s: float = 0.0
    tiempo_hasta_proxima_parada_s: float = 0.0
    tiempo_restante_parada_s: float = 0.0
    posicion_detenida: tuple[float, float] | None = None

    def iniciar(self) -> None:
        self.estado = EstadoVendedor.EN_MOVIMIENTO
        self.tramo = "ida"
        self.tiempo_en_tramo_s = 0.0
        self.tiempo_hasta_proxima_parada_s = sortear_umbral_proxima_parada_s(self.rng)
        self.tiempo_restante_parada_s = 0.0
        self.posicion_detenida = None

    def avanzar(self, dt_s: float) -> dict:
        if self.estado == EstadoVendedor.DETENIDO:
            return self._avanzar_detenido(dt_s)
        if self.estado == EstadoVendedor.EN_MOVIMIENTO:
            return self._avanzar_en_movimiento(dt_s)
        raise RuntimeError(f"avanzar() llamado en estado invalido: {self.estado}")

    def _avanzar_detenido(self, dt_s: float) -> dict:
        self.tiempo_restante_parada_s -= dt_s
        lat, lon = self.posicion_detenida
        if self.tiempo_restante_parada_s <= 0:
            self.estado = EstadoVendedor.EN_MOVIMIENTO
            self.posicion_detenida = None
            self.tiempo_hasta_proxima_parada_s = sortear_umbral_proxima_parada_s(self.rng)
        else:
            lat, lon = jitter_gps(lat, lon, self.rng)
        return self._mensaje_posicion(lat, lon)

    def _avanzar_en_movimiento(self, dt_s: float) -> dict:
        self.tiempo_en_tramo_s += dt_s
        self.tiempo_hasta_proxima_parada_s -= dt_s
        ruta = self.ruta_ida if self.tramo == "ida" else self.ruta_vuelta
        lat, lon, terminado = interpolar_posicion(ruta, self.tiempo_en_tramo_s)

        if terminado:
            if self.tramo == "ida":
                self.tramo = "vuelta"
                self.tiempo_en_tramo_s = 0.0
            else:
                self.estado = EstadoVendedor.CICLO_COMPLETO
        elif self.tiempo_hasta_proxima_parada_s <= 0:
            self.estado = EstadoVendedor.DETENIDO
            self.tiempo_restante_parada_s = sortear_duracion_parada_s(self.rng)
            self.posicion_detenida = (lat, lon)

        return self._mensaje_posicion(lat, lon)

    def _mensaje_posicion(self, lat: float, lon: float) -> dict:
        return {
            "tipo": "posicion",
            "vendedorCodigo": self.codigo,
            "vendedorTipo": self.tipo,
            "latitud": lat,
            "longitud": lon,
            "timestamp": datetime.now().isoformat(timespec="seconds"),
        }
```

- [ ] **Step 4: Ejecutar y verificar que pasa**

```bash
pytest tests/test_vendedor.py -v
```
Esperado: 8 tests PASS (incluye el test de resorteo del umbral al reanudar de una parada, agregado en la revisión de esta task — ver nota más abajo).

- [ ] **Step 5: Commit**

```bash
git add herramientas/simulador-navegacion/simulador/vendedor.py herramientas/simulador-navegacion/tests/test_vendedor.py
git commit -m "feat: maquina de estados del vendedor simulado"
```

---

### Task 6: Servidor WebSocket y difusión (`servidor.py`)

**Files:**
- Create: `herramientas/simulador-navegacion/simulador/servidor.py`
- Modify: `herramientas/simulador-navegacion/pyproject.toml` (agregar dependencias de test)
- Test: `herramientas/simulador-navegacion/tests/test_servidor.py`

**Interfaces:**
- Consumes: `EstadoVendedor`, `VendedorSimulacion` (Task 5).
- Produces:
  - `INTERVALO_EMISION_S: float = 30`
  - `class Simulador(vendedores: list[VendedorSimulacion], intervalo_emision_s: float = INTERVALO_EMISION_S)`
  - `Simulador.difundir(self, mensaje: dict) -> None` (async) — envía a todos los `self.clientes`, descarta los que fallan al enviar.
  - `Simulador.tarea_vendedor(self, vendedor: VendedorSimulacion) -> None` (async, loop infinito) — corre el ciclo de vida completo de un vendedor (iniciar → mover hasta ciclo completo → difundir evento `ciclo_completo` → esperar reinicio → difundir evento `reiniciado` → repetir).
  - `Simulador.manejar_cliente(self, websocket) -> None` (async) — agrega/quita el websocket de `self.clientes`, procesa comandos entrantes.
  - `Simulador.ejecutar_vendedores(self) -> None` (async) — `asyncio.gather` de `tarea_vendedor` para todos los vendedores.
  - `self.eventos_reinicio: dict[str, asyncio.Event]` — un `Event` por código de vendedor.

- [ ] **Step 1: Actualizar `pyproject.toml` con dependencias de test**

`herramientas/simulador-navegacion/pyproject.toml`:

```toml
[tool.pytest.ini_options]
asyncio_mode = "auto"
```

(Sin cambios de contenido — el `asyncio_mode = "auto"` ya definido en Task 1 habilita `pytest-asyncio` en modo automático; solo se documenta aquí que Task 6 es la primera que depende de tests `async def`.)

Instalar la dependencia de test:

```bash
pip install pytest-asyncio websockets
```

- [ ] **Step 2: Escribir el test que falla**

`herramientas/simulador-navegacion/tests/test_servidor.py`:

```python
import asyncio
import json
import random

import pytest

from simulador.ruta import Ruta, SegmentoRuta
from simulador.servidor import Simulador
from simulador.vendedor import VendedorSimulacion


class ClienteFalso:
    def __init__(self):
        self.mensajes = []

    async def send(self, data):
        self.mensajes.append(json.loads(data))


class ClienteRoto:
    async def send(self, data):
        raise ConnectionError("cerrado")


def _ruta_recta(distancia_total_m: float, tiempo_total_s: float) -> Ruta:
    return Ruta(segmentos=[
        SegmentoRuta(lat=-33.0, lon=-71.0, distancia_acum_m=0.0, tiempo_acum_s=0.0),
        SegmentoRuta(lat=-33.0, lon=-70.9, distancia_acum_m=distancia_total_m, tiempo_acum_s=tiempo_total_s),
    ])


def _vendedor(codigo="005", tiempo_s=0.01):
    return VendedorSimulacion(
        codigo=codigo, tipo="0",
        ruta_ida=_ruta_recta(1.0, tiempo_s),
        ruta_vuelta=_ruta_recta(1.0, tiempo_s),
        rng=random.Random(1),
    )


async def test_difundir_envia_a_todos_los_clientes_conectados():
    simulador = Simulador([_vendedor()], intervalo_emision_s=0.01)
    c1, c2 = ClienteFalso(), ClienteFalso()
    simulador.clientes = {c1, c2}
    await simulador.difundir({"tipo": "evento", "evento": "prueba"})
    assert c1.mensajes == [{"tipo": "evento", "evento": "prueba"}]
    assert c2.mensajes == [{"tipo": "evento", "evento": "prueba"}]


async def test_difundir_descarta_clientes_que_fallan_al_enviar():
    simulador = Simulador([_vendedor()], intervalo_emision_s=0.01)
    roto = ClienteRoto()
    simulador.clientes = {roto}
    await simulador.difundir({"tipo": "evento", "evento": "prueba"})
    assert roto not in simulador.clientes


async def test_procesar_comando_reinicio_dispara_el_evento():
    simulador = Simulador([_vendedor(codigo="005")], intervalo_emision_s=0.01)
    cliente = ClienteFalso()
    await simulador._procesar_comando(cliente, json.dumps({"comando": "reiniciar", "vendedorCodigo": "005"}))
    assert simulador.eventos_reinicio["005"].is_set()
    assert cliente.mensajes == []


async def test_procesar_comando_vendedor_desconocido_responde_error():
    simulador = Simulador([_vendedor(codigo="005")], intervalo_emision_s=0.01)
    cliente = ClienteFalso()
    await simulador._procesar_comando(cliente, json.dumps({"comando": "reiniciar", "vendedorCodigo": "999"}))
    assert cliente.mensajes[0]["tipo"] == "evento"
    assert cliente.mensajes[0]["evento"] == "error"


async def test_procesar_comando_json_invalido_responde_error():
    simulador = Simulador([_vendedor(codigo="005")], intervalo_emision_s=0.01)
    cliente = ClienteFalso()
    await simulador._procesar_comando(cliente, "esto no es json")
    assert cliente.mensajes[0]["evento"] == "error"


async def test_tarea_vendedor_emite_ciclo_completo_y_espera_reinicio():
    vendedor = _vendedor(codigo="005", tiempo_s=0.001)
    simulador = Simulador([vendedor], intervalo_emision_s=0.01)
    cliente = ClienteFalso()
    simulador.clientes = {cliente}

    tarea = asyncio.create_task(simulador.tarea_vendedor(vendedor))
    await asyncio.sleep(0.1)
    eventos = [m for m in cliente.mensajes if m.get("tipo") == "evento"]
    assert any(e["evento"] == "ciclo_completo" for e in eventos)

    simulador.eventos_reinicio["005"].set()
    await asyncio.sleep(0.1)
    eventos = [m for m in cliente.mensajes if m.get("tipo") == "evento"]
    assert any(e["evento"] == "reiniciado" for e in eventos)

    tarea.cancel()
    with pytest.raises(asyncio.CancelledError):
        await tarea
```

- [ ] **Step 3: Ejecutar y verificar que falla**

```bash
pytest tests/test_servidor.py -v
```
Esperado: FAIL con `ModuleNotFoundError: No module named 'simulador.servidor'`.

- [ ] **Step 4: Implementar `servidor.py`**

```python
from __future__ import annotations

import asyncio
import json
import logging

from .vendedor import EstadoVendedor, VendedorSimulacion

INTERVALO_EMISION_S = 30

logger = logging.getLogger(__name__)


class Simulador:
    def __init__(self, vendedores: list[VendedorSimulacion], intervalo_emision_s: float = INTERVALO_EMISION_S):
        self.vendedores = {v.codigo: v for v in vendedores}
        self.intervalo_emision_s = intervalo_emision_s
        self.eventos_reinicio = {codigo: asyncio.Event() for codigo in self.vendedores}
        self.clientes: set = set()

    async def difundir(self, mensaje: dict) -> None:
        if not self.clientes:
            return
        data = json.dumps(mensaje)
        destinatarios = list(self.clientes)
        resultados = await asyncio.gather(
            *(cliente.send(data) for cliente in destinatarios),
            return_exceptions=True,
        )
        for cliente, resultado in zip(destinatarios, resultados):
            if isinstance(resultado, Exception):
                self.clientes.discard(cliente)

    async def tarea_vendedor(self, vendedor: VendedorSimulacion) -> None:
        evento_reinicio = self.eventos_reinicio[vendedor.codigo]
        while True:
            evento_reinicio.clear()
            vendedor.iniciar()
            while vendedor.estado != EstadoVendedor.CICLO_COMPLETO:
                await asyncio.sleep(self.intervalo_emision_s)
                mensaje = vendedor.avanzar(self.intervalo_emision_s)
                await self.difundir(mensaje)
            await self.difundir({"tipo": "evento", "evento": "ciclo_completo", "vendedorCodigo": vendedor.codigo})
            await evento_reinicio.wait()
            await self.difundir({"tipo": "evento", "evento": "reiniciado", "vendedorCodigo": vendedor.codigo})

    async def manejar_cliente(self, websocket) -> None:
        self.clientes.add(websocket)
        try:
            async for mensaje_raw in websocket:
                await self._procesar_comando(websocket, mensaje_raw)
        finally:
            self.clientes.discard(websocket)

    async def _procesar_comando(self, websocket, mensaje_raw: str) -> None:
        try:
            comando = json.loads(mensaje_raw)
        except json.JSONDecodeError:
            await websocket.send(json.dumps({"tipo": "evento", "evento": "error", "detalle": "JSON invalido"}))
            return

        if comando.get("comando") != "reiniciar":
            await websocket.send(json.dumps({"tipo": "evento", "evento": "error", "detalle": "comando desconocido"}))
            return

        codigo = comando.get("vendedorCodigo")
        evento = self.eventos_reinicio.get(codigo)
        if evento is None:
            await websocket.send(json.dumps({
                "tipo": "evento", "evento": "error", "detalle": f"vendedor desconocido: {codigo}",
            }))
            return
        evento.set()

    async def ejecutar_vendedores(self) -> None:
        await asyncio.gather(*(self.tarea_vendedor(v) for v in self.vendedores.values()))
```

- [ ] **Step 5: Ejecutar y verificar que pasa**

```bash
pytest tests/test_servidor.py -v
```
Esperado: 6 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add herramientas/simulador-navegacion/simulador/servidor.py herramientas/simulador-navegacion/tests/test_servidor.py
git commit -m "feat: servidor websocket con difusion y comando de reinicio"
```

---

### Task 7: Punto de entrada, configuración y documentación (`main.py`)

**Files:**
- Create: `herramientas/simulador-navegacion/simulador/main.py`
- Create: `herramientas/simulador-navegacion/config.ejemplo.json`
- Create: `herramientas/simulador-navegacion/requirements.txt`
- Create: `herramientas/simulador-navegacion/README.md`
- Test: `herramientas/simulador-navegacion/tests/test_main.py`

**Interfaces:**
- Consumes: `consultar_ruta_osrm` (Task 2), `construir_ruta` (Task 3), `Simulador` (Task 6), `VendedorSimulacion` (Task 5).
- Produces:
  - `cargar_config(path: str) -> dict`
  - `construir_vendedores(config: dict) -> list[VendedorSimulacion]` — para cada vendedor de `config["vendedores"]`, consulta OSRM ida y vuelta; si `RuntimeError`, loguea y omite ese vendedor (no lanza).
  - `main_async(config_path: str) -> None` (async) — arma todo y sirve hasta cancelación.
  - `main() -> None` — punto de entrada de consola (`python -m simulador.main [config.json]`).

- [ ] **Step 1: Escribir el test que falla**

`herramientas/simulador-navegacion/tests/test_main.py`:

```python
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
```

- [ ] **Step 2: Ejecutar y verificar que falla**

```bash
pytest tests/test_main.py -v
```
Esperado: FAIL con `ModuleNotFoundError: No module named 'simulador.main'`.

- [ ] **Step 3: Implementar `main.py`**

```python
from __future__ import annotations

import asyncio
import json
import logging
import random
import sys

import websockets

from .osrm import consultar_ruta_osrm
from .ruta import construir_ruta
from .servidor import Simulador
from .vendedor import VendedorSimulacion

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger(__name__)


def cargar_config(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def construir_vendedores(config: dict) -> list[VendedorSimulacion]:
    vendedores = []
    for datos in config["vendedores"]:
        codigo = datos["codigo"]
        inicio = (datos["latInicio"], datos["lonInicio"])
        fin = (datos["latFin"], datos["lonFin"])
        try:
            ruta_osrm_ida = consultar_ruta_osrm(inicio, fin)
            ruta_osrm_vuelta = consultar_ruta_osrm(fin, inicio)
        except RuntimeError as exc:
            logger.error("Vendedor %s queda inactivo: %s", codigo, exc)
            continue
        vendedores.append(VendedorSimulacion(
            codigo=codigo,
            tipo=datos["tipo"],
            ruta_ida=construir_ruta(ruta_osrm_ida),
            ruta_vuelta=construir_ruta(ruta_osrm_vuelta),
            rng=random.Random(),
        ))
    return vendedores


async def main_async(config_path: str) -> None:
    config = cargar_config(config_path)
    vendedores = construir_vendedores(config)
    if not vendedores:
        logger.error("Ningun vendedor pudo ser ruteado; el servicio no tiene nada que simular")
        return

    simulador = Simulador(vendedores)
    host = config.get("host", "0.0.0.0")
    puerto = config.get("puerto", 8765)

    async with websockets.serve(simulador.manejar_cliente, host, puerto):
        logger.info("Simulador escuchando en ws://%s:%s con %d vendedor(es)", host, puerto, len(vendedores))
        await simulador.ejecutar_vendedores()


def main() -> None:
    config_path = sys.argv[1] if len(sys.argv) > 1 else "config.json"
    asyncio.run(main_async(config_path))


if __name__ == "__main__":
    main()
```

`herramientas/simulador-navegacion/config.ejemplo.json`:

```json
{
  "host": "0.0.0.0",
  "puerto": 8765,
  "vendedores": [
    {
      "codigo": "005",
      "tipo": "0",
      "latInicio": -32.8820,
      "lonInicio": -71.2489,
      "latFin": -32.7761,
      "lonFin": -71.5314
    }
  ]
}
```

`herramientas/simulador-navegacion/requirements.txt`:

```
requests>=2.31
websockets>=12.0
pytest>=8.0
pytest-asyncio>=0.23
```

`herramientas/simulador-navegacion/README.md`:

```markdown
# Simulador de navegación por calles

Simula uno o más vendedores moviéndose por calles reales (ruteo vía OSRM,
`router.project-osrm.org`) entre una posición inicial y una final, con
paradas aleatorias en el camino, y emite cada posición en tiempo real por
WebSocket.

Ver el diseño completo en
`docs/superpowers/specs/2026-08-02-simulador-navegacion-app1-design.md`.

## Instalar

    cd herramientas/simulador-navegacion
    python3 -m venv .venv
    source .venv/bin/activate
    pip install -r requirements.txt

## Configurar

Copiar `config.ejemplo.json` a `config.json` y editar la lista de
vendedores (código, posición inicial, posición final).

## Ejecutar

    python -m simulador.main config.json

## Protocolo WebSocket

Ver la sección "Protocolo WebSocket" del documento de diseño.

## Tests

    pytest
```

- [ ] **Step 4: Ejecutar y verificar que pasa**

```bash
pytest tests/test_main.py -v
```
Esperado: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add herramientas/simulador-navegacion/
git commit -m "feat: punto de entrada, configuracion y documentacion del simulador"
```

---

### Task 8: Test de humo end-to-end (WebSocket real)

**Files:**
- Test: `herramientas/simulador-navegacion/tests/test_smoke_e2e.py`

**Interfaces:**
- Consumes: `Simulador`, `VendedorSimulacion`, `Ruta`, `SegmentoRuta` de tasks previas. No agrega interfaces nuevas — es una prueba de integración del sistema ya construido.

- [ ] **Step 1: Escribir el test (no hay implementación pendiente — es el propio test de integración)**

`herramientas/simulador-navegacion/tests/test_smoke_e2e.py`:

```python
import asyncio
import json
import random

import pytest
import websockets

from simulador.ruta import Ruta, SegmentoRuta
from simulador.servidor import Simulador
from simulador.vendedor import VendedorSimulacion


def _vendedor_rapido():
    ruta = Ruta(segmentos=[
        SegmentoRuta(lat=-33.0, lon=-71.0, distancia_acum_m=0.0, tiempo_acum_s=0.0),
        SegmentoRuta(lat=-33.0, lon=-70.999, distancia_acum_m=10.0, tiempo_acum_s=0.05),
    ])
    return VendedorSimulacion(codigo="005", tipo="0", ruta_ida=ruta, ruta_vuelta=ruta, rng=random.Random(7))


async def test_ciclo_completo_via_websocket_real():
    vendedor = _vendedor_rapido()
    simulador = Simulador([vendedor], intervalo_emision_s=0.05)
    tarea_simulacion = asyncio.create_task(simulador.tarea_vendedor(vendedor))

    async with websockets.serve(simulador.manejar_cliente, "localhost", 8765):
        async with websockets.connect("ws://localhost:8765") as cliente:
            mensajes = []
            async with asyncio.timeout(5):
                while True:
                    mensaje = json.loads(await cliente.recv())
                    mensajes.append(mensaje)
                    if mensaje.get("evento") == "ciclo_completo":
                        break

            posiciones = [m for m in mensajes if m["tipo"] == "posicion"]
            eventos = [m for m in mensajes if m["tipo"] == "evento"]
            assert len(posiciones) >= 1
            assert any(e["evento"] == "ciclo_completo" for e in eventos)

            await cliente.send(json.dumps({"comando": "reiniciar", "vendedorCodigo": "005"}))
            async with asyncio.timeout(5):
                while True:
                    mensaje = json.loads(await cliente.recv())
                    if mensaje.get("evento") == "reiniciado":
                        break

    tarea_simulacion.cancel()
    with pytest.raises(asyncio.CancelledError):
        await tarea_simulacion
```

- [ ] **Step 2: Ejecutar y verificar que pasa**

```bash
pytest tests/test_smoke_e2e.py -v
```
Esperado: 1 test PASS, corre en pocos segundos reales (ruta sintética de 0.05s + intervalo de emisión de 0.05s).

- [ ] **Step 3: Ejecutar toda la suite del proyecto**

```bash
pytest -v
```
Esperado: todos los tests de las 8 tasks PASS.

- [ ] **Step 4: Commit**

```bash
git add herramientas/simulador-navegacion/tests/test_smoke_e2e.py
git commit -m "test: prueba de humo end-to-end del simulador via websocket real"
```

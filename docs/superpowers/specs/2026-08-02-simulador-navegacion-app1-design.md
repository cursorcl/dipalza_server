# Simulador de navegación por calles (App 1) — Diseño

## Contexto

El proyecto ya cuenta con scripts offline (`data/generar_datos_prueba_posicion*.py`)
que generan datos GPS de prueba realistas, ruteando por calles reales vía
OSRM (`router.project-osrm.org`, público, sin API key) y volcando el
resultado directo a SQL. Son útiles para sembrar historial, pero no sirven
para probar el pipeline de ingesta en vivo (`POST /api/posicion`, detección
de paradas en tiempo real, WebSocket de posición actual, etc.) porque no
simulan un vendedor real emitiendo posiciones minuto a minuto.

Este documento diseña una **App 1 independiente**: un servicio que simula
uno o más vendedores moviéndose por calles reales entre una posición inicial
y una final (y de vuelta), deteniéndose aleatoriamente en el camino, y
entrega cada posición en tiempo real por un socket. Es el primero de dos
subproyectos:

- **App 1 (este documento):** simulador puro — calcula movimiento y lo
  emite por WebSocket. Nunca toca la base de datos ni conoce los entry
  points reales del sistema.
- **App 2 (spec separado, a futuro):** consume el WebSocket de App 1 y
  reenvía cada posición a los entry points reales del sistema
  (`POST /api/posicion` u otros), incluyendo el manejo de limpieza de
  registros al recibir un reinicio.

## Decisiones confirmadas con el usuario

- **Tiempo real estricto:** 1 minuto simulado = 1 minuto real. Sin factor
  de aceleración.
- **Clasificación ciudad/interurbano vía OSRM:** se usa la velocidad que
  OSRM asigna a cada micro-tramo de la vía (parámetro `annotations=true`)
  únicamente como clasificador — velocidad OSRM < 70 km/h ⇒ tramo "ciudad",
  ≥ 70 km/h ⇒ tramo "interurbano". La velocidad de movimiento simulada es
  siempre una de dos constantes fijas: **50 km/h en ciudad, 100 km/h fuera
  de ciudad** — el valor de OSRM nunca se usa como velocidad real.
- **Socket:** WebSocket (librería `websockets` de Python).
- **Entrada inicial:** archivo de configuración JSON con la lista de
  vendedores (código, posición inicial, posición final), leído al arrancar.
- **Ciclo de vida:** cada vendedor hace un único ciclo ida→fin→vuelta→inicio
  y luego queda inactivo (no vuelve a moverse solo). Un comando explícito
  de "reiniciar", recibido por el mismo WebSocket, lo hace volver a
  arrancar desde la posición inicial.
- **Alcance del borrado en reinicio:** App 1 nunca toca la base de datos.
  Al reiniciar, solo resetea su estado interno y difunde un evento de
  "reiniciado" — la limpieza de los registros ya ingresados ese día es
  responsabilidad de App 2, que se diseñará por separado.
- **Formato de mensaje de posición:** mínimo necesario para que App 2 lo
  reenvíe tal cual — código de vendedor, lat/lon, timestamp. Sin metadatos
  de simulación (velocidad actual, si está detenido, tipo de tramo).
- **Ubicación:** `dipalza_server/herramientas/simulador-navegacion/`, mismo
  repo que los scripts de datos de prueba existentes.
- **Cadencia de emisión:** cada 30 segundos por vendedor en movimiento —
  igual al `Timer.periodic` real del servicio de background de
  `dipalza_mobile`.

## Arquitectura

Servicio Python (`asyncio`), sin dependencias del resto del backend Java —
proceso independiente, standalone.

### Precómputo de rutas (al arrancar)

Para cada vendedor de `config.json`, se consultan **dos rutas** a OSRM:
- Ida: posición inicial → posición final
- Vuelta: posición final → posición inicial

(Se consultan ambas por separado, no se asume que la vuelta sea la ida
invertida — calles de sentido único pueden hacer que la ruta real de
regreso sea distinta.)

Cada consulta usa `overview=full&geometries=geojson&steps=true&annotations=true`.
La respuesta trae, además de la geometría completa, una velocidad estimada
por OSRM (`annotation.speed`, m/s) para cada segmento entre coordenadas
consecutivas de la geometría. Con eso se construye, por ruta, una lista de
`(distancia_acumulada_m, tiempo_simulado_acumulado_s, lat, lon)`:

- Para cada micro-segmento, se clasifica ciudad/interurbano según el
  umbral de 70 km/h sobre `annotation.speed`.
- Se le asigna la velocidad fija correspondiente (50 o 100 km/h).
- `tiempo_simulado_acumulado_s += distancia_segmento_m / velocidad_fija_m_s`.

Esta tabla es la base para interpolar "dónde está el vendedor" dado un
tiempo simulado transcurrido — mismo principio que `punto_en_distancia()`
del script existente, pero indexado por tiempo propio en vez de por la
duración que asume OSRM.

### Máquina de estados por vendedor

Cada vendedor corre en su propia tarea `asyncio`, independiente del resto:

```
INACTIVO ──(arranque o comando "reiniciar")──> EN_MOVIMIENTO (ida)
EN_MOVIMIENTO (ida) ──(llega a posición final)──> EN_MOVIMIENTO (vuelta)
EN_MOVIMIENTO (vuelta) ──(llega a posición inicial)──> CICLO_COMPLETO (inactivo)
CICLO_COMPLETO ──(comando "reiniciar")──> EN_MOVIMIENTO (ida)
EN_MOVIMIENTO (cualquiera) ──(umbral de parada alcanzado)──> DETENIDO
DETENIDO ──(duración de parada cumplida)──> EN_MOVIMIENTO (mismo tramo, continúa)
```

**Paradas aleatorias:** un temporizador propio por vendedor sortea, cada
vez que se reanuda el movimiento (arranque, reinicio, o fin de una parada
anterior), un umbral de "tiempo conduciendo antes de la próxima parada"
uniforme en `[60, 120]` minutos. Al alcanzar ese umbral de tiempo simulado
en movimiento, el vendedor pasa a `DETENIDO` por una duración uniforme en
`[10, 60]` minutos, emitiendo su posición actual con un jitter GPS pequeño
(±6 m, igual que el script existente) en cada tick de 30s. El temporizador
es continuo a través de todo el ciclo ida+vuelta — no se reinicia al llegar
a la posición final, solo al ocurrir una parada real.

### Emisión

Cada 30 segundos, por cada vendedor en `EN_MOVIMIENTO` o `DETENIDO`, se
calcula/mantiene la posición actual y se difunde por WebSocket a todos los
clientes conectados. Un vendedor `INACTIVO` o `CICLO_COMPLETO` no emite
nada.

## Protocolo WebSocket

Servidor WebSocket (host/puerto configurables). Todos los clientes
conectados reciben el mismo stream de mensajes JSON, uno por línea de
mensaje WebSocket.

**Mensaje de posición** (servidor → clientes, cada 30s por vendedor activo):
```json
{"tipo": "posicion", "vendedorCodigo": "005", "vendedorTipo": "0", "latitud": -32.881, "longitud": -71.249, "timestamp": "2026-08-02T14:32:10"}
```

**Evento de ciclo completo** (servidor → clientes):
```json
{"tipo": "evento", "evento": "ciclo_completo", "vendedorCodigo": "005"}
```

**Evento de reiniciado** (servidor → clientes, tras procesar un comando de reinicio):
```json
{"tipo": "evento", "evento": "reiniciado", "vendedorCodigo": "005"}
```

**Comando de reinicio** (cliente → servidor):
```json
{"comando": "reiniciar", "vendedorCodigo": "005"}
```

Un vendedor con código desconocido en el comando de reinicio se ignora y
se responde con un evento de error (`{"tipo": "evento", "evento": "error", "detalle": "..."}`)
al cliente que lo envió, sin afectar a los demás vendedores.

## Configuración de entrada

`config.json`, leído una vez al arrancar el proceso:

```json
{
  "host": "0.0.0.0",
  "puerto": 8765,
  "vendedores": [
    {
      "codigo": "005",
      "tipo": "0",
      "latInicio": -32.8820, "lonInicio": -71.2489,
      "latFin": -32.7761, "lonFin": -71.5314
    }
  ]
}
```

## Manejo de errores

- **OSRM no disponible o no puede rutear** al arrancar: se reintenta con el
  mismo patrón de radio decreciente que `consultar_osrm()` del script
  existente; si tras los reintentos configurados sigue fallando para un
  vendedor, ese vendedor queda `INACTIVO` permanentemente y se loguea el
  error — no tumba el proceso completo ni afecta a los demás vendedores.
- **Cliente WebSocket se desconecta:** no afecta la simulación — el
  servidor sigue calculando y difundiendo posiciones a los clientes que
  queden conectados (y a los que se conecten después, uniéndose al stream
  en curso).
- **Comando de reinicio malformado o vendedor inexistente:** se responde
  con un evento de error al remitente, sin efecto en la simulación.

## Testing

- `pytest` + `pytest-asyncio`.
- **Funciones puras testeadas sin reloj real:** interpolación
  posición↔tiempo simulado dada una tabla de ruta sintética; clasificación
  ciudad/interurbano dado un array de velocidades OSRM sintéticas; sorteo
  de próxima parada y duración de parada con una semilla fija
  (determinístico).
- **Mocks de OSRM:** las respuestas de OSRM se mockean en los tests, sin
  depender de red real (igual criterio que se aplicaría a los scripts
  existentes si tuvieran tests, que hoy no tienen).
- **Test de humo end-to-end:** ruta sintética muy corta (recorrible en
  segundos reales, no horas) para verificar el flujo asyncio + servidor
  WebSocket real, incluyendo un ciclo de parada breve y un comando de
  reinicio, dentro de un tiempo de ejecución acotado.

## Fuera de alcance de este documento

- App 2 (consumidor del WebSocket que reenvía a los entry points reales y
  maneja la limpieza de registros al reiniciar) — spec independiente, a
  diseñar después de que App 1 esté implementada y funcionando.

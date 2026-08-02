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

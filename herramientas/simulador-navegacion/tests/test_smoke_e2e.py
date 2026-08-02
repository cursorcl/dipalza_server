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

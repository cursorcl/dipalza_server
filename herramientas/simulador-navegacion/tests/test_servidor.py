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

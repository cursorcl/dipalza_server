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
            ruta_ida = construir_ruta(ruta_osrm_ida)
            ruta_vuelta = construir_ruta(ruta_osrm_vuelta)
        except (RuntimeError, ValueError, KeyError, IndexError) as exc:
            logger.error("Vendedor %s queda inactivo: %s", codigo, exc)
            continue
        vendedores.append(VendedorSimulacion(
            codigo=codigo,
            tipo=datos["tipo"],
            ruta_ida=ruta_ida,
            ruta_vuelta=ruta_vuelta,
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

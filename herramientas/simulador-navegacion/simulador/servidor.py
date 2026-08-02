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
                if evento_reinicio.is_set():
                    break
                await asyncio.sleep(self.intervalo_emision_s)
                mensaje = vendedor.avanzar(self.intervalo_emision_s)
                await self.difundir(mensaje)
            if vendedor.estado == EstadoVendedor.CICLO_COMPLETO:
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

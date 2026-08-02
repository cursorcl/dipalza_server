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

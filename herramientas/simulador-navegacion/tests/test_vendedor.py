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

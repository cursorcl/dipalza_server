package cl.eos.dipalza.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoUtilsTest {

    @Test
    void distanciaEntreDosPuntosConocidosEnSantiago() {
        // Plaza de Armas (-33.4372, -70.6506) a un punto ~100m al norte
        double distancia = GeoUtils.distanciaMetros(-33.4372, -70.6506, -33.4363, -70.6506);
        assertThat(distancia).isBetween(95.0, 105.0);
    }

    @Test
    void esSimetrica() {
        double d1 = GeoUtils.distanciaMetros(-33.4372, -70.6506, -33.05, -71.62);
        double d2 = GeoUtils.distanciaMetros(-33.05, -71.62, -33.4372, -70.6506);
        assertThat(d1).isEqualTo(d2);
    }

    @Test
    void distanciaCeroEntreUnPuntoYSiMismo() {
        assertThat(GeoUtils.distanciaMetros(-33.4372, -70.6506, -33.4372, -70.6506)).isZero();
    }
}

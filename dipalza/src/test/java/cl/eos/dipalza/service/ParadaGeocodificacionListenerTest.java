package cl.eos.dipalza.service;

import cl.eos.dipalza.event.ParadaDetectadaEvent;
import cl.eos.dipalza.repository.ParadaVendedorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParadaGeocodificacionListenerTest {

    @Mock
    private GeocodificacionService geocodificacionService;
    @Mock
    private ParadaVendedorRepository paradaVendedorRepository;

    @org.junit.jupiter.api.Test
    void resuelveCalleYActualizaLaFila() {
        ParadaGeocodificacionListener listener =
                new ParadaGeocodificacionListener(geocodificacionService, paradaVendedorRepository);
        when(geocodificacionService.obtenerCalle(-33.45, -70.65)).thenReturn("Av. Providencia");

        listener.onParadaDetectada(new ParadaDetectadaEvent(1L, -33.45, -70.65));

        verify(paradaVendedorRepository).actualizarCalle(1L, "Av. Providencia");
    }

    @Test
    void geocodificacionFallaOSinResultado_persisteSentinelIgual() {
        ParadaGeocodificacionListener listener =
                new ParadaGeocodificacionListener(geocodificacionService, paradaVendedorRepository);
        when(geocodificacionService.obtenerCalle(-33.45, -70.65)).thenReturn("Calle no disponible");

        listener.onParadaDetectada(new ParadaDetectadaEvent(1L, -33.45, -70.65));

        verify(paradaVendedorRepository).actualizarCalle(1L, "Calle no disponible");
    }
}

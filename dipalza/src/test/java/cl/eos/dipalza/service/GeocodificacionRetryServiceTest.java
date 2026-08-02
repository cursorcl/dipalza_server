package cl.eos.dipalza.service;

import cl.eos.dipalza.repository.ParadaVendedorRepository;
import cl.eos.dipalza.entity.ParadaVendedor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GeocodificacionRetryServiceTest {

    @Mock
    private ParadaVendedorRepository paradaVendedorRepository;
    @Mock
    private GeocodificacionService geocodificacionService;

    @Test
    void reintentarGeocodificacionPendiente_resuelveYActualizaLasQueTuvieronExito() {
        ParadaVendedor p1 = new ParadaVendedor();
        p1.setId(1L);
        p1.setLatitud(-33.45);
        p1.setLongitud(-70.65);
        when(paradaVendedorRepository.findByCalle(eq("Calle no disponible"), any(Pageable.class)))
                .thenReturn(List.of(p1));
        when(geocodificacionService.obtenerCalle(-33.45, -70.65)).thenReturn("Av. Providencia");

        GeocodificacionRetryService service =
                new GeocodificacionRetryService(paradaVendedorRepository, geocodificacionService);
        service.reintentarGeocodificacionPendiente();

        verify(paradaVendedorRepository).actualizarCalle(1L, "Av. Providencia");
    }

    @Test
    void reintentarGeocodificacionPendiente_siSigueFallando_noActualiza() {
        ParadaVendedor p1 = new ParadaVendedor();
        p1.setId(1L);
        p1.setLatitud(-33.45);
        p1.setLongitud(-70.65);
        when(paradaVendedorRepository.findByCalle(eq("Calle no disponible"), any(Pageable.class)))
                .thenReturn(List.of(p1));
        when(geocodificacionService.obtenerCalle(-33.45, -70.65)).thenReturn("Calle no disponible");

        GeocodificacionRetryService service =
                new GeocodificacionRetryService(paradaVendedorRepository, geocodificacionService);
        service.reintentarGeocodificacionPendiente();

        verify(paradaVendedorRepository, never()).actualizarCalle(any(), any());
    }

    @Test
    void reintentarGeocodificacionPendiente_sinPendientes_noHaceNada() {
        when(paradaVendedorRepository.findByCalle(eq("Calle no disponible"), any(Pageable.class)))
                .thenReturn(List.of());

        GeocodificacionRetryService service =
                new GeocodificacionRetryService(paradaVendedorRepository, geocodificacionService);
        service.reintentarGeocodificacionPendiente();

        verifyNoInteractions(geocodificacionService);
        verify(paradaVendedorRepository, never()).actualizarCalle(any(), any());
    }
}

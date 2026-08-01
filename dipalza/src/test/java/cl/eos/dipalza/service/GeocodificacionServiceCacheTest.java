package cl.eos.dipalza.service;

import cl.eos.dipalza.config.CacheConfig;
import cl.eos.dipalza.model.NominatimAddressDTO;
import cl.eos.dipalza.model.NominatimResponseDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.client.RestTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {CacheConfig.class, GeocodificacionService.class})
class GeocodificacionServiceCacheTest {

    @Autowired GeocodificacionService service;
    @MockBean RestTemplate restTemplate;

    @Test
    void obtenerCalle_mismasCoordenadas_soloConsultaNominatimUnaVez() {
        NominatimResponseDTO respuesta = new NominatimResponseDTO(
                new NominatimAddressDTO("Calle Cacheada", null, null, null), "Calle Cacheada, Chile");
        when(restTemplate.getForObject(anyString(), eq(NominatimResponseDTO.class), any(), any()))
                .thenReturn(respuesta);

        service.obtenerCalle(-33.1, -71.1);
        service.obtenerCalle(-33.1, -71.1);

        verify(restTemplate, times(1)).getForObject(anyString(), eq(NominatimResponseDTO.class), any(), any());
    }

    @Test
    void obtenerCalle_fallaLaConsulta_noCacheaElResultadoDeFalla() {
        when(restTemplate.getForObject(anyString(), eq(NominatimResponseDTO.class), any(), any()))
                .thenThrow(new RuntimeException("timeout"));

        service.obtenerCalle(-33.2, -71.2);
        service.obtenerCalle(-33.2, -71.2);

        verify(restTemplate, times(2)).getForObject(anyString(), eq(NominatimResponseDTO.class), any(), any());
    }
}

package cl.eos.dipalza.service;

import cl.eos.dipalza.model.NominatimAddressDTO;
import cl.eos.dipalza.model.NominatimResponseDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class GeocodificacionServiceTest {

    @Mock RestTemplate restTemplate;

    @Test
    void obtenerCalle_conRoadEnAddress_devuelveRoad() {
        GeocodificacionService service = new GeocodificacionService(restTemplate);
        NominatimResponseDTO respuesta = new NominatimResponseDTO(
                new NominatimAddressDTO("Av. Errázuriz", null, null, null), "Av. Errázuriz, Valparaíso, Chile");
        when(restTemplate.getForObject(anyString(), eq(NominatimResponseDTO.class), any(), any()))
                .thenReturn(respuesta);

        String calle = service.obtenerCalle(-33.0393, -71.6273);

        assertThat(calle).isEqualTo("Av. Errázuriz");
    }

    @Test
    void obtenerCalle_sinRoad_usaFallbackPedestrian() {
        GeocodificacionService service = new GeocodificacionService(restTemplate);
        NominatimResponseDTO respuesta = new NominatimResponseDTO(
                new NominatimAddressDTO(null, "Paseo Atkinson", null, null), "Paseo Atkinson, Valparaíso, Chile");
        when(restTemplate.getForObject(anyString(), eq(NominatimResponseDTO.class), any(), any()))
                .thenReturn(respuesta);

        String calle = service.obtenerCalle(-33.04, -71.63);

        assertThat(calle).isEqualTo("Paseo Atkinson");
    }

    @Test
    void obtenerCalle_sinAddressNiRoad_usaPrimerSegmentoDeDisplayName() {
        GeocodificacionService service = new GeocodificacionService(restTemplate);
        NominatimResponseDTO respuesta = new NominatimResponseDTO(null, "Sector Rural, Región de Valparaíso, Chile");
        when(restTemplate.getForObject(anyString(), eq(NominatimResponseDTO.class), any(), any()))
                .thenReturn(respuesta);

        String calle = service.obtenerCalle(-33.5, -71.2);

        assertThat(calle).isEqualTo("Sector Rural");
    }

    @Test
    void obtenerCalle_respuestaSinNadaUsable_devuelveCalleSinIdentificar() {
        GeocodificacionService service = new GeocodificacionService(restTemplate);
        NominatimResponseDTO respuesta = new NominatimResponseDTO(null, null);
        when(restTemplate.getForObject(anyString(), eq(NominatimResponseDTO.class), any(), any()))
                .thenReturn(respuesta);

        String calle = service.obtenerCalle(-33.5, -71.2);

        assertThat(calle).isEqualTo("Calle sin identificar");
    }

    @Test
    void obtenerCalle_fallaLaLlamadaARestTemplate_devuelveCalleNoDisponible() {
        GeocodificacionService service = new GeocodificacionService(restTemplate);
        doThrow(new RuntimeException("timeout"))
                .when(restTemplate).getForObject(anyString(), eq(NominatimResponseDTO.class), any(), any());

        String calle = service.obtenerCalle(-33.5, -71.2);

        assertThat(calle).isEqualTo("Calle no disponible");
    }

    @Test
    void obtenerCalle_dosLlamadasSeguidas_esperaAlMenosUnSegundoEntreLasLlamadasReales() {
        GeocodificacionService service = new GeocodificacionService(restTemplate);
        NominatimResponseDTO respuesta = new NominatimResponseDTO(
                new NominatimAddressDTO("Calle X", null, null, null), "Calle X, Chile");
        when(restTemplate.getForObject(anyString(), eq(NominatimResponseDTO.class), any(), any()))
                .thenReturn(respuesta);

        long inicio = System.currentTimeMillis();
        service.obtenerCalle(-33.1, -71.1);
        service.obtenerCalle(-33.2, -71.2); // coordenada distinta: no debe pegarle al cache, debe esperar el turno
        long duracion = System.currentTimeMillis() - inicio;

        assertThat(duracion).isGreaterThanOrEqualTo(1000);
    }
}

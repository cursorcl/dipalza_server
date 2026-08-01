package cl.eos.dipalza.service;

import cl.eos.dipalza.config.CacheConfig;
import cl.eos.dipalza.model.NominatimAddressDTO;
import cl.eos.dipalza.model.NominatimResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class GeocodificacionService {

    private static final Logger log = LoggerFactory.getLogger(GeocodificacionService.class);
    private static final String NOMINATIM_URL = "https://nominatim.openstreetmap.org/reverse?lat={lat}&lon={lon}&format=jsonv2";
    private static final long INTERVALO_MINIMO_MS = 1100;
    private static final String CALLE_SIN_IDENTIFICAR = "Calle sin identificar";
    private static final String CALLE_NO_DISPONIBLE = "Calle no disponible";

    private final RestTemplate restTemplate;
    private final Object nominatimLock = new Object();
    private long ultimaConsultaMs = 0;

    public GeocodificacionService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Cacheable(value = CacheConfig.GEOCODIFICACION_CALLE, key = "#lat + ',' + #lon")
    public String obtenerCalle(double lat, double lon) {
        try {
            esperarTurno();
            NominatimResponseDTO respuesta = restTemplate.getForObject(NOMINATIM_URL, NominatimResponseDTO.class, lat, lon);
            return extraerCalle(respuesta);
        } catch (Exception e) {
            log.warn("No se pudo geocodificar lat={} lon={}: {}", lat, lon, e.getMessage());
            return CALLE_NO_DISPONIBLE;
        }
    }

    private void esperarTurno() {
        synchronized (nominatimLock) {
            long espera = INTERVALO_MINIMO_MS - (System.currentTimeMillis() - ultimaConsultaMs);
            if (espera > 0) {
                try {
                    Thread.sleep(espera);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            ultimaConsultaMs = System.currentTimeMillis();
        }
    }

    private String extraerCalle(NominatimResponseDTO respuesta) {
        if (respuesta == null) {
            return CALLE_SIN_IDENTIFICAR;
        }
        NominatimAddressDTO address = respuesta.address();
        if (address != null) {
            if (esUtilizable(address.road())) return address.road();
            if (esUtilizable(address.pedestrian())) return address.pedestrian();
            if (esUtilizable(address.footway())) return address.footway();
            if (esUtilizable(address.cycleway())) return address.cycleway();
        }
        if (esUtilizable(respuesta.displayName())) {
            return respuesta.displayName().split(",")[0].trim();
        }
        return CALLE_SIN_IDENTIFICAR;
    }

    private boolean esUtilizable(String valor) {
        return valor != null && !valor.isBlank();
    }
}

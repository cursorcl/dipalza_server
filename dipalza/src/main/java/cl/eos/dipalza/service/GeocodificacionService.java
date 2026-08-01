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
    // IMPORTANT: The SpEL expression in @Cacheable.unless must stay in sync with BOTH
    // CALLE_SIN_IDENTIFICAR and CALLE_NO_DISPONIBLE below — neither sentinel value must ever
    // be cached, since both represent a non-answer (unresolved coordinate / failed lookup)
    // rather than a real geocoding result.
    private static final String CALLE_SIN_IDENTIFICAR = "Calle sin identificar";
    static final String CALLE_NO_DISPONIBLE = "Calle no disponible";

    private final RestTemplate restTemplate;
    private final Object nominatimLock = new Object();
    private long ultimaConsultaMs = 0;

    public GeocodificacionService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Cacheable(value = CacheConfig.GEOCODIFICACION_CALLE, key = "#lat + ',' + #lon",
            unless = "#result == 'Calle no disponible' || #result == 'Calle sin identificar'")
    public String obtenerCalle(double lat, double lon) {
        try {
            if (!esperarTurno()) {
                return CALLE_NO_DISPONIBLE;
            }
            NominatimResponseDTO respuesta = restTemplate.getForObject(NOMINATIM_URL, NominatimResponseDTO.class, lat, lon);
            return extraerCalle(respuesta);
        } catch (Exception e) {
            log.warn("No se pudo geocodificar lat={} lon={}", lat, lon, e);
            return CALLE_NO_DISPONIBLE;
        }
    }

    /**
     * @return true si se puede proceder con la consulta a Nominatim, false si el hilo fue
     *         interrumpido mientras esperaba su turno (en cuyo caso el llamador no debe
     *         realizar la consulta).
     */
    private boolean esperarTurno() {
        // Lock no-justo y sin timeout, por diseño: aceptable a la carga esperada de esta
        // funcionalidad (un puñado de solicitudes de geocodificación por día seleccionado por
        // vendedor). Un uso futuro de alta concurrencia de este endpoint debería reconsiderarlo
        // (p.ej. un Semaphore/tryLock con timeout que falle rápido en vez de bloquear
        // indefinidamente un hilo de solicitud).
        synchronized (nominatimLock) {
            long espera = Math.min(INTERVALO_MINIMO_MS,
                    INTERVALO_MINIMO_MS - (System.currentTimeMillis() - ultimaConsultaMs));
            if (espera > 0) {
                try {
                    Thread.sleep(espera);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            ultimaConsultaMs = System.currentTimeMillis();
            return true;
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

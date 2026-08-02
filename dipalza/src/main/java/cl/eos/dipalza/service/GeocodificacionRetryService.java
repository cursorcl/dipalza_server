package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.ParadaVendedor;
import cl.eos.dipalza.repository.ParadaVendedorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GeocodificacionRetryService {

    private static final Logger log = LoggerFactory.getLogger(GeocodificacionRetryService.class);
    private static final int TAMANO_LOTE = 20;
    private static final long QUINCE_MINUTOS_MS = 15 * 60 * 1000L;

    private final ParadaVendedorRepository paradaVendedorRepository;
    private final GeocodificacionService geocodificacionService;

    public GeocodificacionRetryService(ParadaVendedorRepository paradaVendedorRepository,
                                        GeocodificacionService geocodificacionService) {
        this.paradaVendedorRepository = paradaVendedorRepository;
        this.geocodificacionService = geocodificacionService;
    }

    // Reintenta resolver la calle de paradas cuya geocodificacion fallo transitoriamente
    // (sentinel CALLE_NO_DISPONIBLE, tipicamente por rate-limit o caida de red de Nominatim).
    // No reintenta CALLE_SIN_IDENTIFICAR: ese sentinel significa que Nominatim SI respondio
    // pero no hay nombre de calle utilizable para esa coordenada -- reintentar es
    // deterministicamente inutil.
    @Scheduled(fixedDelay = QUINCE_MINUTOS_MS)
    public void reintentarGeocodificacionPendiente() {
        List<ParadaVendedor> pendientes = paradaVendedorRepository.findByCalle(
                GeocodificacionService.CALLE_NO_DISPONIBLE, PageRequest.of(0, TAMANO_LOTE));

        for (ParadaVendedor parada : pendientes) {
            String calle = geocodificacionService.obtenerCalle(parada.getLatitud(), parada.getLongitud());
            if (!GeocodificacionService.CALLE_NO_DISPONIBLE.equals(calle)) {
                paradaVendedorRepository.actualizarCalle(parada.getId(), calle);
                log.info("Geocodificacion reintentada con exito para parada {}", parada.getId());
            }
        }
    }
}

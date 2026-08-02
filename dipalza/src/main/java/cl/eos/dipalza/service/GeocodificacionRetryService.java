package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.ParadaVendedor;
import cl.eos.dipalza.repository.ParadaVendedorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
        // Orden deterministico (mas antiguo primero): sin esto, que 20 filas vuelven en
        // cada corrida depende del motor de BD -- si un subconjunto falla permanentemente,
        // esas mismas filas arbitrarias podrian reintentarse por siempre mientras filas
        // pendientes mas nuevas nunca llegan a intentarse.
        List<ParadaVendedor> pendientes = paradaVendedorRepository.findByCalle(
                GeocodificacionService.CALLE_NO_DISPONIBLE,
                PageRequest.of(0, TAMANO_LOTE, Sort.by("horaInicio")));

        for (ParadaVendedor parada : pendientes) {
            try {
                String calle = geocodificacionService.obtenerCalle(parada.getLatitud(), parada.getLongitud());
                if (!GeocodificacionService.CALLE_NO_DISPONIBLE.equals(calle)) {
                    paradaVendedorRepository.actualizarCalle(parada.getId(), calle);
                    log.info("Geocodificacion reintentada con exito para parada {}", parada.getId());
                }
            } catch (Exception e) {
                // Una fila fallando (p.ej. error transitorio de BD en actualizarCalle) no
                // debe abortar el resto del lote.
                log.warn("Fallo al reintentar geocodificacion para parada {}", parada.getId(), e);
            }
        }
    }
}

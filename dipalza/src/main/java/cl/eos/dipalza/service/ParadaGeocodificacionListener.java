package cl.eos.dipalza.service;

import cl.eos.dipalza.event.ParadaDetectadaEvent;
import cl.eos.dipalza.repository.ParadaVendedorRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ParadaGeocodificacionListener {

    private final GeocodificacionService geocodificacionService;
    private final ParadaVendedorRepository paradaVendedorRepository;

    public ParadaGeocodificacionListener(GeocodificacionService geocodificacionService,
                                          ParadaVendedorRepository paradaVendedorRepository) {
        this.geocodificacionService = geocodificacionService;
        this.paradaVendedorRepository = paradaVendedorRepository;
    }

    @Async("deteccionParadaExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onParadaDetectada(ParadaDetectadaEvent event) {
        String calle = geocodificacionService.obtenerCalle(event.latitud(), event.longitud());
        paradaVendedorRepository.actualizarCalle(event.paradaId(), calle);
    }
}

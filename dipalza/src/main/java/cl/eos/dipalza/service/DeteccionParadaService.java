package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.ParadaVendedor;
import cl.eos.dipalza.entity.ParadaVendedorGrupoActual;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.event.ParadaDetectadaEvent;
import cl.eos.dipalza.repository.ParadaVendedorGrupoActualRepository;
import cl.eos.dipalza.repository.ParadaVendedorRepository;
import cl.eos.dipalza.utils.GeoUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class DeteccionParadaService {

    // Estos valores deben mantenerse en sincronia manual con los parametros por defecto de
    // detectarParadas() en dipalza_web_client/src/app/mapa/detectar-paradas.ts (otro repo, sin
    // verificacion por el compilador).
    private static final double RADIO_METROS = 100.0;
    private static final Duration DURACION_MINIMA = Duration.ofMinutes(10);
    private static final String CALLE_PENDIENTE = GeocodificacionService.CALLE_NO_DISPONIBLE;

    private final ParadaVendedorGrupoActualRepository grupoActualRepository;
    private final ParadaVendedorRepository paradaVendedorRepository;
    private final ApplicationEventPublisher eventPublisher;

    public DeteccionParadaService(ParadaVendedorGrupoActualRepository grupoActualRepository,
                                   ParadaVendedorRepository paradaVendedorRepository,
                                   ApplicationEventPublisher eventPublisher) {
        this.grupoActualRepository = grupoActualRepository;
        this.paradaVendedorRepository = paradaVendedorRepository;
        this.eventPublisher = eventPublisher;
    }

    // REQUIRES_NEW: aisla esta transaccion de la de PosicionService.registrarUbicacion para que un fallo aqui
    // no marque como rollback-only la transaccion del llamador y pierda el registro de posicion del GPS.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void procesarNuevoPunto(VendedorId vendedorId, Vendedor vendedorRef,
                                    double lat, double lon, LocalDateTime fecha) {
        ParadaVendedorGrupoActual grupo = grupoActualRepository.findById(vendedorId).orElse(null);

        if (grupo == null) {
            abrirNuevoGrupo(vendedorId, vendedorRef, lat, lon, fecha);
            return;
        }

        boolean cambioDeDia = !grupo.getDia().equals(fecha.toLocalDate());
        boolean seAleja = !cambioDeDia && GeoUtils.distanciaMetros(
                grupo.getLatitudReferencia(), grupo.getLongitudReferencia(), lat, lon) > RADIO_METROS;

        if (cambioDeDia || seAleja) {
            cerrarGrupo(grupo, vendedorRef);
            abrirNuevoGrupo(vendedorId, vendedorRef, lat, lon, fecha);
        } else {
            grupo.setHoraUltimoPunto(fecha);
            grupo.setSumaLatitud(grupo.getSumaLatitud() + lat);
            grupo.setSumaLongitud(grupo.getSumaLongitud() + lon);
            grupo.setCantidadPuntos(grupo.getCantidadPuntos() + 1);
            grupoActualRepository.save(grupo);
        }
    }

    private void abrirNuevoGrupo(VendedorId vendedorId, Vendedor vendedorRef,
                                  double lat, double lon, LocalDateTime fecha) {
        ParadaVendedorGrupoActual nuevo = new ParadaVendedorGrupoActual();
        nuevo.setId(vendedorId);
        nuevo.setVendedor(vendedorRef);
        nuevo.setDia(fecha.toLocalDate());
        nuevo.setLatitudReferencia(lat);
        nuevo.setLongitudReferencia(lon);
        nuevo.setHoraInicio(fecha);
        nuevo.setHoraUltimoPunto(fecha);
        nuevo.setSumaLatitud(lat);
        nuevo.setSumaLongitud(lon);
        nuevo.setCantidadPuntos(1);
        grupoActualRepository.save(nuevo);
    }

    private void cerrarGrupo(ParadaVendedorGrupoActual grupo, Vendedor vendedorRef) {
        Duration duracion = Duration.between(grupo.getHoraInicio(), grupo.getHoraUltimoPunto());
        if (duracion.compareTo(DURACION_MINIMA) < 0) {
            return;
        }
        ParadaVendedor parada = new ParadaVendedor();
        parada.setVendedor(vendedorRef);
        parada.setLatitud(grupo.getSumaLatitud() / grupo.getCantidadPuntos());
        parada.setLongitud(grupo.getSumaLongitud() / grupo.getCantidadPuntos());
        parada.setHoraInicio(grupo.getHoraInicio());
        parada.setHoraFin(grupo.getHoraUltimoPunto());
        parada.setCalle(CALLE_PENDIENTE);
        parada = paradaVendedorRepository.save(parada);
        eventPublisher.publishEvent(new ParadaDetectadaEvent(parada.getId(), parada.getLatitud(), parada.getLongitud()));
    }
}

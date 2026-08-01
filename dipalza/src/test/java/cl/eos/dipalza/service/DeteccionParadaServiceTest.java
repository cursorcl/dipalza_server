package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.ParadaVendedor;
import cl.eos.dipalza.entity.ParadaVendedorGrupoActual;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.event.ParadaDetectadaEvent;
import cl.eos.dipalza.repository.ParadaVendedorGrupoActualRepository;
import cl.eos.dipalza.repository.ParadaVendedorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeteccionParadaServiceTest {

    @Mock
    private ParadaVendedorGrupoActualRepository grupoActualRepository;
    @Mock
    private ParadaVendedorRepository paradaVendedorRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DeteccionParadaService service;

    private final VendedorId vendedorId = new VendedorId("001", "V");
    private final Vendedor vendedorRef = new Vendedor(vendedorId);

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new DeteccionParadaService(grupoActualRepository, paradaVendedorRepository, eventPublisher);
    }

    @Test
    void sinGrupoPrevio_abreGrupoNuevo() {
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.empty());
        LocalDateTime fecha = LocalDateTime.of(2026, 8, 1, 10, 0);

        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.45, -70.65, fecha);

        ArgumentCaptor<ParadaVendedorGrupoActual> captor = ArgumentCaptor.forClass(ParadaVendedorGrupoActual.class);
        verify(grupoActualRepository).save(captor.capture());
        ParadaVendedorGrupoActual grupo = captor.getValue();
        assertThat(grupo.getLatitudReferencia()).isEqualTo(-33.45);
        assertThat(grupo.getLongitudReferencia()).isEqualTo(-70.65);
        assertThat(grupo.getCantidadPuntos()).isEqualTo(1);
        assertThat(grupo.getSumaLatitud()).isEqualTo(-33.45);
        assertThat(grupo.getDia()).isEqualTo(fecha.toLocalDate());
        verifyNoInteractions(paradaVendedorRepository, eventPublisher);
    }

    @Test
    void dentroDelRadioMismoDia_extiendeGrupo() {
        ParadaVendedorGrupoActual grupo = grupoAbierto(LocalDateTime.of(2026, 8, 1, 10, 0), -33.45, -70.65);
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));

        LocalDateTime fechaNueva = LocalDateTime.of(2026, 8, 1, 10, 5);
        // ~10m de distancia, dentro del radio de 100m
        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.45009, -70.65, fechaNueva);

        ArgumentCaptor<ParadaVendedorGrupoActual> captor = ArgumentCaptor.forClass(ParadaVendedorGrupoActual.class);
        verify(grupoActualRepository).save(captor.capture());
        ParadaVendedorGrupoActual actualizado = captor.getValue();
        assertThat(actualizado.getCantidadPuntos()).isEqualTo(2);
        assertThat(actualizado.getSumaLatitud()).isEqualTo(-33.45 + -33.45009);
        assertThat(actualizado.getHoraUltimoPunto()).isEqualTo(fechaNueva);
        assertThat(actualizado.getLatitudReferencia()).isEqualTo(-33.45); // referencia NO cambia
        verifyNoInteractions(paradaVendedorRepository, eventPublisher);
    }

    @Test
    void seAlejaYDuracionSuficiente_cierraYPersisteComoParada() {
        LocalDateTime inicio = LocalDateTime.of(2026, 8, 1, 10, 0);
        ParadaVendedorGrupoActual grupo = grupoAbierto(inicio, -33.45, -70.65);
        grupo.setHoraUltimoPunto(inicio.plusMinutes(12));
        grupo.setSumaLatitud(-33.45 * 3);
        grupo.setSumaLongitud(-70.65 * 3);
        grupo.setCantidadPuntos(3);
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));
        when(paradaVendedorRepository.save(any(ParadaVendedor.class)))
                .thenAnswer(inv -> { ParadaVendedor p = inv.getArgument(0); p.setId(99L); return p; });

        LocalDateTime fechaLejos = inicio.plusMinutes(13);
        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.50, -70.70, fechaLejos); // >100m

        ArgumentCaptor<ParadaVendedor> paradaCaptor = ArgumentCaptor.forClass(ParadaVendedor.class);
        verify(paradaVendedorRepository).save(paradaCaptor.capture());
        ParadaVendedor persistida = paradaCaptor.getValue();
        assertThat(persistida.getLatitud()).isEqualTo(-33.45);
        assertThat(persistida.getLongitud()).isEqualTo(-70.65);
        assertThat(persistida.getHoraInicio()).isEqualTo(inicio);
        assertThat(persistida.getHoraFin()).isEqualTo(inicio.plusMinutes(12));
        assertThat(persistida.getCalle()).isEqualTo("Calle no disponible");

        verify(eventPublisher).publishEvent(new ParadaDetectadaEvent(99L, -33.45, -70.65));

        // se abre un grupo nuevo con el punto entrante como referencia.
        // cerrarGrupo() NUNCA llama a grupoActualRepository (solo persiste la
        // ParadaVendedor y publica el evento) — el UNICO save() del grupo en
        // este flujo viene de abrirNuevoGrupo(), asi que se espera 1 sola
        // invocacion, no 2.
        ArgumentCaptor<ParadaVendedorGrupoActual> grupoCaptor = ArgumentCaptor.forClass(ParadaVendedorGrupoActual.class);
        verify(grupoActualRepository, times(1)).save(grupoCaptor.capture());
        ParadaVendedorGrupoActual nuevoGrupo = grupoCaptor.getValue();
        assertThat(nuevoGrupo.getLatitudReferencia()).isEqualTo(-33.50);
        assertThat(nuevoGrupo.getCantidadPuntos()).isEqualTo(1);
    }

    @Test
    void seAlejaJustoSobreElRadio_102m_cierraYPersisteComoParada() {
        LocalDateTime inicio = LocalDateTime.of(2026, 8, 1, 10, 0);
        ParadaVendedorGrupoActual grupo = grupoAbierto(inicio, -33.45, -70.65);
        grupo.setHoraUltimoPunto(inicio.plusMinutes(12)); // duracion suficiente
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));
        when(paradaVendedorRepository.save(any(ParadaVendedor.class)))
                .thenAnswer(inv -> { ParadaVendedor p = inv.getArgument(0); p.setId(100L); return p; });

        // -33.45092 queda a ~102.3m de (-33.45, -70.65) segun GeoUtils.distanciaMetros (Haversine,
        // desplazamiento puro en latitud) — justo por encima del radio de 100m. Confirma que
        // '> 100m' SI dispara el cierre del grupo (no solo distancias muy por fuera, como ~7km).
        LocalDateTime fechaLejos = inicio.plusMinutes(13);
        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.45092, -70.65, fechaLejos);

        verify(paradaVendedorRepository).save(any(ParadaVendedor.class));
        verify(eventPublisher).publishEvent(any(ParadaDetectadaEvent.class));
    }

    @Test
    void duracionExactamenteDiezMinutos_calificaYPersisteComoParada() {
        LocalDateTime inicio = LocalDateTime.of(2026, 8, 1, 10, 0);
        ParadaVendedorGrupoActual grupo = grupoAbierto(inicio, -33.45, -70.65);
        grupo.setHoraUltimoPunto(inicio.plusMinutes(10)); // duracion EXACTA de 10 min (limite inclusivo)
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));
        when(paradaVendedorRepository.save(any(ParadaVendedor.class)))
                .thenAnswer(inv -> { ParadaVendedor p = inv.getArgument(0); p.setId(101L); return p; });

        // cerrarGrupo() solo descarta si duracion.compareTo(DURACION_MINIMA) < 0, es decir
        // ESTRICTAMENTE menor a 10 min; exactamente 10 min debe calificar.
        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.50, -70.70, inicio.plusMinutes(11)); // >100m

        verify(paradaVendedorRepository).save(any(ParadaVendedor.class));
        verify(eventPublisher).publishEvent(any(ParadaDetectadaEvent.class));
    }

    @Test
    void seAlejaPeroDuracionInsuficiente_descartaSinPersistir() {
        LocalDateTime inicio = LocalDateTime.of(2026, 8, 1, 10, 0);
        ParadaVendedorGrupoActual grupo = grupoAbierto(inicio, -33.45, -70.65);
        grupo.setHoraUltimoPunto(inicio.plusMinutes(4)); // < 10 min
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));

        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.50, -70.70, inicio.plusMinutes(5));

        verifyNoInteractions(paradaVendedorRepository, eventPublisher);
        // igual se abre grupo nuevo (1 sola invocacion, ver nota en el test anterior)
        verify(grupoActualRepository, times(1)).save(any());
    }

    @Test
    void cambioDeDiaCalendario_fuerzaCierreAunqueNoSeHayaMovido() {
        LocalDateTime inicioAyer = LocalDateTime.of(2026, 7, 31, 23, 50);
        ParadaVendedorGrupoActual grupo = grupoAbierto(inicioAyer, -33.45, -70.65);
        grupo.setHoraUltimoPunto(inicioAyer.plusMinutes(11)); // cruza medianoche, califica por duracion
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));
        when(paradaVendedorRepository.save(any(ParadaVendedor.class)))
                .thenAnswer(inv -> { ParadaVendedor p = inv.getArgument(0); p.setId(1L); return p; });

        LocalDateTime hoyMismaUbicacion = LocalDateTime.of(2026, 8, 1, 0, 5); // MISMA lat/lon, otro dia
        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.45, -70.65, hoyMismaUbicacion);

        verify(paradaVendedorRepository).save(any(ParadaVendedor.class));
        verify(eventPublisher).publishEvent(any(ParadaDetectadaEvent.class));
    }

    @Test
    void grupoAbiertoUnSoloPunto_alCerrarNoCalifica() {
        LocalDateTime fecha = LocalDateTime.of(2026, 8, 1, 10, 0);
        ParadaVendedorGrupoActual grupo = grupoAbierto(fecha, -33.45, -70.65); // 1 solo punto, duracion 0

        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));

        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.50, -70.70, fecha.plusSeconds(30));

        verifyNoInteractions(paradaVendedorRepository, eventPublisher);
    }

    private ParadaVendedorGrupoActual grupoAbierto(LocalDateTime horaInicio, double lat, double lon) {
        ParadaVendedorGrupoActual grupo = new ParadaVendedorGrupoActual();
        grupo.setId(vendedorId);
        grupo.setVendedor(vendedorRef);
        grupo.setDia(horaInicio.toLocalDate());
        grupo.setLatitudReferencia(lat);
        grupo.setLongitudReferencia(lon);
        grupo.setHoraInicio(horaInicio);
        grupo.setHoraUltimoPunto(horaInicio);
        grupo.setSumaLatitud(lat);
        grupo.setSumaLongitud(lon);
        grupo.setCantidadPuntos(1);
        return grupo;
    }
}

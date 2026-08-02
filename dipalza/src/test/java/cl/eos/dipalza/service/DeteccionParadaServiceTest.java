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
import static org.mockito.ArgumentMatchers.eq;
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
        assertThat(grupo.getParadaVendedorId()).isNull();
        verifyNoInteractions(paradaVendedorRepository, eventPublisher);
    }

    @Test
    void dentroDelRadioMismoDia_duracionAunInsuficiente_extiendeSinCrearParada() {
        ParadaVendedorGrupoActual grupo = grupoAbierto(LocalDateTime.of(2026, 8, 1, 10, 0), -33.45, -70.65);
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));

        LocalDateTime fechaNueva = LocalDateTime.of(2026, 8, 1, 10, 5); // 5 min, < 10 min umbral
        // ~10m de distancia, dentro del radio de 100m
        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.45009, -70.65, fechaNueva);

        ArgumentCaptor<ParadaVendedorGrupoActual> captor = ArgumentCaptor.forClass(ParadaVendedorGrupoActual.class);
        verify(grupoActualRepository).save(captor.capture());
        ParadaVendedorGrupoActual actualizado = captor.getValue();
        assertThat(actualizado.getCantidadPuntos()).isEqualTo(2);
        assertThat(actualizado.getSumaLatitud()).isEqualTo(-33.45 + -33.45009);
        assertThat(actualizado.getHoraUltimoPunto()).isEqualTo(fechaNueva);
        assertThat(actualizado.getLatitudReferencia()).isEqualTo(-33.45); // referencia NO cambia
        assertThat(actualizado.getParadaVendedorId()).isNull(); // aun no califica
        verifyNoInteractions(paradaVendedorRepository, eventPublisher);
    }

    @Test
    void extensionCruzaElUmbralPorPrimeraVez_creaLaParadaYPublicaEvento() {
        LocalDateTime inicio = LocalDateTime.of(2026, 8, 1, 10, 0);
        ParadaVendedorGrupoActual grupo = grupoAbierto(inicio, -33.45, -70.65);
        grupo.setHoraUltimoPunto(inicio.plusMinutes(9)); // 9 min acumulados, aun no califica
        grupo.setSumaLatitud(-33.45 * 2);
        grupo.setSumaLongitud(-70.65 * 2);
        grupo.setCantidadPuntos(2);
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));
        when(paradaVendedorRepository.save(any(ParadaVendedor.class)))
                .thenAnswer(inv -> { ParadaVendedor p = inv.getArgument(0); p.setId(50L); return p; });

        LocalDateTime fechaCruceUmbral = inicio.plusMinutes(11); // 11 min totales, cruza el umbral de 10
        // sigue dentro del radio (~10m)
        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.45009, -70.65, fechaCruceUmbral);

        ArgumentCaptor<ParadaVendedor> paradaCaptor = ArgumentCaptor.forClass(ParadaVendedor.class);
        verify(paradaVendedorRepository).save(paradaCaptor.capture());
        ParadaVendedor creada = paradaCaptor.getValue();
        assertThat(creada.getHoraInicio()).isEqualTo(inicio);
        assertThat(creada.getHoraFin()).isEqualTo(fechaCruceUmbral);
        assertThat(creada.getCalle()).isEqualTo("Calle no disponible");
        // promedio de 3 puntos: -33.45, -33.45, -33.45009
        assertThat(creada.getLatitud()).isEqualTo((-33.45 * 2 + -33.45009) / 3);

        verify(eventPublisher).publishEvent(new ParadaDetectadaEvent(50L, creada.getLatitud(), creada.getLongitud()));

        ArgumentCaptor<ParadaVendedorGrupoActual> grupoCaptor = ArgumentCaptor.forClass(ParadaVendedorGrupoActual.class);
        verify(grupoActualRepository).save(grupoCaptor.capture());
        assertThat(grupoCaptor.getValue().getParadaVendedorId()).isEqualTo(50L);

        verify(paradaVendedorRepository, never()).actualizarUbicacionYHoraFin(any(), anyDouble(), anyDouble(), any());
    }

    @Test
    void extensionConDuracionExactamenteDiezMinutos_yaCalifica() {
        LocalDateTime inicio = LocalDateTime.of(2026, 8, 1, 10, 0);
        ParadaVendedorGrupoActual grupo = grupoAbierto(inicio, -33.45, -70.65);
        grupo.setHoraUltimoPunto(inicio.plusMinutes(9));
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));
        when(paradaVendedorRepository.save(any(ParadaVendedor.class)))
                .thenAnswer(inv -> { ParadaVendedor p = inv.getArgument(0); p.setId(101L); return p; });

        // El umbral es inclusivo: extenderGrupo() crea la parada cuando
        // duracion.compareTo(DURACION_MINIMA) >= 0, es decir exactamente 10 min ya califica.
        LocalDateTime fechaDiezMinutosExactos = inicio.plusMinutes(10);
        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.45009, -70.65, fechaDiezMinutosExactos);

        ArgumentCaptor<ParadaVendedor> paradaCaptor = ArgumentCaptor.forClass(ParadaVendedor.class);
        verify(paradaVendedorRepository).save(paradaCaptor.capture());
        assertThat(paradaCaptor.getValue().getHoraFin()).isEqualTo(fechaDiezMinutosExactos);
        verify(eventPublisher).publishEvent(any(ParadaDetectadaEvent.class));
    }

    @Test
    void extensionConGrupoYaCalificado_actualizaLaParadaExistente_sinCrearNiPublicarDeNuevo() {
        LocalDateTime inicio = LocalDateTime.of(2026, 8, 1, 10, 0);
        ParadaVendedorGrupoActual grupo = grupoAbierto(inicio, -33.45, -70.65);
        grupo.setHoraUltimoPunto(inicio.plusMinutes(11));
        grupo.setSumaLatitud(-33.45 * 3);
        grupo.setSumaLongitud(-70.65 * 3);
        grupo.setCantidadPuntos(3);
        grupo.setParadaVendedorId(50L); // ya califico antes, la parada 50 ya existe
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));

        LocalDateTime fechaNueva = inicio.plusMinutes(15);
        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.45009, -70.65, fechaNueva);

        double latPromedioEsperado = (-33.45 * 3 + -33.45009) / 4;
        verify(paradaVendedorRepository).actualizarUbicacionYHoraFin(
                eq(50L), eq(latPromedioEsperado), anyDouble(), eq(fechaNueva));
        verify(paradaVendedorRepository, never()).save(any(ParadaVendedor.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void seAlejaJustoSobreElRadio_102m_conGrupoYaCalificado_soloAbreGrupoNuevo() {
        LocalDateTime inicio = LocalDateTime.of(2026, 8, 1, 10, 0);
        ParadaVendedorGrupoActual grupo = grupoAbierto(inicio, -33.45, -70.65);
        grupo.setHoraUltimoPunto(inicio.plusMinutes(12));
        grupo.setParadaVendedorId(77L); // ya califico y fue actualizado durante el trayecto
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));

        // -33.45092 queda a ~102.3m de (-33.45, -70.65) — justo por encima del radio de 100m
        LocalDateTime fechaLejos = inicio.plusMinutes(13);
        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.45092, -70.65, fechaLejos);

        // el grupo que se cierra ya estaba al dia (ultima extension lo actualizo) -- cerrar no hace
        // NINGUN trabajo de persistencia nuevo sobre la parada 77
        verifyNoInteractions(paradaVendedorRepository, eventPublisher);
        ArgumentCaptor<ParadaVendedorGrupoActual> grupoCaptor = ArgumentCaptor.forClass(ParadaVendedorGrupoActual.class);
        verify(grupoActualRepository).save(grupoCaptor.capture());
        ParadaVendedorGrupoActual nuevoGrupo = grupoCaptor.getValue();
        assertThat(nuevoGrupo.getLatitudReferencia()).isEqualTo(-33.45092);
        assertThat(nuevoGrupo.getParadaVendedorId()).isNull(); // grupo nuevo, aun no califica
    }

    @Test
    void seAlejaConGrupoNuncaCalifico_descartaSinPersistir() {
        LocalDateTime inicio = LocalDateTime.of(2026, 8, 1, 10, 0);
        ParadaVendedorGrupoActual grupo = grupoAbierto(inicio, -33.45, -70.65);
        grupo.setHoraUltimoPunto(inicio.plusMinutes(4)); // < 10 min, nunca califico
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));

        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.50, -70.70, inicio.plusMinutes(5));

        verifyNoInteractions(paradaVendedorRepository, eventPublisher);
        verify(grupoActualRepository, times(1)).save(any());
    }

    @Test
    void cambioDeDiaCalendario_conGrupoQueCalificoAyer_soloAbreGrupoNuevoHoy() {
        LocalDateTime inicioAyer = LocalDateTime.of(2026, 7, 31, 23, 50);
        ParadaVendedorGrupoActual grupo = grupoAbierto(inicioAyer, -33.45, -70.65);
        grupo.setHoraUltimoPunto(inicioAyer.plusMinutes(11)); // califico y ya fue persistido/actualizado ayer
        grupo.setParadaVendedorId(1L);
        when(grupoActualRepository.findById(vendedorId)).thenReturn(Optional.of(grupo));

        LocalDateTime hoyMismaUbicacion = LocalDateTime.of(2026, 8, 1, 0, 5); // MISMA lat/lon, otro dia
        service.procesarNuevoPunto(vendedorId, vendedorRef, -33.45, -70.65, hoyMismaUbicacion);

        // el cambio de dia fuerza abrir grupo nuevo; la parada de ayer (id 1) no necesita ningun
        // trabajo adicional porque ya quedo al dia por la ultima extension de ayer
        verifyNoInteractions(paradaVendedorRepository, eventPublisher);
        ArgumentCaptor<ParadaVendedorGrupoActual> grupoCaptor = ArgumentCaptor.forClass(ParadaVendedorGrupoActual.class);
        verify(grupoActualRepository).save(grupoCaptor.capture());
        assertThat(grupoCaptor.getValue().getDia()).isEqualTo(LocalDateTime.of(2026, 8, 1, 0, 5).toLocalDate());
    }

    @Test
    void grupoAbiertoUnSoloPunto_alAlejarseInmediatamenteNoCalifica() {
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

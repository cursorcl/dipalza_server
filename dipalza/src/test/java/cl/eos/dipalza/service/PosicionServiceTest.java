package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Posicion;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.model.HistorialResumenDiaDTO;
import cl.eos.dipalza.model.PosicionDTO;
import cl.eos.dipalza.repository.HistorialPosicionRepository;
import cl.eos.dipalza.repository.PosicionRepository;
import cl.eos.dipalza.repository.VendedorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PosicionServiceTest {

    @Mock PosicionRepository posicionRepo;
    @Mock HistorialPosicionRepository historialRepo;
    @Mock VendedorRepository vendedorRepo;
    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock DeteccionParadaService deteccionParadaService;
    @InjectMocks PosicionService service;

    private PosicionDTO dto(String vendedorId) {
        return new PosicionDTO(vendedorId, "0 ", "Juan", LocalDateTime.now(), -33.4, -70.6);
    }

    @Test
    void obtenerActuales_retornaListaMapeada() {
        Posicion p = new Posicion();
        p.setId(new VendedorId("V01", "0 "));
        p.setLatitud(-33.4);
        p.setLongitud(-70.6);
        p.setFechaHora(LocalDateTime.now());
        Vendedor v = new Vendedor();
        v.setId(new VendedorId("V01", "0 "));
        v.setNombre("Juan");
        p.setVendedor(v);

        when(posicionRepo.findAll()).thenReturn(List.of(p));
        List<PosicionDTO> result = service.obtenerActuales();
        assertThat(result).hasSize(1);
    }

    @Test
    void registrarUbicacion_posicionNueva_creaNuevaEntidad() {
        VendedorId vid = new VendedorId("V01", "0 ");
        Vendedor vend = new Vendedor();
        vend.setId(vid);

        when(vendedorRepo.getReferenceById(vid)).thenReturn(vend);
        when(posicionRepo.findByVendedorId(vid)).thenReturn(null);

        service.registrarUbicacion(dto("V01"));

        verify(posicionRepo).save(any());
        verify(historialRepo).save(any());
    }

    @Test
    void registrarUbicacion_posicionExistente_actualizaEntidad() {
        VendedorId vid = new VendedorId("V01", "0 ");
        Vendedor vend = new Vendedor();
        vend.setId(vid);

        Posicion existente = new Posicion();
        existente.setId(vid);

        when(vendedorRepo.getReferenceById(vid)).thenReturn(vend);
        when(posicionRepo.findByVendedorId(vid)).thenReturn(existente);

        service.registrarUbicacion(dto("V01"));

        verify(posicionRepo).save(existente);
    }

    @Test
    void registrarUbicacion_enviaAlTopicWebSocket_conNombreDelVendedor() {
        VendedorId vid = new VendedorId("V01", "0 ");
        Vendedor vend = new Vendedor();
        vend.setId(vid);
        vend.setNombre("Juan");
        when(vendedorRepo.getReferenceById(vid)).thenReturn(vend);
        when(posicionRepo.findByVendedorId(vid)).thenReturn(null);

        service.registrarUbicacion(dto("V01"));

        ArgumentCaptor<PosicionDTO> captor = ArgumentCaptor.forClass(PosicionDTO.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/posiciones"), captor.capture());
        assertThat(captor.getValue().vendedorNombre()).isEqualTo("Juan");
    }

    @Test
    void registrarUbicacion_invocaDeteccionParadaServiceConLosDatosCorrectos() {
        PosicionDTO dto = dto("V01");
        when(vendedorRepo.getReferenceById(any())).thenReturn(new Vendedor());
        when(posicionRepo.findByVendedorId(any())).thenReturn(null);

        service.registrarUbicacion(dto);

        ArgumentCaptor<VendedorId> idCaptor = ArgumentCaptor.forClass(VendedorId.class);
        verify(deteccionParadaService).procesarNuevoPunto(
                idCaptor.capture(), any(Vendedor.class), eq(dto.latitud()), eq(dto.longitud()), eq(dto.fechaHora()));
        assertThat(idCaptor.getValue().getCodigo()).isEqualTo(dto.vendedorId());
    }

    @Test
    void buscarResumenHistorico_mapeaProyeccionesDelRepositorioADTO() {
        HistorialPosicionRepository.ResumenDiaProjection proyeccion = mock(HistorialPosicionRepository.ResumenDiaProjection.class);
        when(proyeccion.getDia()).thenReturn(LocalDate.of(2026, 8, 10));
        when(proyeccion.getCantidadPuntos()).thenReturn(120L);
        when(proyeccion.getHoraInicio()).thenReturn(LocalDateTime.of(2026, 8, 10, 10, 0));
        when(proyeccion.getHoraFin()).thenReturn(LocalDateTime.of(2026, 8, 10, 19, 0));
        when(historialRepo.resumenPorDia(eq("V01"), eq("0 "), any())).thenReturn(List.of(proyeccion));

        List<HistorialResumenDiaDTO> resultado = service.buscarResumenHistorico("V01", "0 ");

        assertThat(resultado).hasSize(1);
        HistorialResumenDiaDTO dto = resultado.get(0);
        assertThat(dto.dia()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(dto.cantidadPuntos()).isEqualTo(120L);
        assertThat(dto.horaInicio()).isEqualTo(LocalDateTime.of(2026, 8, 10, 10, 0));
        assertThat(dto.horaFin()).isEqualTo(LocalDateTime.of(2026, 8, 10, 19, 0));
    }

    @Test
    void buscarResumenHistorico_consultaDesdeHace30Dias() {
        when(historialRepo.resumenPorDia(any(), any(), any())).thenReturn(List.of());

        service.buscarResumenHistorico("V01", "0 ");

        ArgumentCaptor<LocalDateTime> desdeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(historialRepo).resumenPorDia(eq("V01"), eq("0 "), desdeCaptor.capture());
        assertThat(desdeCaptor.getValue()).isEqualTo(LocalDate.now().minusDays(30).atStartOfDay());
    }

    @Test
    void registrarUbicacion_siDeteccionParadaServiceFalla_igualPersisteYNotifica() {
        PosicionDTO dto = dto("V01");
        when(vendedorRepo.getReferenceById(any())).thenReturn(new Vendedor());
        when(posicionRepo.findByVendedorId(any())).thenReturn(null);
        doThrow(new RuntimeException("fallo simulado"))
                .when(deteccionParadaService).procesarNuevoPunto(any(), any(), anyDouble(), anyDouble(), any());

        service.registrarUbicacion(dto);

        verify(posicionRepo).save(any());
        verify(historialRepo).save(any());
        verify(messagingTemplate).convertAndSend(eq("/topic/posiciones"), any(PosicionDTO.class));
    }
}

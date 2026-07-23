package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Posicion;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
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
}

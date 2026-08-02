package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.ParadaVendedor;
import cl.eos.dipalza.entity.ParadaVendedorGrupoActual;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.repository.ParadaVendedorGrupoActualRepository;
import cl.eos.dipalza.repository.ParadaVendedorRepository;
import cl.eos.dipalza.specifications.PosicionFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeteccionServiceTest {

    @Mock
    private ParadaVendedorRepository paradaVendedorRepository;
    @Mock
    private ParadaVendedorGrupoActualRepository grupoActualRepository;

    @Test
    void buscarHistorico_paradaSinGrupoAbiertoApuntandoAElla_enCursoFalse() {
        DeteccionService service = new DeteccionService(paradaVendedorRepository, grupoActualRepository);
        Vendedor vendedor = new Vendedor(new VendedorId("001", "V"));
        vendedor.setNombre("Juan Perez");
        ParadaVendedor parada = new ParadaVendedor();
        parada.setId(1L);
        parada.setVendedor(vendedor);
        parada.setLatitud(-33.45);
        parada.setLongitud(-70.65);
        parada.setCalle("Av. Providencia");
        parada.setHoraInicio(LocalDateTime.of(2026, 8, 1, 10, 0));
        parada.setHoraFin(LocalDateTime.of(2026, 8, 1, 10, 15));
        when(paradaVendedorRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(parada));
        when(grupoActualRepository.findAll()).thenReturn(List.of()); // ningun grupo abierto

        List<cl.eos.dipalza.model.ParadaVendedorDTO> resultado =
                service.buscarHistorico(new PosicionFilter(List.of(vendedor.getId()), null, null, LocalDate.of(2026, 8, 1)));

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(0).calle()).isEqualTo("Av. Providencia");
        assertThat(resultado.get(0).vendedorNombre()).isEqualTo("Juan Perez");
        assertThat(resultado.get(0).enCurso()).isFalse();
    }

    @Test
    void buscarHistorico_paradaConGrupoAbiertoApuntandoAElla_enCursoTrue() {
        DeteccionService service = new DeteccionService(paradaVendedorRepository, grupoActualRepository);
        Vendedor vendedor = new Vendedor(new VendedorId("001", "V"));
        ParadaVendedor parada = new ParadaVendedor();
        parada.setId(1L);
        parada.setVendedor(vendedor);
        when(paradaVendedorRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(parada));

        ParadaVendedorGrupoActual grupoAbierto = new ParadaVendedorGrupoActual();
        grupoAbierto.setId(new VendedorId("001", "V"));
        grupoAbierto.setParadaVendedorId(1L); // apunta exactamente a la parada 1
        ParadaVendedorGrupoActual grupoDeOtroVendedorSinCalificar = new ParadaVendedorGrupoActual();
        grupoDeOtroVendedorSinCalificar.setId(new VendedorId("002", "V"));
        grupoDeOtroVendedorSinCalificar.setParadaVendedorId(null); // aun no califica, no debe romper el filtro
        when(grupoActualRepository.findAll()).thenReturn(List.of(grupoAbierto, grupoDeOtroVendedorSinCalificar));

        List<cl.eos.dipalza.model.ParadaVendedorDTO> resultado =
                service.buscarHistorico(new PosicionFilter(List.of(vendedor.getId()), null, null, LocalDate.of(2026, 8, 1)));

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(0).enCurso()).isTrue();
    }
}

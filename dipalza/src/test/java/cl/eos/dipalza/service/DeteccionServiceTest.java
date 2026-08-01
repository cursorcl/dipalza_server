package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.ParadaVendedor;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
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

    @Test
    void buscarHistorico_delegaAlRepositorioYMapeaADTO() {
        DeteccionService service = new DeteccionService(paradaVendedorRepository);
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

        List<cl.eos.dipalza.model.ParadaVendedorDTO> resultado =
                service.buscarHistorico(new PosicionFilter(List.of(vendedor.getId()), null, null, LocalDate.of(2026, 8, 1)));

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(0).calle()).isEqualTo("Av. Providencia");
        assertThat(resultado.get(0).vendedorNombre()).isEqualTo("Juan Perez");
    }
}

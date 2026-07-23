package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.model.VendedorDTO;
import cl.eos.dipalza.repository.VendedorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VendedorServiceTest {

    @Mock VendedorRepository vendedorRepository;
    @InjectMocks VendedorService service;

    @Test
    void listarTodos_retornaTodosLosVendedoresMapeados() {
        Vendedor v1 = new Vendedor();
        v1.setId(new VendedorId("001", "0"));
        v1.setNombre("Juan Perez");

        Vendedor v2 = new Vendedor();
        v2.setId(new VendedorId("002", "0"));
        v2.setNombre("Maria Soto");

        when(vendedorRepository.findAll()).thenReturn(List.of(v1, v2));

        List<VendedorDTO> result = service.listarTodos();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).codigo()).isEqualTo("001");
        assertThat(result.get(0).nombre()).isEqualTo("Juan Perez");
        assertThat(result.get(1).codigo()).isEqualTo("002");
    }

    @Test
    void listarTodos_sinVendedores_retornaListaVacia() {
        when(vendedorRepository.findAll()).thenReturn(List.of());

        List<VendedorDTO> result = service.listarTodos();

        assertThat(result).isEmpty();
    }
}

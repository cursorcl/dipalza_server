package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Ruta;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.VendedorRuta;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.entity.ids.VendedorRutaId;
import cl.eos.dipalza.mapper.RutaMapper;
import cl.eos.dipalza.model.RutaDTO;
import cl.eos.dipalza.repository.RutaRepository;
import cl.eos.dipalza.repository.VendedorRepository;
import cl.eos.dipalza.repository.VendedorRutaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class VendedorRutaServiceTest {

    @Mock VendedorRutaRepository vendedorRutaRepo;
    @Mock VendedorRepository vendedorRepo;
    @Mock RutaRepository rutaRepo;
    @Mock RutaMapper rutaMapper;
    @InjectMocks VendedorRutaService service;

    private VendedorRuta asociacion(String codigoRuta) {
        VendedorRuta vr = new VendedorRuta();
        vr.setId(new VendedorRutaId("001", "V", codigoRuta));
        Ruta ruta = new Ruta();
        ruta.setCodigo(codigoRuta);
        vr.setRuta(ruta);
        return vr;
    }

    private RutaDTO dto(String codigo) {
        RutaDTO d = new RutaDTO();
        d.setCodigo(codigo);
        return d;
    }

    @Test
    void getRutasByVendedor_conAsociaciones_retornaListaMapeada() {
        VendedorRuta vr = asociacion("R01");
        when(vendedorRutaRepo.findByIdCodigoVendedorAndIdTipoVendedor("001", "V"))
                .thenReturn(List.of(vr));
        when(rutaMapper.toDTO(vr.getRuta())).thenReturn(dto("R01"));

        List<RutaDTO> result = service.getRutasByVendedor("001", "V");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCodigo()).isEqualTo("R01");
    }

    @Test
    void getRutasByVendedor_sinAsociaciones_retornaListaVacia() {
        when(vendedorRutaRepo.findByIdCodigoVendedorAndIdTipoVendedor("001", "V"))
                .thenReturn(List.of());

        assertThat(service.getRutasByVendedor("001", "V")).isEmpty();
    }

    @Test
    void asignarRutas_vendedorNoExiste_lanza404() {
        when(vendedorRepo.findById(new VendedorId("001", "V"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.asignarRutas("001", "V", List.of("R01")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Vendedor no encontrado");

        verifyNoInteractions(rutaRepo, vendedorRutaRepo);
    }

    @Test
    void asignarRutas_rutaNoExiste_lanza404() {
        when(vendedorRepo.findById(new VendedorId("001", "V"))).thenReturn(Optional.of(new Vendedor()));
        when(rutaRepo.findById("R99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.asignarRutas("001", "V", List.of("R99")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("R99");

        verify(vendedorRutaRepo, never()).deleteByIdCodigoVendedorAndIdTipoVendedor(any(), any());
    }

    @Test
    void asignarRutas_datosValidos_reemplazaSetCompleto() {
        Ruta r01 = new Ruta();
        r01.setCodigo("R01");
        Ruta r02 = new Ruta();
        r02.setCodigo("R02");
        when(vendedorRepo.findById(new VendedorId("001", "V"))).thenReturn(Optional.of(new Vendedor()));
        when(rutaRepo.findById("R01")).thenReturn(Optional.of(r01));
        when(rutaRepo.findById("R02")).thenReturn(Optional.of(r02));
        when(vendedorRutaRepo.findByIdCodigoVendedorAndIdTipoVendedor("001", "V"))
                .thenReturn(List.of(asociacion("R01"), asociacion("R02")));
        when(rutaMapper.toDTO(any())).thenReturn(dto("R01"), dto("R02"));

        List<RutaDTO> result = service.asignarRutas("001", "V", List.of("R01", "R02"));

        verify(vendedorRutaRepo).deleteByIdCodigoVendedorAndIdTipoVendedor("001", "V");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VendedorRuta>> captor = ArgumentCaptor.forClass(List.class);
        verify(vendedorRutaRepo).saveAll(captor.capture());
        // Reproduce el bug real: sin `vr.setRuta(...)`, esta aserción fallaría
        // porque `getRuta()` quedaría null pese a haber validado la ruta.
        assertThat(captor.getValue()).extracting(VendedorRuta::getRuta).doesNotContainNull();

        assertThat(result).hasSize(2);
    }
}

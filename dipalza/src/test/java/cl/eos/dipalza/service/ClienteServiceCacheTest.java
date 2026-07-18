package cl.eos.dipalza.service;

import cl.eos.dipalza.config.CacheConfig;
import cl.eos.dipalza.entity.Cliente;
import cl.eos.dipalza.entity.ids.ClienteId;
import cl.eos.dipalza.mapper.ClienteMapper;
import cl.eos.dipalza.model.ClienteDTO;
import cl.eos.dipalza.repository.ClienteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A diferencia de {@link ClienteServiceTest} (unitario puro, con {@code @InjectMocks}),
 * este test carga un contexto Spring real para que el proxy de {@code @Cacheable}/
 * {@code @CacheEvict} esté activo. Usa códigos distintos por test para que el estado
 * del caché (compartido entre tests, ya que el contexto Spring se reutiliza) no
 * contamine otros casos.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {ClienteService.class, ClienteMapper.class, CacheConfig.class})
class ClienteServiceCacheTest {

    @Autowired
    ClienteService clienteService;

    @MockitoBean
    ClienteRepository clienteRepository;

    private Cliente entidad(String rut, String codigo) {
        Cliente c = new Cliente();
        c.setId(new ClienteId(rut, codigo));
        c.setRazon("Test SA");
        return c;
    }

    @Test
    void getClientesByVendedor_segundaLlamada_noVuelveAGolpearElRepositorio() {
        String codigoVendedor = "CACHE-V01";
        when(clienteRepository.findByCodigoVendedorOrderByRazonAsc(codigoVendedor))
                .thenReturn(List.of(entidad("11111111-1", "001")));

        List<ClienteDTO> primera = clienteService.getClientesByVendedor(codigoVendedor);
        List<ClienteDTO> segunda = clienteService.getClientesByVendedor(codigoVendedor);

        assertThat(primera).hasSize(1);
        assertThat(segunda).hasSize(1);
        verify(clienteRepository, times(1)).findByCodigoVendedorOrderByRazonAsc(codigoVendedor);
    }

    @Test
    void getClientesByRuta_segundaLlamada_noVuelveAGolpearElRepositorio() {
        String ruta = "CACHE-R01";
        when(clienteRepository.getClienteByCodigoRuta(ruta))
                .thenReturn(List.of(entidad("22222222-2", "001")));

        clienteService.getClientesByRuta(ruta);
        clienteService.getClientesByRuta(ruta);

        verify(clienteRepository, times(1)).getClienteByCodigoRuta(ruta);
    }

    @Test
    void getClienteById_segundaLlamada_noVuelveAGolpearElRepositorio() {
        ClienteId id = new ClienteId("33333333-3", "001");
        when(clienteRepository.findById(id)).thenReturn(Optional.of(entidad("33333333-3", "001")));

        clienteService.getClienteById("33333333-3", "001");
        clienteService.getClienteById("33333333-3", "001");

        verify(clienteRepository, times(1)).findById(id);
    }

    @Test
    void getAllClientes_segundaLlamada_noVuelveAGolpearElRepositorio() {
        when(clienteRepository.findAll()).thenReturn(List.of(entidad("44444444-4", "001")));

        clienteService.getAllClientes();
        clienteService.getAllClientes();

        verify(clienteRepository, times(1)).findAll();
    }

    @Test
    void createOrUpdateCliente_evictaElCacheDeClientesByVendedor() {
        String codigoVendedor = "CACHE-V02";
        when(clienteRepository.findByCodigoVendedorOrderByRazonAsc(codigoVendedor))
                .thenReturn(List.of(entidad("55555555-5", "001")));

        clienteService.getClientesByVendedor(codigoVendedor);
        clienteService.getClientesByVendedor(codigoVendedor);
        verify(clienteRepository, times(1)).findByCodigoVendedorOrderByRazonAsc(codigoVendedor);

        Cliente guardado = entidad("66666666-6", "002");
        when(clienteRepository.save(any())).thenReturn(guardado);

        ClienteDTO nuevoCliente = new ClienteDTO();
        nuevoCliente.setRut("66666666-6");
        nuevoCliente.setCodigo("002");
        nuevoCliente.setCodigoVendedor(codigoVendedor);
        clienteService.createOrUpdateCliente(nuevoCliente);

        clienteService.getClientesByVendedor(codigoVendedor);
        verify(clienteRepository, times(2)).findByCodigoVendedorOrderByRazonAsc(codigoVendedor);
    }
}

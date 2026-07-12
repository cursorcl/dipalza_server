package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Venta;
import cl.eos.dipalza.entity.VentaDetalle;
import cl.eos.dipalza.repository.VentaDetalleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VentaDetalleServiceTest {

    @Mock VentaDetalleRepository repo;
    @InjectMocks VentaDetalleService service;

    private VentaDetalle detalle(Long id, Long ventaId) {
        Venta v = new Venta();
        v.setId(ventaId);
        VentaDetalle d = new VentaDetalle();
        d.setId(id);
        d.setCantidad(BigDecimal.ONE);
        d.setPrecioUnitario(BigDecimal.valueOf(1000));
        d.setPorcentajeDescuento(BigDecimal.ZERO);
        d.setPorcentajeIva(BigDecimal.valueOf(19));
        d.setPorcentajeIla(BigDecimal.ZERO);
        v.addDetalle(d); // sets d.venta = v
        return d;
    }

    @Test
    void listarDetallesOptimized_conResultados_retornaDTOs() {
        when(repo.findAllOptimizedByVentaId(10L)).thenReturn(List.of(detalle(1L, 10L), detalle(2L, 10L)));
        assertThat(service.listarDetallesOptimized(10L)).hasSize(2);
    }

    @Test
    void listarDetallesOptimized_sinResultados_retornaListaVacia() {
        when(repo.findAllOptimizedByVentaId(99L)).thenReturn(List.of());
        assertThat(service.listarDetallesOptimized(99L)).isEmpty();
    }
}

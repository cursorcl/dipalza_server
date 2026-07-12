package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.Venta;
import cl.eos.dipalza.entity.VentaDetalle;
import cl.eos.dipalza.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas unitarias para recalcularTotalesVenta en VentaService.
 *
 * Cubre el bug donde totalNeto usaba `total` (con IVA/ILA acumulados)
 * en vez de `totalNeto`, inflando el total con cada ítem adicional.
 */
@ExtendWith(MockitoExtension.class)
class VentaServiceTotalesTest {

    @Mock VentaRepository ventaRepository;
    @Mock VentaDetalleRepository ventaDetalleRepository;
    @Mock VentaDetallePiezaRepository ventaDetallePiezaRepository;
    @Mock ProductoRepository productoRepository;
    @Mock NumeradoRepository numeradoRepository;
    @Mock ClienteRepository clienteRepository;
    @Mock RutaRepository rutaRepository;
    @Mock VendedorRepository vendedorRepository;
    @Mock CondicionVentaRepository condicionVentaRepository;

    @InjectMocks VentaService service;

    private void recalcular(Venta venta) throws Exception {
        Method m = VentaService.class.getDeclaredMethod("recalcularTotalesVenta", Venta.class);
        m.setAccessible(true);
        m.invoke(service, venta);
    }

    private VentaDetalle detalle(double cantidad, double precioUnitario,
                                 double porcDesc, double porcIva, double porcIla) {
        VentaDetalle d = new VentaDetalle();
        d.setCantidad(BigDecimal.valueOf(cantidad));
        d.setPrecioUnitario(BigDecimal.valueOf(precioUnitario));
        d.setPorcentajeDescuento(BigDecimal.valueOf(porcDesc));
        d.setPorcentajeIva(BigDecimal.valueOf(porcIva));
        d.setPorcentajeIla(BigDecimal.valueOf(porcIla));
        return d;
    }

    private Venta ventaCon(VentaDetalle... detalles) {
        Venta v = new Venta();
        for (VentaDetalle d : detalles) v.addDetalle(d);
        return v;
    }

    @Test
    void unItem_sinImpuestos_totalIgualAlNeto() throws Exception {
        Venta v = ventaCon(detalle(2, 1000, 0, 0, 0));
        recalcular(v);
        assertThat(v.getTotalNeto()).isEqualByComparingTo("2000");
        assertThat(v.getTotalIva()).isEqualByComparingTo("0");
        assertThat(v.getTotalIla()).isEqualByComparingTo("0");
        assertThat(v.getTotal()).isEqualByComparingTo("2000");
    }

    @Test
    void unItem_conIVA19_totalIncluyeIVA() throws Exception {
        Venta v = ventaCon(detalle(1, 1000, 0, 19, 0));
        recalcular(v);
        assertThat(v.getTotalNeto()).isEqualByComparingTo("1000");
        assertThat(v.getTotalIva()).isEqualByComparingTo("190");
        assertThat(v.getTotalDescuento()).isEqualByComparingTo("0");
        assertThat(v.getTotal()).isEqualByComparingTo("1190");
    }

    @Test
    void dosItems_conIVA_totalNoInfladoPorIVADeIteracionAnterior() throws Exception {
        // Regresión del bug: con el defecto, totalNeto = total.add(linea)
        // usaba `total` (que ya incluía IVA) en vez de `totalNeto`.
        // Resultado incorrecto: totalNeto=3190, total=3760.
        // Resultado correcto:   totalNeto=3000, total=3570.
        Venta v = ventaCon(
                detalle(1, 1000, 0, 19, 0),  // neto=1000, iva=190
                detalle(1, 2000, 0, 19, 0)   // neto=2000, iva=380
        );
        recalcular(v);
        assertThat(v.getTotalNeto()).isEqualByComparingTo("3000");
        assertThat(v.getTotalIva()).isEqualByComparingTo("570");
        assertThat(v.getTotal()).isEqualByComparingTo("3570");
    }

    @Test
    void tresItems_conIVA_totalCorrecto() throws Exception {
        Venta v = ventaCon(
                detalle(1, 1000, 0, 19, 0),  // neto=1000
                detalle(1, 2000, 0, 19, 0),  // neto=2000
                detalle(1, 3000, 0, 19, 0)   // neto=3000
        );
        recalcular(v);
        assertThat(v.getTotalNeto()).isEqualByComparingTo("6000");
        assertThat(v.getTotalIva()).isEqualByComparingTo("1140");
        assertThat(v.getTotal()).isEqualByComparingTo("7140");
    }

    @Test
    void unItem_conDescuento10pct_netoYTotalReducidos() throws Exception {
        // PU=1000, cant=1, desc=10% → desc=100, neto=900, iva(19%)=171, total=1071
        Venta v = ventaCon(detalle(1, 1000, 10, 19, 0));
        recalcular(v);
        assertThat(v.getTotalDescuento()).isEqualByComparingTo("100");
        assertThat(v.getTotalNeto()).isEqualByComparingTo("900");
        assertThat(v.getTotalIva()).isEqualByComparingTo("171");
        assertThat(v.getTotal()).isEqualByComparingTo("1071");
    }

    @Test
    void unItem_conILA_totalIncluyeIVAyILA() throws Exception {
        // PU=1000, cant=1, IVA=19%, ILA=10% → neto=1000, iva=190, ila=100, total=1290
        Venta v = ventaCon(detalle(1, 1000, 0, 19, 10));
        recalcular(v);
        assertThat(v.getTotalNeto()).isEqualByComparingTo("1000");
        assertThat(v.getTotalIva()).isEqualByComparingTo("190");
        assertThat(v.getTotalIla()).isEqualByComparingTo("100");
        assertThat(v.getTotal()).isEqualByComparingTo("1290");
    }

    @Test
    void dosItems_conILAyIVA_totalNoInfladoPorImpuestosAnteriores() throws Exception {
        // Igual que el test de regresión pero con ILA también
        // Item 1: neto=1000, iva=190, ila=100
        // Item 2: neto=2000, iva=380, ila=200
        // Esperado: totalNeto=3000, totalIva=570, totalIla=300, total=3870
        Venta v = ventaCon(
                detalle(1, 1000, 0, 19, 10),
                detalle(1, 2000, 0, 19, 10)
        );
        recalcular(v);
        assertThat(v.getTotalNeto()).isEqualByComparingTo("3000");
        assertThat(v.getTotalIva()).isEqualByComparingTo("570");
        assertThat(v.getTotalIla()).isEqualByComparingTo("300");
        assertThat(v.getTotal()).isEqualByComparingTo("3870");
    }

    @Test
    void sinDetalles_todosLosTotalesEnCero() throws Exception {
        Venta v = new Venta();
        recalcular(v);
        assertThat(v.getTotalNeto()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(v.getTotalIva()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(v.getTotalIla()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(v.getTotalDescuento()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(v.getTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}

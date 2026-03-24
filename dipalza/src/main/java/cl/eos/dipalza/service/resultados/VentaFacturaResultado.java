package cl.eos.dipalza.service.resultados;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record VentaFacturaResultado(
        String factura,
        LocalDateTime fecha,
        BigDecimal total,
        List<VentaItemResultado> items,
        String mensaje
) {

}

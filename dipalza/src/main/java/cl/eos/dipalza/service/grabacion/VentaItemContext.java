package cl.eos.dipalza.service.grabacion;

import cl.eos.dipalza.model.venta.VentaDetalleDTO;

public record VentaItemContext(
        VentaDetalleDTO detalle,
        String numeroFactura,
        String idenficador,
        int nroLinea
) {
    public boolean esNumerado() {
        return this.detalle.getPiezas() != null && this.detalle.getPiezas().intValue() > 0;
    }
}

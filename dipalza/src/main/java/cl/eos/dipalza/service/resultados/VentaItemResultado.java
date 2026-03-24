package cl.eos.dipalza.service.resultados;

public record VentaItemResultado(
        String codigoProducto,
        int nroLinea,
        float precioVentaNeto,
        float valorTotalVentaNeta,
        float cantidadAsignada,
        float cantidadFaltante,
        float valorTotalIva,
        float valorTotalIla,
        float valorTotalDescuento,
        NumeracionResultado numeracion,
        String error
) {}

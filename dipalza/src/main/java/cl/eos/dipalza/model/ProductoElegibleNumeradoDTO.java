package cl.eos.dipalza.model;

import java.math.BigDecimal;

public record ProductoElegibleNumeradoDTO(
        String codigoProducto,
        String nombreProducto,
        BigDecimal stock,
        BigDecimal piezas,
        boolean tieneRegistrosAsociados
) {}

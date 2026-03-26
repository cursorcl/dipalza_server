package cl.eos.dipalza.service.resultados;

import java.util.List;

public record NumeracionResultado(
        Float cantidadPiezasAsignadas,
        Float cantidadPiezasFaltantes,
        List<String> numerosPiezasAsignadas,
        Float pesoRealDeVenta
) {}

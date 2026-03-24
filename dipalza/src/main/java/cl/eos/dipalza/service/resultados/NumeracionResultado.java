package cl.eos.dipalza.service.resultados;

import java.math.BigDecimal;
import java.util.List;

public record NumeracionResultado(
        Integer cantidadPiezasAsignadas,
        Integer cantidadPiezasFaltantes,
        List<String> numerosPiezasAsignadas,
        BigDecimal pesoRealDeVenta
) {}

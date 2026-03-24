package cl.eos.dipalza.service.grabacion;

public record MontosVenta(
        float ventaNetaReal,
        float valorIvaDeLaVenta,
        float valorIlaDeLaVenta,
        float valorDescuentoDeLaVenta
) {}
package cl.eos.dipalza.service.grabacion;

import cl.eos.dipalza.entity.Numerado;

import java.util.List;

public record NumeradosAsignados(
        List<Numerado> numeradosUtilizados,
        float pesoRealAsignado,
        String nombreProductoConNumerados
) {}
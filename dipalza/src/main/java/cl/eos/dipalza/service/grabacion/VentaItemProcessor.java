package cl.eos.dipalza.service.grabacion;

import cl.eos.dipalza.service.resultados.VentaItemResultado;

public interface VentaItemProcessor {
    boolean soporta(VentaItemContext context);

    VentaItemResultado procesar(VentaItemContext context);
}


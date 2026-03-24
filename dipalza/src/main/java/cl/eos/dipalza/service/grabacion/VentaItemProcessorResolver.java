package cl.eos.dipalza.service.grabacion;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VentaItemProcessorResolver {

    private final List<VentaItemProcessor> processors;

    public VentaItemProcessorResolver(List<VentaItemProcessor> processors) {
        this.processors = processors;
    }

    public VentaItemProcessor resolve(VentaItemContext context) {

        return processors.stream()
                .filter(p -> p.soporta(context))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No existe processor para el item"));
    }
}
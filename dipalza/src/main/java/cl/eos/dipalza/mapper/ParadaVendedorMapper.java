package cl.eos.dipalza.mapper;

import cl.eos.dipalza.entity.ParadaVendedor;
import cl.eos.dipalza.model.ParadaVendedorDTO;

public class ParadaVendedorMapper {

    private ParadaVendedorMapper() {
    }

    public static ParadaVendedorDTO toDTO(ParadaVendedor p) {
        if (p == null || p.getVendedor() == null) {
            return null;
        }
        return new ParadaVendedorDTO(
                p.getId(), p.getVendedor().getId().getCodigo(), p.getVendedor().getId().getTipo(),
                p.getVendedor().getNombre(), p.getLatitud(), p.getLongitud(), p.getCalle(),
                p.getHoraInicio(), p.getHoraFin());
    }
}

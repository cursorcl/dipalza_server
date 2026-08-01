package cl.eos.dipalza.service;

import cl.eos.dipalza.mapper.ParadaVendedorMapper;
import cl.eos.dipalza.model.ParadaVendedorDTO;
import cl.eos.dipalza.repository.ParadaVendedorRepository;
import cl.eos.dipalza.specifications.ParadaVendedorSpecifications;
import cl.eos.dipalza.specifications.PosicionFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DeteccionService {

    private final ParadaVendedorRepository paradaVendedorRepository;

    public DeteccionService(ParadaVendedorRepository paradaVendedorRepository) {
        this.paradaVendedorRepository = paradaVendedorRepository;
    }

    @Transactional(readOnly = true)
    public List<ParadaVendedorDTO> buscarHistorico(PosicionFilter filter) {
        return paradaVendedorRepository.findAll(ParadaVendedorSpecifications.conFiltros(filter))
                .stream().map(ParadaVendedorMapper::toDTO).toList();
    }
}

package cl.eos.dipalza.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.eos.dipalza.mapper.VendedorMapper;
import cl.eos.dipalza.model.VendedorDTO;
import cl.eos.dipalza.repository.VendedorRepository;

@Service
public class VendedorService {

    private final VendedorRepository vendedorRepository;

    public VendedorService(VendedorRepository vendedorRepository) {
        this.vendedorRepository = vendedorRepository;
    }

    @Transactional(readOnly = true)
    public List<VendedorDTO> listarTodos() {
        return vendedorRepository.findAll()
                .stream()
                .map(VendedorMapper::toDto)
                .toList();
    }
}

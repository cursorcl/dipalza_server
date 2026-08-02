package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.ParadaVendedorGrupoActual;
import cl.eos.dipalza.mapper.ParadaVendedorMapper;
import cl.eos.dipalza.model.ParadaVendedorDTO;
import cl.eos.dipalza.repository.ParadaVendedorGrupoActualRepository;
import cl.eos.dipalza.repository.ParadaVendedorRepository;
import cl.eos.dipalza.specifications.ParadaVendedorSpecifications;
import cl.eos.dipalza.specifications.PosicionFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class DeteccionService {

    private final ParadaVendedorRepository paradaVendedorRepository;
    private final ParadaVendedorGrupoActualRepository grupoActualRepository;

    public DeteccionService(ParadaVendedorRepository paradaVendedorRepository,
                             ParadaVendedorGrupoActualRepository grupoActualRepository) {
        this.paradaVendedorRepository = paradaVendedorRepository;
        this.grupoActualRepository = grupoActualRepository;
    }

    @Transactional(readOnly = true)
    public List<ParadaVendedorDTO> buscarHistorico(PosicionFilter filter) {
        Set<Long> idsEnCurso = new HashSet<>();
        for (ParadaVendedorGrupoActual grupo : grupoActualRepository.findAll()) {
            if (grupo.getParadaVendedorId() != null) {
                idsEnCurso.add(grupo.getParadaVendedorId());
            }
        }

        return paradaVendedorRepository.findAll(ParadaVendedorSpecifications.conFiltros(filter))
                .stream()
                .map(p -> ParadaVendedorMapper.toDTO(p, idsEnCurso.contains(p.getId())))
                .filter(Objects::nonNull)
                .toList();
    }
}

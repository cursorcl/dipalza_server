package cl.eos.dipalza.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import cl.eos.dipalza.entity.VendedorRuta;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.entity.ids.VendedorRutaId;
import cl.eos.dipalza.mapper.RutaMapper;
import cl.eos.dipalza.model.RutaDTO;
import cl.eos.dipalza.repository.RutaRepository;
import cl.eos.dipalza.repository.VendedorRepository;
import cl.eos.dipalza.repository.VendedorRutaRepository;

@Service
public class VendedorRutaService {

    private final VendedorRutaRepository vendedorRutaRepository;
    private final VendedorRepository vendedorRepository;
    private final RutaRepository rutaRepository;
    private final RutaMapper rutaMapper;

    public VendedorRutaService(VendedorRutaRepository vendedorRutaRepository,
                                VendedorRepository vendedorRepository,
                                RutaRepository rutaRepository,
                                RutaMapper rutaMapper) {
        this.vendedorRutaRepository = vendedorRutaRepository;
        this.vendedorRepository = vendedorRepository;
        this.rutaRepository = rutaRepository;
        this.rutaMapper = rutaMapper;
    }

    public List<RutaDTO> getRutasByVendedor(String codigo, String tipo) {
        return vendedorRutaRepository.findByIdCodigoVendedorAndIdTipoVendedor(codigo, tipo)
                .stream()
                .map(vr -> rutaMapper.toDTO(vr.getRuta()))
                .collect(Collectors.toList());
    }

    @Transactional
    public List<RutaDTO> asignarRutas(String codigo, String tipo, List<String> codigosRuta) {
        if (vendedorRepository.findById(new VendedorId(codigo, tipo)).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vendedor no encontrado");
        }
        for (String codigoRuta : codigosRuta) {
            if (rutaRepository.findById(codigoRuta).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ruta " + codigoRuta + " no encontrada");
            }
        }

        vendedorRutaRepository.deleteByIdCodigoVendedorAndIdTipoVendedor(codigo, tipo);

        List<VendedorRuta> nuevas = codigosRuta.stream()
                .map(codigoRuta -> {
                    VendedorRuta vr = new VendedorRuta();
                    vr.setId(new VendedorRutaId(codigo, tipo, codigoRuta));
                    return vr;
                })
                .collect(Collectors.toList());
        vendedorRutaRepository.saveAll(nuevas);

        return getRutasByVendedor(codigo, tipo);
    }
}

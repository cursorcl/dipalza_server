package cl.eos.dipalza.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import cl.eos.dipalza.entity.Ruta;
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

    @Transactional(readOnly = true)
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

        List<Ruta> rutas = new ArrayList<>();
        for (String codigoRuta : codigosRuta) {
            Ruta ruta = rutaRepository.findById(codigoRuta)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Ruta " + codigoRuta + " no encontrada"));
            rutas.add(ruta);
        }

        vendedorRutaRepository.deleteByIdCodigoVendedorAndIdTipoVendedor(codigo, tipo);

        // Se asigna la Ruta ya validada (no solo el id) porque, dentro de esta
        // misma transacción, el identity map de Hibernate devolverá estas mismas
        // instancias en la relectura de getRutasByVendedor(); sin la asociación
        // seteada, esa relectura vería `ruta == null`.
        List<VendedorRuta> nuevas = rutas.stream()
                .map(ruta -> {
                    VendedorRuta vr = new VendedorRuta();
                    vr.setId(new VendedorRutaId(codigo, tipo, ruta.getCodigo()));
                    vr.setRuta(ruta);
                    return vr;
                })
                .collect(Collectors.toList());
        vendedorRutaRepository.saveAll(nuevas);

        return getRutasByVendedor(codigo, tipo);
    }
}

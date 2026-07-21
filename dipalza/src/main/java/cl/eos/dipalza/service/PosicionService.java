package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.HistorialPosicion;
import cl.eos.dipalza.entity.Posicion;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.mapper.PosicionMapper;
import cl.eos.dipalza.model.HistorialPosicionDTO;
import cl.eos.dipalza.model.PosicionDTO;
import cl.eos.dipalza.repository.HistorialPosicionRepository;
import cl.eos.dipalza.repository.PosicionRepository;
import cl.eos.dipalza.repository.VendedorRepository;
import cl.eos.dipalza.specifications.HistorialPosicionSpecifications;
import cl.eos.dipalza.specifications.PosicionFilter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PosicionService {

    private final PosicionRepository posicionRepository;
    private final HistorialPosicionRepository historialRepository;
    private final VendedorRepository vendedorRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public PosicionService(PosicionRepository posicionRepository, HistorialPosicionRepository historialRepository, VendedorRepository vendedorRepository, SimpMessagingTemplate messagingTemplate) {
        this.posicionRepository = posicionRepository;
        this.historialRepository = historialRepository;
        this.vendedorRepository = vendedorRepository;
        this.messagingTemplate = messagingTemplate;
    }


    public List<PosicionDTO> obtenerActuales() {
        List<Posicion> entidades =  posicionRepository.findAll();
        return entidades.stream()
                .map(PosicionMapper::toPosicionDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<HistorialPosicionDTO> buscarHistorico(PosicionFilter filter) {
        return historialRepository.findAll(HistorialPosicionSpecifications.conFiltros(filter))
                .stream()
                .map(PosicionMapper::toHistorialDTO)
                .toList();
    }

    ///  Almacena el registro de posición asociado al movil
    @Transactional
    public void registrarUbicacion(PosicionDTO dto) {

        var lon = dto.longitud();
        var lat = dto.latitud();
        var vCodigo = dto.vendedorId();
        var vTipo = dto.vendedorCodigo() == null ? "0 " : dto.vendedorCodigo();
        var fecha = dto.fechaHora();

        VendedorId vendedorId = new VendedorId(vCodigo, vTipo);
        Vendedor vendedorRef = vendedorRepository.getReferenceById(vendedorId);

        // 1. Actualizar o crear el estado actual
        Posicion posicion = posicionRepository.findByVendedorId(vendedorId);
        if(posicion == null) {
            posicion = new Posicion();
            posicion.setId(vendedorId);
            posicion.setVendedor(vendedorRef);
        }


        posicion.setLatitud(lat);
        posicion.setLongitud(lon);
        posicion.setFechaHora(fecha);
        posicionRepository.save(posicion);

        // 2. Insertar en el historial
        HistorialPosicion historial = new HistorialPosicion();
        historial.setVendedor(vendedorRef);
        historial.setLatitud(lat);
        historial.setLongitud(lon);
        historial.setFechaHora(fecha);
        historialRepository.save(historial);


        messagingTemplate.convertAndSend("/topic/posiciones", dto);
    }
}

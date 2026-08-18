package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.HistorialPosicion;
import cl.eos.dipalza.entity.Posicion;
import cl.eos.dipalza.entity.Vendedor;
import cl.eos.dipalza.entity.ids.VendedorId;
import cl.eos.dipalza.mapper.PosicionMapper;
import cl.eos.dipalza.model.HistorialPosicionDTO;
import cl.eos.dipalza.model.HistorialResumenDiaDTO;
import cl.eos.dipalza.model.PosicionDTO;
import cl.eos.dipalza.repository.HistorialPosicionRepository;
import cl.eos.dipalza.repository.PosicionRepository;
import cl.eos.dipalza.repository.VendedorRepository;
import cl.eos.dipalza.specifications.HistorialPosicionSpecifications;
import cl.eos.dipalza.specifications.PosicionFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class PosicionService {

    private static final Logger log = LoggerFactory.getLogger(PosicionService.class);

    private final PosicionRepository posicionRepository;
    private final HistorialPosicionRepository historialRepository;
    private final VendedorRepository vendedorRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final DeteccionParadaService deteccionParadaService;

    public PosicionService(PosicionRepository posicionRepository, HistorialPosicionRepository historialRepository, VendedorRepository vendedorRepository, SimpMessagingTemplate messagingTemplate, DeteccionParadaService deteccionParadaService) {
        this.posicionRepository = posicionRepository;
        this.historialRepository = historialRepository;
        this.vendedorRepository = vendedorRepository;
        this.messagingTemplate = messagingTemplate;
        this.deteccionParadaService = deteccionParadaService;
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

    @Transactional(readOnly = true)
    public List<HistorialResumenDiaDTO> buscarResumenHistorico(String vendedorCodigo, String vendedorTipo) {
        var desde = LocalDate.now().minusDays(30).atStartOfDay();
        return historialRepository.resumenPorDia(vendedorCodigo, vendedorTipo, desde)
                .stream()
                .map(p -> new HistorialResumenDiaDTO(p.getDia(), p.getCantidadPuntos(), p.getHoraInicio(), p.getHoraFin()))
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

        // 3. Detectar paradas (no debe afectar el registro de posicion si falla)
        try {
            deteccionParadaService.procesarNuevoPunto(vendedorId, vendedorRef, lat, lon, fecha);
        } catch (Exception e) {
            log.warn("Fallo la deteccion de parada para vendedor {}: no afecta el registro de posicion", vendedorId, e);
        }

        messagingTemplate.convertAndSend("/topic/posiciones", PosicionMapper.toPosicionDTO(posicion));
    }
}

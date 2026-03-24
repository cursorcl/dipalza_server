package cl.eos.dipalza.service;

import cl.eos.dipalza.entity.VentaDetalle;
import cl.eos.dipalza.mapper.VentaMapper;
import cl.eos.dipalza.model.venta.VentaDetalleDTO;
import cl.eos.dipalza.repository.VentaDetalleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class VentaDetalleService {

    @Autowired
    private VentaDetalleRepository ventaDetalleRepository;

    @Transactional(readOnly = true)
    public List<VentaDetalleDTO> listarDetallesOptimized(Long saleId) {
        // Buscamos detalles cargando el Producto pero NO las PiezasUsadas
        List<VentaDetalle> entidades = ventaDetalleRepository.findAllOptimizedByVentaId(saleId);

        return entidades.stream()
                .map(VentaMapper::toVentaDetalleDTO)
                .toList();
    }
}
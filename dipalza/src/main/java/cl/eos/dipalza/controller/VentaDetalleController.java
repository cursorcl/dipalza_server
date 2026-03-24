package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.venta.VentaDetalleDTO;
import cl.eos.dipalza.service.VentaDetalleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ventadetalle")
public class VentaDetalleController {


    private final VentaDetalleService ventaDetalleService;

    public VentaDetalleController(VentaDetalleService ventaDetalleService) {
        this.ventaDetalleService = ventaDetalleService;
    }
    @GetMapping("/{saleId}")
    public ResponseEntity<List<VentaDetalleDTO>> listarDetallesPorVenta(@PathVariable Long saleId) {
        // Llamamos al método optimizado del servicio
        List<VentaDetalleDTO> detalles = ventaDetalleService.listarDetallesOptimized(saleId);
        return ResponseEntity.ok(detalles);
    }
}

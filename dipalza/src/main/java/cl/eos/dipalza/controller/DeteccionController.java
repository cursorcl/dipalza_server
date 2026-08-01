package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.ParadaVendedorDTO;
import cl.eos.dipalza.service.DeteccionService;
import cl.eos.dipalza.specifications.PosicionFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/deteccion")
public class DeteccionController {

    private final DeteccionService deteccionService;

    public DeteccionController(DeteccionService deteccionService) {
        this.deteccionService = deteccionService;
    }

    @PostMapping("/historico")
    public ResponseEntity<List<ParadaVendedorDTO>> obtenerHistorico(@RequestBody PosicionFilter filter) {
        return ResponseEntity.ok(deteccionService.buscarHistorico(filter));
    }
}

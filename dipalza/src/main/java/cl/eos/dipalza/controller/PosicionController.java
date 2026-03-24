package cl.eos.dipalza.controller;

import cl.eos.dipalza.model.HistorialPosicionDTO;
import cl.eos.dipalza.model.PosicionDTO;
import cl.eos.dipalza.service.PosicionService;
import cl.eos.dipalza.specifications.PosicionFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/posicion")
public class PosicionController {

    private final PosicionService posicionService;

    public PosicionController(PosicionService posicionService) {
        this.posicionService = posicionService;
    }

    @GetMapping
    public ResponseEntity<List<PosicionDTO>> obtenerPosiciones() {
        // El servicio decidirá si consulta Posicion o HistorialPosicion
        List<PosicionDTO> resultados = posicionService.obtenerActuales();
        return ResponseEntity.ok(resultados);
    }

    @PostMapping("/historico") // Cambiar a POST para soportar el cuerpo del filtro
    public ResponseEntity<List<HistorialPosicionDTO>> obtenerHistorico(
            @RequestBody PosicionFilter filter) { // @RequestBody es indispensable aquí

        // Llamada al servicio, delegando la lógica de negocio
        List<HistorialPosicionDTO> dtos = posicionService.buscarHistorico(filter);
        return ResponseEntity.ok(dtos);
    }

    @PostMapping
    public ResponseEntity<Void> registrarPosicion(@RequestBody PosicionDTO dto) {
        posicionService.registrarUbicacion(dto);
        return ResponseEntity.accepted().build();
    }



}
